package io.kbrag.app.graph;

import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.GraphExtractionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two stage shape of the extraction pipeline, the property the throughput rests on.
 *
 * <p>模型调用与图写入是两个性质相反的阶段：前者是几秒的网络等待、无共享状态，越宽越好；后者要 MERGE
 * 同名实体，而图 schema 用的是复合索引而非唯一约束（见 {@code Neo4jGraphStore}），并发写就会打架。
 * 拆开之后两个诉求同时成立 —— 这里验证的就是这个不变量：抽取真的并发，写入真的串行。
 *
 * <p>Progress is asserted to be throttled as well: the pipeline reports per finished chunk, so without
 * throttling a large corpus would write the task row once per passage.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphExtractionPipelineTest {

    private static final String KB_ID = "kb_test";
    private static final String VERSION_ID = "dv_1";
    private static final String TASK_ID = "task_graph";
    private static final String VALID_ANSWER =
            "{\"entities\":[{\"name\":\"A\",\"type\":\"person\"}],\"relations\":[]}";

    /** Extraction concurrency of the run under test, and the party count of the barrier below. */
    private static final int CONCURRENCY = 4;

    /** Chunks of the run, an exact multiple of the concurrency so the barrier fills twice. */
    private static final int CHUNK_COUNT = 8;

    /** Longest a chunk waits for its concurrent peers before the barrier gives up. */
    private static final int BARRIER_TIMEOUT_SECONDS = 5;

    private ChunkMapper chunkMapper;
    private KbTaskMapper kbTaskMapper;
    private GraphStore graphStore;
    private ChatProvider chatProvider;
    private GraphExtractionService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(KbTask.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        ActiveVersionResolver activeVersionResolver = mock(ActiveVersionResolver.class);
        chunkMapper = mock(ChunkMapper.class);
        kbTaskMapper = mock(KbTaskMapper.class);
        graphStore = mock(GraphStore.class);
        chatProvider = mock(ChatProvider.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);

        when(bizIdGenerator.taskId()).thenReturn(TASK_ID);
        when(kbTaskMapper.selectOne(any())).thenReturn(null);
        when(graphStore.isEnabled()).thenReturn(true);
        when(chatProvider.isConfigured()).thenReturn(true);
        when(knowledgeBaseService.graphEnabled(KB_ID)).thenReturn(true);
        when(knowledgeBaseService.indexConfigOf(KB_ID)).thenReturn(new KbIndexConfig());
        when(activeVersionResolver.activeVersionIds(KB_ID)).thenReturn(List.of(VERSION_ID));
        when(chunkMapper.selectList(any())).thenReturn(chunks());

        KbProperties properties = new KbProperties();
        properties.getGraph().setExtractConcurrency(CONCURRENCY);
        service = new GraphExtractionService(knowledgeBaseService, activeVersionResolver, chunkMapper,
                mock(DocumentVersionMapper.class), kbTaskMapper, graphStore, chatProvider,
                new GraphExtractionParser(), bizIdGenerator, properties);
    }

    @Test
    void shouldRunTheConfiguredNumberOfModelCallsAtOnce() throws Exception {
        // The barrier only opens once as many calls as the configured concurrency are waiting in it, so
        // a run that still serialised the chunks - or that fanned out narrower than configured - would
        // time out here and count every chunk as skipped. Nothing else in the run can produce a skip.
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY);
        when(chatProvider.complete(anyString(), anyString())).thenAnswer(invocation -> {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return VALID_ANSWER;
        });

        KbTask task = task();
        service.runFullExtraction(KB_ID, task);

        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals(0, task.getSkippedCount());
        verify(graphStore, org.mockito.Mockito.times(CHUNK_COUNT)).upsert(any());
    }

    @Test
    void shouldSerialiseTheGraphWritesWhileTheModelCallsFanOut() {
        AtomicInteger writing = new AtomicInteger();
        AtomicInteger peakWriters = new AtomicInteger();
        AtomicInteger peakExtractors = new AtomicInteger();
        AtomicInteger extracting = new AtomicInteger();

        when(chatProvider.complete(anyString(), anyString())).thenAnswer(invocation -> {
            peakExtractors.accumulateAndGet(extracting.incrementAndGet(), Math::max);
            Thread.sleep(20);
            extracting.decrementAndGet();
            return VALID_ANSWER;
        });
        doAnswer(invocation -> {
            peakWriters.accumulateAndGet(writing.incrementAndGet(), Math::max);
            Thread.sleep(5);
            writing.decrementAndGet();
            return null;
        }).when(graphStore).upsert(any());

        service.runFullExtraction(KB_ID, task());

        // 唯一写入者：两个事务同时 MERGE 同一个 (kb_id, name) 就会打架，而 schema 没有唯一约束兜底。
        assertEquals(1, peakWriters.get(), "graph writes must never overlap");
        // 而抽取阶段确实是并发的，否则串行化写入就只是把慢跑变成了单线程跑。
        assertTrue(peakExtractors.get() > 1,
                "model calls must overlap, observed peak " + peakExtractors.get());
    }

    @Test
    void shouldPublishProgressAtMostOncePerPercentagePoint() {
        when(chatProvider.complete(anyString(), anyString())).thenReturn(VALID_ANSWER);

        service.runFullExtraction(KB_ID, task());

        // 每完成一个分片都写一次任务行的话，一万个分片就是一万次更新，而控制台只看得见整数百分比。
        // 八个分片跨过的百分点最多八个，且行是按列更新的 —— 整行写会撞上乐观锁版本号。
        verify(kbTaskMapper, atMost(CHUNK_COUNT)).update(isNull(), any());
    }

    private List<Chunk> chunks() {
        List<Chunk> chunks = new ArrayList<>(CHUNK_COUNT);
        for (int index = 0; index < CHUNK_COUNT; index++) {
            Chunk chunk = new Chunk();
            chunk.setChunkId("ck_" + index);
            chunk.setKbId(KB_ID);
            chunk.setDocumentVersionId(VERSION_ID);
            chunk.setContent("passage " + index);
            chunk.setEnabled(1);
            chunks.add(chunk);
        }
        return chunks;
    }

    private KbTask task() {
        KbTask task = new KbTask();
        task.setTaskId(TASK_ID);
        task.setTaskType(TaskType.GRAPH_EXTRACT);
        task.setBizId(KB_ID);
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setSkippedCount(0);
        return task;
    }
}
