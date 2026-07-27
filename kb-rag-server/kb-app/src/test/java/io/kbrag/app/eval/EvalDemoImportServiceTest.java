package io.kbrag.app.eval;

import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the demo evaluation case set importer of requirement section 5: matching a manifest evidence
 * to a real {@code doc_id} by file name and content hash, and skipping (with a reason) whatever cannot
 * be matched, without aborting the rest of the import.
 *
 * <p>A single document/version pair is reused as the stubbed lookup result across every test: the
 * content hash comparison inside {@code resolveDocId} is what actually decides a match, so a manifest
 * evidence whose hash disagrees is correctly rejected even though the mocked mapper does not filter by
 * the query's file name - exactly the discriminating check the production code performs.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalDemoImportServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String MATCHING_HASH = "hash-one";

    @TempDir
    Path demoDir;

    private EvalDatasetService evalDatasetService;
    private EvalDatasetMapper evalDatasetMapper;
    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private KnowledgeBaseService knowledgeBaseService;
    private KbProperties properties;
    private EvalDemoImportService service;

    @BeforeEach
    void setUp() {
        evalDatasetService = mock(EvalDatasetService.class);
        evalDatasetMapper = mock(EvalDatasetMapper.class);
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        properties = new KbProperties();
        properties.getDemo().setDataDir(demoDir.toString());

        service = new EvalDemoImportService(evalDatasetService, evalDatasetMapper, documentMapper,
                documentVersionMapper, knowledgeBaseService, properties);

        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId("evds_demo");
        dataset.setKbId(KB_ID);
        when(evalDatasetMapper.selectOne(any())).thenReturn(null);
        when(evalDatasetService.create(any(), any(), any())).thenReturn(dataset);
        when(evalDatasetService.createCase(any(), any())).thenReturn(new EvalCase());

        Document document = new Document();
        document.setDocId("doc_one");
        document.setKbId(KB_ID);
        document.setFileName("one.md");
        document.setCurrentVersionId("dv_one");
        when(documentMapper.selectList(any())).thenReturn(List.of(document));

        DocumentVersion version = new DocumentVersion();
        version.setVersionId("dv_one");
        version.setContentHash(MATCHING_HASH);
        when(documentVersionMapper.selectOne(any())).thenReturn(version);
    }

    @Test
    void shouldImportMatchingCasesAndSkipTheOthersWithAReason() throws Exception {
        writeManifest("""
                {
                  "cases": [
                    {"case_id":"c0","query":"q0","anchor_type":"span",
                     "evidence":[{"doc_ref":{"file_name":"docs/one.md","content_hash_sha256":"hash-one"},"span":"s0"}]},
                    {"case_id":"c1","query":"q1","anchor_type":"span",
                     "evidence":[{"doc_ref":{"file_name":"docs/two.md","content_hash_sha256":"wrong-hash"},"span":"s1"}]},
                    {"case_id":"c2","query":"q2","anchor_type":"span",
                     "evidence":[{"doc_ref":{"file_name":"missing.md","content_hash_sha256":"whatever"},"span":"s2"}]}
                  ]
                }
                """);

        EvalDemoImportService.ImportResult result = service.importDemo(KB_ID);

        assertEquals("evds_demo", result.datasetId());
        assertFalse(result.alreadyExisted());
        // Only the case whose file name and content hash both resolve to the stubbed document is
        // imported; a content hash mismatch and a document that was never found both count as skipped.
        assertEquals(1, result.importedCaseCount());
        assertEquals(2, result.skipped().size());
        assertEquals(1, result.skipped().get(0).caseIndex());
        assertEquals(2, result.skipped().get(1).caseIndex());
    }

    @Test
    void shouldMatchTheFileNameByItsBaseNameIgnoringTheManifestsDirectoryPrefix() throws Exception {
        // demo/manifest.json paths are relative ("docs/one.md") while the demo document importer only
        // ever stores the leaf name it read from disk ("one.md") - the match has to survive that.
        writeManifest("""
                {"cases":[{"case_id":"c0","query":"q0","anchor_type":"span",
                  "evidence":[{"doc_ref":{"file_name":"docs/nested/one.md","content_hash_sha256":"hash-one"},"span":"s0"}]}]}
                """);

        EvalDemoImportService.ImportResult result = service.importDemo(KB_ID);

        assertEquals(1, result.importedCaseCount());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void shouldBeIdempotentWhenTheDemoDataSetAlreadyExists() {
        EvalDataset existing = new EvalDataset();
        existing.setDatasetId("evds_existing");
        existing.setKbId(KB_ID);
        when(evalDatasetMapper.selectOne(any())).thenReturn(existing);

        EvalDemoImportService.ImportResult result = service.importDemo(KB_ID);

        assertEquals("evds_existing", result.datasetId());
        assertTrue(result.alreadyExisted());
        assertEquals(0, result.importedCaseCount());
        assertTrue(result.skipped().isEmpty());
    }

    private void writeManifest(String json) throws Exception {
        Files.writeString(demoDir.resolve("eval-cases.json"), json);
    }
}
