package io.kbrag.app.index;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.IndexRegistry;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.IndexRegistryStatus;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.IndexRegistryMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the physical index cleanup of the M16 contract section 4.1: a deletion marks the registry
 * rows and opens one retryable CLEANUP task, the runner treats an already vanished index as cleaned,
 * an engine refusal keeps the row and the task alive for the retry scan, and the same runner also
 * collects the rows the M7 snapshot retirement had been marking without ever dropping.
 *
 * <p>The task row is mocked statefully - insert and updateById write into one holder that selectOne
 * reads back - because the service reloads its own task through the mapper on every phase change,
 * and a stateless mock would hand it a fresh null and make it open a second task.
 *
 * @author owlzhangfq@gmail.com
 */
class IndexCleanupServiceTest {

    private static final String KB_ID = "kb_alpha";
    private static final String TASK_ID = "task_cleanup_1";
    private static final String ES_INDEX = "kb_alpha_bm25_v1";
    private static final String QDRANT_INDEX = "kb_alpha_tev4_v1";

    private IndexRegistryMapper indexRegistryMapper;
    private KbTaskMapper kbTaskMapper;
    private FulltextStore fulltextStore;
    private VectorStore vectorStore;
    private KbProperties properties;
    private IndexCleanupService service;

    /** The single task row the mapper mock persists across the service's own reloads. */
    private final AtomicReference<KbTask> taskRow = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(IndexRegistry.class, KbTask.class);
        indexRegistryMapper = mock(IndexRegistryMapper.class);
        kbTaskMapper = mock(KbTaskMapper.class);
        fulltextStore = mock(FulltextStore.class);
        vectorStore = mock(VectorStore.class);
        properties = new KbProperties();
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.taskId()).thenReturn(TASK_ID);
        taskRow.set(null);
        when(kbTaskMapper.selectOne(any())).thenAnswer(invocation -> taskRow.get());
        when(kbTaskMapper.insert(any(KbTask.class))).thenAnswer(invocation -> {
            taskRow.set(invocation.getArgument(0));
            return 1;
        });
        when(kbTaskMapper.updateById(any(KbTask.class))).thenAnswer(invocation -> {
            taskRow.set(invocation.getArgument(0));
            return 1;
        });
        // A same-thread executor turns the after-commit hand-off into a plain call, so every
        // assertion below sees the completed run instead of racing it.
        service = new IndexCleanupService(indexRegistryMapper, kbTaskMapper, bizIdGenerator,
                fulltextStore, vectorStore, properties, Runnable::run);
    }

    @Test
    void shouldMarkTheRegistryAndOpenOneCleanupTaskOnSubmit() {
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of());

        service.submit(KB_ID);

        // The mark is the work queue: whatever carries PENDING_CLEANUP is what the runner drops,
        // so the deletion writes the status instead of remembering a list of names.
        verify(indexRegistryMapper).update(eq(null), any());
        KbTask task = taskRow.get();
        assertNotNull(task);
        assertEquals(TASK_ID, task.getTaskId());
        assertEquals(TaskType.CLEANUP, task.getTaskType());
        assertEquals(KB_ID, task.getBizId());
        // No transaction is active in the test, so submit runs the cleanup inline and the empty
        // registry answer completes the task immediately.
        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        verify(kbTaskMapper, times(1)).insert(any(KbTask.class));
    }

    @Test
    void shouldTreatAnAlreadyMissingIndexAsCleaned() {
        when(indexRegistryMapper.selectList(any()))
                .thenReturn(List.of(pendingRow(1L, VectorEngine.ES, ES_INDEX)));
        when(fulltextStore.indexExists(ES_INDEX)).thenReturn(false);

        service.run(KB_ID);

        // Idempotency is what makes a retry converge: the successes of the previous attempt must
        // not fail the next one, so a gone index is deleted from the registry without a drop call.
        verify(fulltextStore, never()).dropIndex(anyString());
        verify(indexRegistryMapper).deleteById(1L);
        assertEquals(TaskStatus.SUCCESS, taskRow.get().getStatus());
    }

    @Test
    void shouldKeepTheRowAndFailTheTaskWhenTheEngineRefusesTheDrop() {
        when(indexRegistryMapper.selectList(any())).thenReturn(List.of(
                pendingRow(1L, VectorEngine.ES, ES_INDEX),
                pendingRow(2L, VectorEngine.QDRANT, QDRANT_INDEX)));
        when(fulltextStore.indexExists(ES_INDEX)).thenReturn(true);
        when(vectorStore.indexExists(QDRANT_INDEX)).thenReturn(true);
        doThrow(new IllegalStateException("es down")).when(fulltextStore).dropIndex(ES_INDEX);

        service.run(KB_ID);

        // Best effort per row: the unreachable engine must not protect the indices of the other,
        // and the surviving row plus the FAILED task are what hand the failure to the retry scan.
        verify(vectorStore).dropIndex(QDRANT_INDEX);
        verify(indexRegistryMapper).deleteById(2L);
        verify(indexRegistryMapper, never()).deleteById(1L);
        KbTask task = taskRow.get();
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertTrue(task.getFailReason().contains(ES_INDEX));
    }

    @Test
    void shouldCollectTheRowsTheSnapshotRetirementLeftBehind() {
        // A snapshot retirement of M7 marks its rows PENDING_CLEANUP without a surrounding
        // deletion; the runner selects by status only, so those rows are the same work.
        when(indexRegistryMapper.selectList(any()))
                .thenReturn(List.of(pendingRow(7L, VectorEngine.QDRANT, "kb_alpha_tev4_s1")));
        when(vectorStore.indexExists("kb_alpha_tev4_s1")).thenReturn(true);

        service.run(KB_ID);

        verify(vectorStore).dropIndex("kb_alpha_tev4_s1");
        verify(indexRegistryMapper).deleteById(7L);
        assertEquals(TaskStatus.SUCCESS, taskRow.get().getStatus());
    }

    @Test
    void shouldRetryAFailedTaskAndAbandonOnePastTheBudget() {
        KbTask exhausted = cleanupTask("kb_gone", properties.getSync().getMaxRetry());
        KbTask retryable = cleanupTask(KB_ID, 1);
        when(kbTaskMapper.selectList(any())).thenReturn(List.of(exhausted, retryable));
        taskRow.set(retryable);
        when(indexRegistryMapper.selectList(any()))
                .thenReturn(List.of(pendingRow(1L, VectorEngine.ES, ES_INDEX)));
        when(fulltextStore.indexExists(ES_INDEX)).thenReturn(true);

        service.retryScan();

        // Only the task inside the budget runs again; the exhausted one is reported and left to
        // the operator, so the registry is queried exactly once.
        verify(indexRegistryMapper, times(1)).selectList(any());
        verify(fulltextStore).dropIndex(ES_INDEX);
        KbTask task = taskRow.get();
        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals(2, task.getRetryCount());
    }

    @Test
    void shouldStayQuietWhenTheCompensationSwitchIsOff() {
        properties.getSync().setCompensationEnabled(false);

        service.retryScan();

        // The scan rides the compensation switch of the index synchronization: a deployment that
        // silenced one background repair wants the other silent too.
        verify(kbTaskMapper, never()).selectList(any());
    }

    private IndexRegistry pendingRow(Long id, VectorEngine engine, String physicalIndexName) {
        IndexRegistry row = new IndexRegistry();
        row.setId(id);
        row.setKbId(KB_ID);
        row.setEngine(engine.code());
        row.setPhysicalIndexName(physicalIndexName);
        row.setStatus(IndexRegistryStatus.PENDING_CLEANUP);
        return row;
    }

    private KbTask cleanupTask(String kbId, int retryCount) {
        KbTask task = new KbTask();
        task.setTaskId(TASK_ID);
        task.setTaskType(TaskType.CLEANUP);
        task.setBizId(kbId);
        task.setStatus(TaskStatus.FAILED);
        task.setRetryCount(retryCount);
        return task;
    }
}
