package io.kbrag.app.system;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the idempotency of the one click import and the manifest driven file selection.
 *
 * <p>The manifest is read from a temporary directory, so the test exercises the real file handling without
 * depending on the material the deployment repository ships.
 *
 * @author owlzhangfq@gmail.com
 */
class DemoImportServiceTest {

    private static final String DEMO_NAME = "Demo 知识库";
    private static final String DEMO_KB_ID = "kb_demo";

    @TempDir
    Path demoDir;

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private DocumentMapper documentMapper;
    private KnowledgeBaseService knowledgeBaseService;
    private DocumentService documentService;
    private KbProperties properties;
    private DemoImportService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(DocumentMapper.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        documentService = mock(DocumentService.class);
        properties = new KbProperties();
        properties.getDemo().setDataDir(demoDir.toString());
        properties.getDemo().setKnowledgeBaseName(DEMO_NAME);
        service = new DemoImportService(knowledgeBaseMapper, documentMapper, knowledgeBaseService,
                documentService, properties);
    }

    @Test
    void shouldImportEveryFileTheManifestLists() throws IOException {
        givenManifest("docs/one.md", "docs/two.md");
        givenNoDemoKnowledgeBase();
        when(knowledgeBaseService.create(eq(DEMO_NAME), anyString())).thenReturn(demoKnowledgeBase());

        assertEquals(DEMO_KB_ID, service.importDemo());

        ArgumentCaptor<String> fileNames = ArgumentCaptor.forClass(String.class);
        verify(documentService, times(2)).upload(eq(DEMO_KB_ID), fileNames.capture(), any());
        // The manifest may point into subdirectories; the document keeps the plain file name for display.
        assertEquals(java.util.List.of("one.md", "two.md"), fileNames.getAllValues());
    }

    @Test
    void shouldBeIdempotentWhenTheDemoKnowledgeBaseAlreadyExists() throws IOException {
        givenManifest("docs/one.md");
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(demoKnowledgeBase());

        assertEquals(DEMO_KB_ID, service.importDemo());

        // Clicking twice returns the same identifier and imports nothing: the existence of the knowledge base
        // is the whole state, so no marker row can disagree with what the operator sees in the list.
        verify(knowledgeBaseService, never()).create(anyString(), anyString());
        verify(documentService, never()).upload(anyString(), anyString(), any());
    }

    @Test
    void shouldStayIdempotentAfterTheMaterialIsUnmounted() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(demoKnowledgeBase());

        // The idempotency check comes before the manifest read, so an imported demo keeps working.
        assertEquals(DEMO_KB_ID, service.importDemo());
        verify(knowledgeBaseService, never()).create(anyString(), anyString());
    }

    @Test
    void shouldFailFastWithoutAManifest() {
        givenNoDemoKnowledgeBase();

        BizException failure = assertThrows(BizException.class, () -> service.importDemo());

        // A missing directory is a deployment detail the user can fix, so the message names the path and the
        // configuration key instead of creating an empty knowledge base that looks like a broken demo.
        assertTrue(failure.getMessage().contains(demoDir.toString()));
        assertTrue(failure.getMessage().contains("DEMO_DATA_DIR"));
        verify(knowledgeBaseService, never()).create(anyString(), anyString());
    }

    @Test
    void shouldFailFastWhenTheManifestListsNoReadableFile() throws IOException {
        Files.writeString(demoDir.resolve(DemoImportService.MANIFEST_FILE_NAME),
                "{\"documents\":[{\"file_name\":\"docs/missing.md\"}]}");
        givenNoDemoKnowledgeBase();

        assertThrows(BizException.class, () -> service.importDemo());
    }

    @Test
    void shouldCarryOnWhenOneSampleCannotBeRead() throws IOException {
        givenManifest("docs/one.md", "docs/two.md");
        givenNoDemoKnowledgeBase();
        when(knowledgeBaseService.create(eq(DEMO_NAME), anyString())).thenReturn(demoKnowledgeBase());
        org.mockito.Mockito.doThrow(new IllegalStateException("parser rejected the file"))
                .when(documentService).upload(eq(DEMO_KB_ID), eq("one.md"), any());

        // A partially populated knowledge base is still a working demonstration.
        assertEquals(DEMO_KB_ID, service.importDemo());
        verify(documentService).upload(eq(DEMO_KB_ID), eq("two.md"), any());
    }

    @Test
    void shouldRejectAManifestEntryPointingOutsideTheDemoDirectory() throws IOException {
        Files.writeString(demoDir.resolve(DemoImportService.MANIFEST_FILE_NAME),
                "{\"documents\":[{\"file_name\":\"../../etc/hosts\"}]}");
        givenNoDemoKnowledgeBase();

        // The manifest is deployment data, so a traversal entry must not read an arbitrary host file.
        assertFalse(service.available());
        assertThrows(BizException.class, () -> service.importDemo());
    }

    @Test
    void shouldAcceptTheFilesSpellingOfTheManifest() throws IOException {
        Files.createDirectories(demoDir.resolve("docs"));
        Files.writeString(demoDir.resolve("docs/one.md"), "sample body");
        Files.writeString(demoDir.resolve(DemoImportService.MANIFEST_FILE_NAME),
                "{\"files\":[{\"file_name\":\"docs/one.md\"}]}");

        assertTrue(service.available());
    }

    @Test
    void shouldTolerateAnUnreadableManifest() throws IOException {
        Files.writeString(demoDir.resolve(DemoImportService.MANIFEST_FILE_NAME), "{ not json");

        assertFalse(service.available());
    }

    @Test
    void shouldReportTheStateBeforeAndAfterTheImport() throws IOException {
        givenManifest("docs/one.md");
        givenNoDemoKnowledgeBase();

        DemoImportService.DemoStatus before = service.status();
        assertTrue(before.available());
        assertFalse(before.imported());
        assertNull(before.kbId());
        assertEquals(0L, before.docCount());

        when(knowledgeBaseMapper.selectOne(any())).thenReturn(demoKnowledgeBase());
        when(documentMapper.selectCount(any())).thenReturn(4L);

        DemoImportService.DemoStatus after = service.status();
        assertTrue(after.imported());
        assertEquals(DEMO_KB_ID, after.kbId());
        assertEquals(4L, after.docCount());
    }

    @Test
    void shouldReportUnavailableWithoutTheMaterial() {
        givenNoDemoKnowledgeBase();

        DemoImportService.DemoStatus status = service.status();

        // The console greys the button out rather than letting the user trigger a call that cannot work.
        assertFalse(status.available());
        assertFalse(status.imported());
    }

    private void givenManifest(String... relativePaths) throws IOException {
        StringBuilder entries = new StringBuilder();
        for (String path : relativePaths) {
            Path file = demoDir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "sample body of " + path);
            if (entries.length() > 0) {
                entries.append(',');
            }
            entries.append("{\"file_name\":\"").append(path).append("\",\"title\":\"t\"}");
        }
        Files.writeString(demoDir.resolve(DemoImportService.MANIFEST_FILE_NAME),
                "{\"documents\":[" + entries + "]}");
    }

    private void givenNoDemoKnowledgeBase() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);
    }

    private KnowledgeBase demoKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(DEMO_KB_ID);
        knowledgeBase.setName(DEMO_NAME);
        return knowledgeBase;
    }
}
