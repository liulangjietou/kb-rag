package io.kbrag.domain.service;

import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three branches of the version numbering rule and the artifact reuse decision, which
 * together decide whether a repeated upload costs a parse, an embedding run, or nothing at all.
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentVersionPlannerTest {

    private static final String HASH_A = "hash_a";
    private static final String HASH_B = "hash_b";
    private static final String PARSE_A = "parse_a";
    private static final String PARSE_B = "parse_b";
    private static final String CHUNK_A = "chunk_a";
    private static final String CHUNK_B = "chunk_b";
    private static final String EMBEDDING_A = "text-embedding-v4";
    private static final String PARSED_OBJECT = "kb/kb_1/doc/doc_1/dv_1/parsed.json";

    private final DocumentVersionPlanner planner = new DocumentVersionPlanner();

    @Test
    void shouldNumberTheFirstVersionOfADocument() {
        DocumentVersionPlanner.VersionPlan plan = planner.plan(List.of(), fingerprint(HASH_A, PARSE_A, CHUNK_A));

        assertFalse(plan.duplicate());
        assertEquals("1.0", plan.versionNumber());
        assertEquals(DocumentVersionPlanner.ReuseLevel.NONE, plan.reuse().level());
    }

    @Test
    void shouldBumpTheMajorAndResetTheMinorWhenTheBytesChanged() {
        // The file itself is different, which is a new lineage of the document rather than a revision.
        List<DocumentVersion> history = List.of(active(1L, "1.3", HASH_A, PARSE_A, CHUNK_A));

        DocumentVersionPlanner.VersionPlan plan = planner.plan(history, fingerprint(HASH_B, PARSE_A, CHUNK_A));

        assertFalse(plan.duplicate());
        assertEquals("2.0", plan.versionNumber());
    }

    @Test
    void shouldBumpTheMinorWhenOnlyTheConfigurationChanged() {
        // Same bytes, different split configuration: the knowledge is unchanged, the way it is cut is not.
        List<DocumentVersion> history = List.of(active(1L, "2.1", HASH_A, PARSE_A, CHUNK_A));

        DocumentVersionPlanner.VersionPlan plan = planner.plan(history, fingerprint(HASH_A, PARSE_A, CHUNK_B));

        assertFalse(plan.duplicate());
        assertEquals("2.2", plan.versionNumber());
    }

    @Test
    void shouldCreateNoVersionWhenNothingAtAllChanged() {
        List<DocumentVersion> history = List.of(active(1L, "1.0", HASH_A, PARSE_A, CHUNK_A));

        DocumentVersionPlanner.VersionPlan plan = planner.plan(history, fingerprint(HASH_A, PARSE_A, CHUNK_A));

        assertTrue(plan.duplicate());
        assertEquals("dv_1", plan.duplicateOfVersionId());
        assertEquals(DocumentVersionPlanner.ReuseLevel.NONE, plan.reuse().level());
    }

    @Test
    void shouldBuildAgainWhenTheOnlyMatchingVersionFailedToBuild() {
        // Nobody is serving that version, so dismissing the re-upload as a duplicate would leave the
        // document permanently unindexed - the exact reason the verdict is made against the active version.
        DocumentVersion failed = version(1L, "1.0", HASH_A, PARSE_A, CHUNK_A,
                DocumentVersionStatus.BUILD_FAILED, false);

        DocumentVersionPlanner.VersionPlan plan =
                planner.plan(List.of(failed), fingerprint(HASH_A, PARSE_A, CHUNK_A));

        assertFalse(plan.duplicate());
        assertEquals("1.1", plan.versionNumber());
    }

    @Test
    void shouldReuseTheParseArtifactWhenOnlyTheSplitConfigurationChanged() {
        List<DocumentVersion> history = List.of(active(1L, "1.0", HASH_A, PARSE_A, CHUNK_A));

        DocumentVersionPlanner.VersionPlan plan = planner.plan(history, fingerprint(HASH_A, PARSE_A, CHUNK_B));

        assertEquals(DocumentVersionPlanner.ReuseLevel.PARSED, plan.reuse().level());
        assertTrue(plan.reuse().reusesParse());
        assertFalse(plan.reuse().reusesChunks());
        assertEquals("dv_1", plan.reuse().sourceVersionId());
        assertEquals(PARSED_OBJECT, plan.reuse().parsedObject());
    }

    @Test
    void shouldReuseTheChunkGenerationOfARevertedConfiguration() {
        // The operator changed the split configuration, then changed it back. The older version already
        // holds exactly the chunks the new one needs, and it is READY so its rows still exist.
        DocumentVersion reverted = version(1L, "1.0", HASH_A, PARSE_A, CHUNK_A,
                DocumentVersionStatus.READY, false);
        DocumentVersion current = version(2L, "1.1", HASH_A, PARSE_A, CHUNK_B,
                DocumentVersionStatus.ACTIVE, true);

        DocumentVersionPlanner.VersionPlan plan =
                planner.plan(List.of(reverted, current), fingerprint(HASH_A, PARSE_A, CHUNK_A));

        assertFalse(plan.duplicate());
        assertEquals("1.2", plan.versionNumber());
        assertEquals(DocumentVersionPlanner.ReuseLevel.CHUNKS, plan.reuse().level());
        assertTrue(plan.reuse().reusesChunks());
        assertEquals("dv_1", plan.reuse().sourceVersionId());
    }

    @Test
    void shouldNotReuseChunksOfAnArchivedVersion() {
        // The artifacts survive an archiving but the chunk rows do not, so only the parse stage is spared.
        DocumentVersion archived = version(1L, "1.0", HASH_A, PARSE_A, CHUNK_A,
                DocumentVersionStatus.ARCHIVED, false);
        DocumentVersion current = version(2L, "1.1", HASH_A, PARSE_A, CHUNK_B,
                DocumentVersionStatus.ACTIVE, true);

        DocumentVersionPlanner.VersionPlan plan =
                planner.plan(List.of(archived, current), fingerprint(HASH_A, PARSE_A, CHUNK_A));

        assertEquals(DocumentVersionPlanner.ReuseLevel.PARSED, plan.reuse().level());
    }

    @Test
    void shouldReuseNothingWhenTheBytesDiffer() {
        List<DocumentVersion> history = List.of(active(1L, "1.0", HASH_A, PARSE_A, CHUNK_A));

        DocumentVersionPlanner.VersionPlan plan = planner.plan(history, fingerprint(HASH_B, PARSE_A, CHUNK_A));

        assertEquals(DocumentVersionPlanner.ReuseLevel.NONE, plan.reuse().level());
        assertNull(plan.reuse().sourceVersionId());
    }

    @Test
    void shouldReuseNothingWhenTheParseInputsChanged() {
        // A different vision model produces different image proxies, so the stored text is not the text
        // this build would produce.
        List<DocumentVersion> history = List.of(active(1L, "1.0", HASH_A, PARSE_A, CHUNK_A));

        DocumentVersionPlanner.VersionPlan plan = planner.plan(history, fingerprint(HASH_A, PARSE_B, CHUNK_A));

        assertEquals(DocumentVersionPlanner.ReuseLevel.NONE, plan.reuse().level());
    }

    @Test
    void shouldIgnoreAnUnparsableVersionNumberWhenNumbering() {
        DocumentVersion broken = version(1L, "not-a-version", HASH_A, PARSE_A, CHUNK_A,
                DocumentVersionStatus.READY, false);
        DocumentVersion current = active(2L, "3.4", HASH_A, PARSE_A, CHUNK_A);

        assertEquals("3.5", planner.nextMinor(List.of(broken, current)));
        assertEquals("4.0", planner.nextMajor(List.of(broken, current)));
    }

    private DocumentVersionPlanner.VersionFingerprint fingerprint(String contentHash, String parse,
                                                                  String chunk) {
        return new DocumentVersionPlanner.VersionFingerprint(contentHash, parse, chunk, EMBEDDING_A);
    }

    private DocumentVersion active(long id, String number, String contentHash, String parse, String chunk) {
        return version(id, number, contentHash, parse, chunk, DocumentVersionStatus.ACTIVE, true);
    }

    private DocumentVersion version(long id, String number, String contentHash, String parse, String chunk,
                                    DocumentVersionStatus status, boolean activeFlag) {
        DocumentVersion version = new DocumentVersion();
        version.setId(id);
        version.setVersionId("dv_" + id);
        version.setDocId("doc_1");
        version.setVersion(number);
        version.setContentHash(contentHash);
        version.setParseFingerprint(parse);
        version.setChunkFingerprint(chunk);
        version.setEmbeddingVersion(EMBEDDING_A);
        version.setParsedObject(PARSED_OBJECT);
        version.setStatus(status);
        version.setActiveFlag(activeFlag ? 1 : null);
        return version;
    }
}
