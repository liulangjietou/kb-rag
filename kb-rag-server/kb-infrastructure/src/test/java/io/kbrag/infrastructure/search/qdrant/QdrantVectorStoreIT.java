package io.kbrag.infrastructure.search.qdrant;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.MetadataFilter;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test of {@link QdrantVectorStore} against a real Qdrant.
 *
 * <p>Disabled unless {@code QDRANT_IT=true} is set, so an ordinary build and CI never depend on an
 * external service. Point it at a throwaway instance - every test drops the collections it creates:
 *
 * <pre>
 *   docker run -d --name qdrant-it -p 6333:6333 qdrant/qdrant:v1.18.3
 *   QDRANT_IT=true mvn -pl kb-infrastructure test -Dtest=QdrantVectorStoreIT
 * </pre>
 *
 * <p>What it is here to catch: the adapter speaks REST by hand, so a wrong endpoint, a renamed request
 * field or a changed score domain would compile and pass every mocked test while failing in production.
 *
 * @author owlzhangfq@gmail.com
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_IT", matches = "true")
class QdrantVectorStoreIT {

    private static final int DIMENSION = 4;
    private static final String COLLECTION = "it_kb_probe_v1";
    private static final String SNAPSHOT = "it_kb_probe_s1";
    private static final String ALIAS = "it_kb_probe";
    private static final String KB_ID = "kb_it";
    private static final String VERSION_A = "dv_a";
    private static final String VERSION_B = "dv_b";

    /** Score of an exact match: cosine 1.0 mapped through (cos+1)/2. */
    private static final double EXACT_MATCH_SCORE = 1.0d;
    private static final double SCORE_TOLERANCE = 1e-6d;

    private QdrantVectorStore store;

    @BeforeEach
    void setUp() {
        String uri = System.getenv().getOrDefault("QDRANT_IT_URI", "http://127.0.0.1:6333");
        RestClient client = RestClient.builder().baseUrl(uri).build();
        store = new QdrantVectorStore(client, new KbProperties());
        dropAll();
    }

    @AfterEach
    void tearDown() {
        dropAll();
    }

    private void dropAll() {
        store.dropIndex(COLLECTION);
        store.dropIndex(SNAPSHOT);
    }

    @Test
    @DisplayName("健康探针连得上真实实例")
    void healthCheckReachesTheEngine() {
        assertTrue(store.healthCheck().isUp());
    }

    @Test
    @DisplayName("ensureIndex 幂等，dropIndex 后 indexExists 为假")
    void ensureIndexIsIdempotent() {
        assertFalse(store.indexExists(COLLECTION));
        store.ensureIndex(spec());
        assertTrue(store.indexExists(COLLECTION));
        // 第二次不应抛错：知识库每次重建都会重新走一遍 ensureIndex
        store.ensureIndex(spec());
        assertTrue(store.indexExists(COLLECTION));
        store.dropIndex(COLLECTION);
        assertFalse(store.indexExists(COLLECTION));
        // 不存在时再删一次同样不能抛错
        store.dropIndex(COLLECTION);
    }

    @Test
    @DisplayName("写入后可经别名检索，完全匹配的分数落在归一化后的 1.0")
    void upsertThenSearchThroughAlias() {
        store.ensureIndex(spec());
        store.upsert(ALIAS, List.of(record("ck_1", VERSION_A, true, new float[]{1, 0, 0, 0}),
                record("ck_2", VERSION_A, true, new float[]{0, 1, 0, 0})));

        List<ScoredChunk> hits = store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, null)));

        assertEquals(2, hits.size());
        assertEquals("ck_1", hits.get(0).getChunkId());
        assertEquals(EXACT_MATCH_SCORE, hits.get(0).getScore(), SCORE_TOLERANCE);
        // 正交向量 cosine=0，归一化后应为 0.5，证明分数域确实是原始 cosine 而非引擎自有刻度
        assertEquals(0.5d, hits.get(1).getScore(), SCORE_TOLERANCE);
    }

    @Test
    @DisplayName("enabled 与文档版本过滤在引擎侧生效")
    void mandatoryFiltersAreAppliedEngineSide() {
        store.ensureIndex(spec());
        store.upsert(ALIAS, List.of(
                record("ck_on", VERSION_A, true, new float[]{1, 0, 0, 0}),
                record("ck_off", VERSION_A, false, new float[]{1, 0, 0, 0}),
                record("ck_other", VERSION_B, true, new float[]{1, 0, 0, 0})));

        List<ScoredChunk> enabledOnly =
                store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, null)));
        assertEquals(List.of("ck_on", "ck_other"), enabledOnly.stream().map(ScoredChunk::getChunkId).sorted().toList());

        List<ScoredChunk> versionScoped =
                store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, List.of(VERSION_A), null)));
        assertEquals(List.of("ck_on"), versionScoped.stream().map(ScoredChunk::getChunkId).toList());
    }

    @Test
    @DisplayName("metadata 过滤：标签任一命中、发送人、时间区间")
    void metadataFilterNarrowsTheCandidates() {
        store.ensureIndex(spec());
        store.upsert(ALIAS, List.of(
                chatRecord("ck_a", List.of("t1", "t2"), "alice", 100L),
                chatRecord("ck_b", List.of("t3"), "bob", 200L),
                chatRecord("ck_c", List.of(), "carol", 300L)));

        assertEquals(List.of("ck_a", "ck_b"), searchIds(MetadataFilter.builder().tagIds(List.of("t2", "t3")).build()));
        assertEquals(List.of("ck_a"), searchIds(MetadataFilter.builder().sender("alice").build()));
        assertEquals(List.of("ck_a", "ck_b"),
                searchIds(MetadataFilter.builder().msgTimeFrom(100L).msgTimeTo(200L).build()));
    }

    @Test
    @DisplayName("updateEnabled 原地翻转开关且不擦除向量")
    void updateEnabledKeepsTheVector() {
        store.ensureIndex(spec());
        store.upsert(ALIAS, List.of(record("ck_1", VERSION_A, true, new float[]{1, 0, 0, 0})));

        store.updateEnabled(ALIAS, List.of("ck_1"), false);
        assertTrue(store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, null))).isEmpty(),
                "关闭后不应再被 enabledOnly 检索命中");

        store.updateEnabled(ALIAS, List.of("ck_1"), true);
        List<ScoredChunk> hits = store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, null)));
        assertEquals(1, hits.size());
        // 向量若被擦除，分数就不会仍是完全匹配 —— set payload 只改字段、不触碰向量
        assertEquals(EXACT_MATCH_SCORE, hits.get(0).getScore(), SCORE_TOLERANCE);
    }

    @Test
    @DisplayName("快照是独立副本：源库继续写入不影响已冻结的快照")
    void snapshotIsAnIndependentCopy() {
        store.ensureIndex(spec());
        store.upsert(ALIAS, List.of(record("ck_1", VERSION_A, true, new float[]{1, 0, 0, 0})));

        store.snapshotIndex(COLLECTION, SNAPSHOT);
        assertTrue(store.indexExists(SNAPSHOT));
        assertEquals(1, store.search(SNAPSHOT, query(new float[]{1, 0, 0, 0}, filter(true, null, null))).size());

        // 快照冻结后源库继续变化，快照必须保持原样，否则已发布版本的语料就会漂移
        store.upsert(ALIAS, List.of(record("ck_2", VERSION_A, true, new float[]{0, 1, 0, 0})));
        assertEquals(2, store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, null))).size());
        assertEquals(1, store.search(SNAPSHOT, query(new float[]{1, 0, 0, 0}, filter(true, null, null))).size());
    }

    @Test
    @DisplayName("delete 按 chunk id 移除，其余不受影响")
    void deleteRemovesOnlyTheGivenChunks() {
        store.ensureIndex(spec());
        store.upsert(ALIAS, List.of(record("ck_1", VERSION_A, true, new float[]{1, 0, 0, 0}),
                record("ck_2", VERSION_A, true, new float[]{0, 1, 0, 0})));

        store.delete(ALIAS, List.of("ck_1"));

        assertEquals(List.of("ck_2"),
                store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, null)))
                        .stream().map(ScoredChunk::getChunkId).toList());
    }

    private List<String> searchIds(MetadataFilter metadata) {
        return store.search(ALIAS, query(new float[]{1, 0, 0, 0}, filter(true, null, metadata)))
                .stream().map(ScoredChunk::getChunkId).sorted().toList();
    }

    private IndexSpec spec() {
        return IndexSpec.builder()
                .physicalIndexName(COLLECTION)
                .aliasName(ALIAS)
                .dimension(DIMENSION)
                .build();
    }

    private VectorQuery query(float[] vector, RetrievalFilter filter) {
        return VectorQuery.builder().queryVector(vector).topK(10).filter(filter).build();
    }

    private RetrievalFilter filter(boolean enabledOnly, List<String> versions, MetadataFilter metadata) {
        return RetrievalFilter.builder()
                .kbId(KB_ID)
                .enabledOnly(enabledOnly)
                .documentVersionIds(versions)
                .metadataFilter(metadata)
                .build();
    }

    private ChunkRecord record(String chunkId, String versionId, boolean enabled, float[] vector) {
        return ChunkRecord.builder()
                .chunkId(chunkId)
                .kbId(KB_ID)
                .docId("doc_1")
                .documentVersionId(versionId)
                .chunkType("text")
                .enabled(enabled)
                .content("content of " + chunkId)
                .vector(vector)
                .build();
    }

    private ChunkRecord chatRecord(String chunkId, List<String> tagIds, String sender, long msgTime) {
        return ChunkRecord.builder()
                .chunkId(chunkId)
                .kbId(KB_ID)
                .docId("doc_1")
                .documentVersionId(VERSION_A)
                .chunkType("chat")
                .enabled(true)
                .tagIds(tagIds)
                .sessionId("s_1")
                .sender(sender)
                .msgTime(msgTime)
                .content("content of " + chunkId)
                .vector(new float[]{1, 0, 0, 0})
                .build();
    }
}
