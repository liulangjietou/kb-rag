package io.kbrag.app.index;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the batching of the embedding stage: batches run concurrently, the status write is one
 * statement per batch rather than one per chunk, and a failing batch still fails the whole call with
 * the exception the pipeline branches on.
 *
 * <p>一个批次就是一次网络往返，串行跑完 50 个批次是文档索引里最长的一段等待，而批次之间没有任何
 * 依赖。这里验证并发真的发生（barrier 凑不齐就超时），以及并发没有把失败语义和落库次数搞坏。
 *
 * @author owlzhangfq@gmail.com
 */
class ChunkEmbedderTest {

    private static final String MODEL = "text-embedding-v4";
    private static final int BATCH_SIZE = 10;
    private static final int CHUNK_COUNT = 40;
    private static final int BATCH_COUNT = CHUNK_COUNT / BATCH_SIZE;
    private static final int DIMENSION = 4;
    private static final int BARRIER_TIMEOUT_SECONDS = 5;

    private EmbeddingProvider embeddingProvider;
    private ChunkMapper chunkMapper;
    private Executor embedExecutor;
    private ChunkEmbedder embedder;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Chunk.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        chunkMapper = mock(ChunkMapper.class);
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(embeddingProvider.maxBatchSize()).thenReturn(BATCH_SIZE);
        when(embeddingProvider.model()).thenReturn(MODEL);
        embedExecutor = Executors.newFixedThreadPool(BATCH_COUNT);
        embedder = new ChunkEmbedder(embeddingProvider, chunkMapper, embedExecutor);
    }

    @Test
    void shouldRunTheBatchesConcurrently() {
        // The barrier only opens once every batch is waiting inside it, so a sequential run would time
        // out and surface as a failed call rather than as a slow one.
        CyclicBarrier barrier = new CyclicBarrier(BATCH_COUNT);
        when(embeddingProvider.embed(anyList())).thenAnswer(invocation -> {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return vectors(invocation.<List<String>>getArgument(0).size());
        });

        Map<String, float[]> embedded = embedder.embed(chunks(CHUNK_COUNT));

        assertEquals(CHUNK_COUNT, embedded.size());
        verify(embeddingProvider, times(BATCH_COUNT)).embed(anyList());
    }

    @Test
    void shouldWriteTheStatusOncePerBatchInsteadOfOncePerChunk() {
        when(embeddingProvider.embed(anyList())).thenAnswer(invocation ->
                vectors(invocation.<List<String>>getArgument(0).size()));

        List<Chunk> chunks = chunks(CHUNK_COUNT);
        embedder.embed(chunks);

        // 40 个分片曾是 40 条 UPDATE；同一批次里状态是同一个值，一条语句就够。
        verify(chunkMapper, times(BATCH_COUNT)).update(isNull(), any());
        // 整行写会让并发批次互相顶掉乐观锁版本号，所以按列写。
        verify(chunkMapper, never()).updateById(any(Chunk.class));
        // 内存里的状态仍与库里一致——调用方拿着同一批对象继续往下走。
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getEmbeddingStatus() == EmbeddingStatus.DONE));
    }

    @Test
    void shouldSkipThePoolForASingleBatch() {
        // 注解修改路径一次只嵌入一个分片，调度它比直接跑更贵。
        AtomicInteger submitted = new AtomicInteger();
        embedder = new ChunkEmbedder(embeddingProvider, chunkMapper, runnable -> {
            submitted.incrementAndGet();
            runnable.run();
        });
        when(embeddingProvider.embed(anyList())).thenReturn(vectors(1));

        embedder.embed(chunks(1));

        assertEquals(0, submitted.get());
        verify(embeddingProvider).embed(anyList());
    }

    @Test
    void shouldFailTheWholeCallWithTheOriginalExceptionWhenOneBatchFails() {
        // 半个版本嵌完不能被当成索引成功；而流水线要按错误码区分解析失败与索引失败，
        // 所以异常必须是原来那个，不能是 CompletionException 包一层。
        when(embeddingProvider.embed(anyList()))
                .thenAnswer(invocation -> vectors(invocation.<List<String>>getArgument(0).size()))
                .thenThrow(new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "quota exceeded"));

        BizException raised = assertThrows(BizException.class, () -> embedder.embed(chunks(CHUNK_COUNT)));

        assertEquals(ErrorCode.UPSTREAM_MODEL_ERROR, raised.getErrorCode());
    }

    @Test
    void shouldReturnNothingInZeroKeyMode() {
        when(embeddingProvider.isConfigured()).thenReturn(false);

        assertTrue(embedder.embed(chunks(CHUNK_COUNT)).isEmpty());

        verify(embeddingProvider, never()).embed(anyList());
        verify(chunkMapper, never()).update(isNull(), any());
    }

    private List<Chunk> chunks(int count) {
        List<Chunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Chunk chunk = new Chunk();
            chunk.setChunkId("ck_" + index);
            chunk.setContent("passage " + index);
            chunk.setEmbeddingStatus(EmbeddingStatus.PENDING);
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<float[]> vectors(int count) {
        List<float[]> vectors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            vectors.add(new float[DIMENSION]);
        }
        return vectors;
    }
}
