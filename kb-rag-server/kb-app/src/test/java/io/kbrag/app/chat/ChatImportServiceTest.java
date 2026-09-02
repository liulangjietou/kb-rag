package io.kbrag.app.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.index.ChunkEmbedder;
import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.index.DocumentVersionActivator;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.enums.SourceMappingType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.ChatAggregationParams;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParsedChatFile;
import io.kbrag.domain.port.DocumentParserClient;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChatWindowAggregator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.DocumentCleaner;
import io.kbrag.domain.service.DocumentVersionPlanner;
import io.kbrag.domain.service.HeaderFooterDetector;
import io.kbrag.domain.service.TextDesensitizer;
import io.kbrag.domain.service.VersionFingerprintFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers what the import path decides before and around the parser call: the extension whitelist that
 * TXT and HTML joined, the mapping profile resolution and its format gate, and the window facts every
 * chat chunk has to carry for the retrieval side to recognise it as an aggregation window.
 *
 * @author owlzhangfq@gmail.com
 */
class ChatImportServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String TOKEN = "upt_test";
    private static final String SESSION_ID = "session_1";
    private static final String YAML = "txt:\n  patterns: []\n";
    private static final long BASE_TIME = 1_737_800_000_000L;

    private DocumentParserClient parserClient;
    private SourceMappingService sourceMappingService;
    private ChunkMapper chunkMapper;
    private ObjectStorage objectStorage;
    private ChatUploadTokenStore uploadTokenStore;
    private KnowledgeBaseService knowledgeBaseService;
    private KbProperties properties;
    private ChatImportService service;

    @BeforeEach
    void setUp() {
        parserClient = mock(DocumentParserClient.class);
        sourceMappingService = mock(SourceMappingService.class);
        chunkMapper = mock(ChunkMapper.class);
        objectStorage = mock(ObjectStorage.class);
        uploadTokenStore = mock(ChatUploadTokenStore.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentVersionMapper documentVersionMapper = mock(DocumentVersionMapper.class);
        ChunkIndexWriter chunkIndexWriter = mock(ChunkIndexWriter.class);
        ChunkEmbedder chunkEmbedder = mock(ChunkEmbedder.class);
        DocumentVersionActivator versionActivator = mock(DocumentVersionActivator.class);
        AnnotationInheritanceService annotationInheritanceService = mock(AnnotationInheritanceService.class);
        EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        VersionFingerprintFactory fingerprintFactory = mock(VersionFingerprintFactory.class);
        DocumentVersionPlanner versionPlanner = mock(DocumentVersionPlanner.class);
        properties = new KbProperties();

        when(bizIdGenerator.uploadToken()).thenReturn(TOKEN);
        when(bizIdGenerator.documentId()).thenReturn("doc_1");
        when(bizIdGenerator.documentVersionId()).thenReturn("dv_1");
        when(bizIdGenerator.chunkId()).thenReturn("ck_1", "ck_2", "ck_3", "ck_4", "ck_5");
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(documentVersionMapper.selectList(any())).thenReturn(List.of());
        when(versionPlanner.nextMinor(anyList())).thenReturn("1.0");
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(embeddingProvider.model()).thenReturn("none");
        when(chunkEmbedder.embed(anyList())).thenReturn(Map.of());
        when(fingerprintFactory.chunkFingerprint(any())).thenReturn("cf");
        when(fingerprintFactory.parseFingerprint(any(), anyString())).thenReturn("pf");

        // One matcher instance for both halves, as the container wires it: the source key that decides
        // which document a conversation maps to has to be the same string on the preview and on the import.
        ChatSessionMatcher sessionMatcher = new ChatSessionMatcher();
        ChatSessionImporter sessionImporter = new ChatSessionImporter(documentMapper, documentVersionMapper,
                chunkMapper, knowledgeBaseService, sessionMatcher, new ChatWindowAggregator(),
                new ChatWindowRenderer(),
                new DocumentCleaner(new HeaderFooterDetector(), new TextDesensitizer()),
                new ChunkTextHasher(), chunkIndexWriter, chunkEmbedder, versionActivator,
                annotationInheritanceService, embeddingProvider, bizIdGenerator, fingerprintFactory,
                versionPlanner);
        service = new ChatImportService(documentMapper, objectStorage, parserClient, knowledgeBaseService,
                uploadTokenStore, sourceMappingService, sessionMatcher, sessionImporter, bizIdGenerator,
                properties);
    }

    @Test
    void shouldAcceptATranscriptExport() {
        givenStoredProfile("liuhen_txt", SourceMappingType.TXT);
        givenParsedExport();

        service.preview(KB_ID, "chat.txt", "content".getBytes(StandardCharsets.UTF_8), "liuhen_txt");

        verify(parserClient).parseChat(eq("chat.txt"), eq("txt"), any(), eq("liuhen_txt"), eq(YAML));
    }

    @Test
    void shouldAcceptAnHtmlExport() {
        givenStoredProfile("liuhen_html", SourceMappingType.HTML);
        givenParsedExport();

        service.preview(KB_ID, "chat.html", "content".getBytes(StandardCharsets.UTF_8), "liuhen_html");

        verify(parserClient).parseChat(anyString(), eq("html"), any(), anyString(), anyString());
    }

    @Test
    void shouldStillRejectAnExtensionNoAdapterReads() {
        BizException thrown = assertThrows(BizException.class, () -> service.preview(
                KB_ID, "chat.json", "content".getBytes(StandardCharsets.UTF_8), null));

        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
    }

    @Test
    void shouldShipTheStoredProfileBodyToTheParser() {
        givenStoredProfile("my_export", SourceMappingType.CSV);
        givenParsedExport();

        service.preview(KB_ID, "chat.csv", "content".getBytes(StandardCharsets.UTF_8), "smp_1");

        // The parser holds no copy of a profile an operator created, so the body has to travel with the
        // request; the name travels too, for the parser's log lines and its local fallback.
        verify(parserClient).parseChat(anyString(), eq("csv"), any(), eq("my_export"), eq(YAML));
    }

    @Test
    void shouldForwardTheNameOfAProfileThisDeploymentDoesNotHold() {
        when(sourceMappingService.findByIdOrName("memotrace")).thenReturn(null);
        givenParsedExport();

        service.preview(KB_ID, "chat.csv", "content".getBytes(StandardCharsets.UTF_8), "memotrace");

        // The legacy value keeps working: the parser resolves the name against its own copies.
        verify(parserClient).parseChat(anyString(), eq("csv"), any(), eq("memotrace"), isNullString());
    }

    @Test
    void shouldRejectAProfileThatReadsAnotherFormat() {
        givenStoredProfile("liuhen_txt", SourceMappingType.TXT);

        BizException thrown = assertThrows(BizException.class, () -> service.preview(
                KB_ID, "chat.csv", "content".getBytes(StandardCharsets.UTF_8), "liuhen_txt"));

        // Blaming the export for a mistake made in the form is what this gate prevents: the parser would
        // otherwise report an unreadable file.
        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
        verify(parserClient, never())
                .parseChat(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void shouldFallBackToTheDefaultOfTheUploadedFormatWhenNoProfileIsNamed() {
        SourceMapping txtProfile = profile("liuhen_txt", SourceMappingType.TXT);
        when(sourceMappingService.defaultFor(SourceMappingType.TXT,
                properties.getChatImport().getDefaultMappingProfile())).thenReturn(txtProfile);
        givenParsedExport();

        service.preview(KB_ID, "chat.txt", "content".getBytes(StandardCharsets.UTF_8), null);

        // The deployment default is a single name while the uploaded formats are four, so a transcript
        // must not land on the tabular default.
        verify(parserClient).parseChat(anyString(), eq("txt"), any(), eq("liuhen_txt"), eq(YAML));
    }

    @Test
    void shouldLetTheParserApplyItsOwnDefaultWhenNothingIsStored() {
        when(sourceMappingService.defaultFor(any(), anyString())).thenReturn(null);
        givenParsedExport();

        service.preview(KB_ID, "chat.csv", "content".getBytes(StandardCharsets.UTF_8), null);

        verify(parserClient).parseChat(anyString(), eq("csv"), any(), isNullString(), isNullString());
    }

    @Test
    void shouldRecordTheWindowSequenceAndMessageSpanOnEveryChunk() {
        givenStagedExport(aggregation(60, 3, 0));

        service.confirm(KB_ID, TOKEN, List.of());

        List<Map<String, Object>> metadata = capturedMetadata(3);
        assertEquals(List.of(0, 1, 2), metadata.stream().map(row -> row.get("window_seq")).toList());
        assertEquals(List.of(0, 2), metadata.get(0).get("msg_span"));
        assertEquals(List.of(3, 5), metadata.get(1).get("msg_span"));
        assertEquals(List.of(6, 6), metadata.get(2).get("msg_span"));
        // The session id travels with the span: the near duplicate merging compares ranges only inside one
        // conversation, so a span without it would be uncomparable.
        assertEquals(SESSION_ID, metadata.get(0).get("session_id"));
    }

    @Test
    void shouldRecordOverlappingSpansWhenTheKnowledgeBaseConfiguresOverlap() {
        givenStagedExport(aggregation(60, 3, 1));

        service.confirm(KB_ID, TOKEN, List.of());

        List<Map<String, Object>> metadata = capturedMetadata(3);
        assertEquals(List.of(0, 2), metadata.get(0).get("msg_span"));
        assertEquals(List.of(2, 4), metadata.get(1).get("msg_span"));
        assertEquals(List.of(4, 6), metadata.get(2).get("msg_span"));
    }

    private ChatAggregationParams aggregation(int windowMinutes, int maxMessages, int overlap) {
        ChatAggregationParams params = new ChatAggregationParams();
        params.setWindowMinutes(windowMinutes);
        params.setMaxMessages(maxMessages);
        params.setWindowOverlap(overlap);
        return params;
    }

    private List<Map<String, Object>> capturedMetadata(int expected) {
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, times(expected)).insert(captor.capture());
        List<Map<String, Object>> metadata = new ArrayList<>(expected);
        for (Chunk chunk : captor.getAllValues()) {
            metadata.add(JsonUtil.parse(chunk.getMetadata(), new TypeReference<Map<String, Object>>() {
            }));
        }
        return metadata;
    }

    private void givenStoredProfile(String name, SourceMappingType sourceType) {
        when(sourceMappingService.findByIdOrName(anyString())).thenReturn(profile(name, sourceType));
    }

    private SourceMapping profile(String name, SourceMappingType sourceType) {
        SourceMapping mapping = new SourceMapping();
        mapping.setMappingId("smp_1");
        mapping.setName(name);
        mapping.setSourceType(sourceType);
        mapping.setProfileYaml(YAML);
        mapping.setIsBuiltin(SourceMapping.BUILTIN);
        return mapping;
    }

    private void givenParsedExport() {
        when(parserClient.parseChat(anyString(), anyString(), any(), any(), any()))
                .thenReturn(ParsedChatFile.builder().sessions(List.of(session(1))).skipped(Map.of()).build());
    }

    private void givenStagedExport(ChatAggregationParams aggregation) {
        ParsedChatFile parsed = ParsedChatFile.builder()
                .sessions(List.of(session(7)))
                .skipped(Map.of())
                .build();
        when(uploadTokenStore.require(KB_ID, TOKEN))
                .thenReturn(new ChatUploadTokenStore.StagedUpload(KB_ID, "key", "chat.txt", null));
        when(objectStorage.get("key")).thenReturn(
                new ByteArrayInputStream(JsonUtil.toJson(parsed).getBytes(StandardCharsets.UTF_8)));
        KbIndexConfig config = new KbIndexConfig();
        config.setChatAggregation(aggregation);
        when(knowledgeBaseService.indexConfigOf(KB_ID)).thenReturn(config);
    }

    private ParsedChatFile.ChatSession session(int messageCount) {
        List<ParsedChatFile.ChatMessageRecord> messages = new ArrayList<>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            messages.add(ParsedChatFile.ChatMessageRecord.builder()
                    .msgId("m" + i)
                    .sender("alice")
                    .sendTime(BASE_TIME + i)
                    .msgType("text")
                    .content("message " + i)
                    .build());
        }
        return ParsedChatFile.ChatSession.builder()
                .sessionId(SESSION_ID)
                .sessionName("Alice")
                .messages(messages)
                .build();
    }

    /**
     * Matcher for a form field the import decided not to send.
     *
     * @return {@code null} matcher typed as a string
     */
    private String isNullString() {
        return isNull();
    }
}
