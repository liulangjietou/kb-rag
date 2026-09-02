package io.kbrag.app.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.enums.SourceMappingType;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParsedChatFile;
import io.kbrag.domain.port.DocumentParserClient;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Two step import of a chat export.
 *
 * <p>Step one parses the file, stages the result and returns what the import would do. Step two executes
 * the decision the user saw. Splitting the flow is what makes the version semantics safe: the user finds
 * out that three of five conversations already exist before anything is written, instead of discovering
 * duplicated documents afterwards.
 *
 * <p><b>A conversation is a document, not a file.</b> One export may hold many conversations and becomes
 * many documents; the same conversation exported twice becomes two versions of one document, replacing its
 * content in full. That is the requirement, and it is why the source key rather than the file name carries
 * the identity.
 *
 * <p><b>Masking is on by default here.</b> A chat log is personal data whether or not the knowledge base
 * enabled masking for its documents, so the chat path forces the master switch on and only takes the per
 * category switches from the knowledge base rules.
 *
 * <p>The windows are indexed as {@code chunk_type=chat_log} carrying the session, sender and time
 * metadata, which are exactly the engine side fields the retrieval metadata filter queries.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatImportService {

    private static final String STAGED_OBJECT_TEMPLATE = "kb/%s/chat-import/%s/parsed.json";
    private static final String SOURCE_OBJECT_TEMPLATE = "kb/%s/chat-import/%s/source.%s";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";

    /**
     * Chat export extensions the parser has an adapter for.
     *
     * <p>Extending the list is the whole of what a new export format costs on this side: the adapter, the
     * line templates and the node selectors all live in the parser and in the mapping profile, and the
     * intermediate model the pipeline consumes is the same whichever of them produced it.
     */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of("csv", "xlsx", "txt", "html");

    private final DocumentMapper documentMapper;
    private final ObjectStorage objectStorage;
    private final DocumentParserClient parserClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatUploadTokenStore uploadTokenStore;
    private final SourceMappingService sourceMappingService;
    private final ChatSessionMatcher sessionMatcher;
    private final ChatSessionImporter sessionImporter;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;

    /**
     * Parses an export, stages it and reports the import plan.
     *
     * @param kbId           target knowledge base
     * @param fileName       original file name
     * @param content        raw file bytes
     * @param mappingProfile mapping profile business id or name, {@code null} selects the configured default
     * @return match preview carrying the upload token the confirmation needs
     */
    public ChatImportView preview(String kbId, String fileName, byte[] content, String mappingProfile) {
        knowledgeBaseService.require(kbId);
        String extension = extensionOf(fileName);
        ResolvedProfile profile = resolveProfile(mappingProfile, extension);

        ParsedChatFile parsed = parserClient.parseChat(fileName, extension, content,
                profile.name(), profile.profileYaml());
        if (CollectionUtils.isEmpty(parsed.sessionsOrEmpty())) {
            throw BizException.invalidParam("the export carries no recognisable conversation");
        }
        String token = bizIdGenerator.uploadToken();
        String stagedKey = String.format(STAGED_OBJECT_TEMPLATE, kbId, token);
        writeObject(stagedKey, JsonUtil.toJson(parsed), CONTENT_TYPE_JSON);
        // The original file is staged as well: a mapping profile that turns out wrong can then be replayed
        // without asking the user to upload the export again.
        objectStorage.put(String.format(SOURCE_OBJECT_TEMPLATE, kbId, token, extension),
                new ByteArrayInputStream(content), content.length, CONTENT_TYPE_OCTET_STREAM);
        uploadTokenStore.register(token, kbId, stagedKey, fileName);

        List<ChatImportView.SessionMatch> matches =
                sessionMatcher.match(parsed.sessionsOrEmpty(), existingSourceKeys(kbId));
        log.info("chat import preview built, kbId={}, fileName={}, sessions={}, profile={}",
                kbId, fileName, matches.size(), profile.name());
        return ChatImportView.builder()
                .uploadToken(token)
                .sessions(matches)
                .skipped(parsed.skippedOrEmpty())
                .build();
    }

    /**
     * Executes a staged import.
     *
     * @param kbId       target knowledge base
     * @param token      token returned by the preview
     * @param sessionIds conversations to import, empty imports every conversation of the export
     * @return document ids that were created or given a new version
     */
    public List<String> confirm(String kbId, String token, List<String> sessionIds) {
        knowledgeBaseService.require(kbId);
        ChatUploadTokenStore.StagedUpload staged = uploadTokenStore.require(kbId, token);
        ParsedChatFile parsed = readStaged(staged.objectKey());
        Set<String> selected = CollectionUtils.isEmpty(sessionIds) ? Set.of() : new HashSet<>(sessionIds);

        KbIndexConfig config = knowledgeBaseService.indexConfigOf(kbId);
        CleanRules rules = chatCleanRules(config);
        Map<String, String> existing = existingSourceKeys(kbId);

        List<String> importedDocIds = new ArrayList<>();
        for (ParsedChatFile.ChatSession session : parsed.sessionsOrEmpty()) {
            if (!selected.isEmpty() && !selected.contains(session.getSessionId())) {
                continue;
            }
            if (CollectionUtils.isEmpty(session.messagesOrEmpty())) {
                log.info("skip empty chat session, kbId={}, sessionId={}", kbId, session.getSessionId());
                continue;
            }
            importedDocIds.add(sessionImporter.importSession(kbId, session, config, rules,
                    existing.get(sessionMatcher.sourceKeyOf(session.getSessionId()))));
        }
        uploadTokenStore.consume(token);
        log.info("chat import confirmed, kbId={}, fileName={}, documents={}",
                kbId, staged.fileName(), importedDocIds.size());
        return importedDocIds;
    }

    /**
     * Cleaning rules of the chat path.
     *
     * @param config knowledge base index configuration
     * @return knowledge base rules with masking forced on when the deployment asks for it
     */
    private CleanRules chatCleanRules(KbIndexConfig config) {
        CleanRules rules = config.cleanRulesOrDefaults();
        return properties.getChatImport().isDesensitizeDefault() ? rules.withDesensitizeEnabled() : rules;
    }

    /**
     * Logical identities already present in a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return document id per source key
     */
    private Map<String, String> existingSourceKeys(String kbId) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getSourceKey));
        Map<String, String> byKey = new HashMap<>(documents.size());
        for (Document document : documents) {
            byKey.put(document.getSourceKey(), document.getDocId());
        }
        return byKey;
    }

    private ParsedChatFile readStaged(String objectKey) {
        ParsedChatFile parsed = JsonUtil.parse(
                new String(readAll(objectStorage.get(objectKey)), StandardCharsets.UTF_8),
                ParsedChatFile.class);
        if (parsed == null) {
            throw BizException.invalidParam("staged chat export is no longer available");
        }
        return parsed;
    }

    /**
     * Resolves the mapping profile an import call uses.
     *
     * <p>A named profile is resolved by business id or by name, which is what lets an import written while
     * the profiles were yml files keep addressing a built-in template by its name, and it is checked
     * against the uploaded extension: a profile describing another format would otherwise be reported by
     * the parser as an unreadable file, which blames the export for a mistake made in the form.
     *
     * @param requested mapping profile business id or name, blank selecting the default of the format
     * @param extension extension of the uploaded file
     * @return name to report to the parser and the YAML body to ship with the request
     */
    private ResolvedProfile resolveProfile(String requested, String extension) {
        SourceMappingType uploaded = SourceMappingType.from(extension);
        if (requested == null || requested.isBlank()) {
            return ResolvedProfile.of(sourceMappingService.defaultFor(uploaded,
                    properties.getChatImport().getDefaultMappingProfile()));
        }
        SourceMapping mapping = sourceMappingService.findByIdOrName(requested);
        if (mapping == null) {
            // Not an error: the parser keeps its own copies of the built-in profiles, so a name this
            // deployment has not seeded still resolves there. The format check is skipped because there is
            // no stored format to check against - the parser reports what it could not read.
            log.info("mapping profile is not stored, forwarding its name to the parser, profile={}",
                    requested);
            return new ResolvedProfile(requested, null);
        }
        if (!mapping.getSourceType().reads(uploaded)) {
            throw BizException.invalidParam("mapping profile " + mapping.getName() + " reads "
                    + mapping.getSourceType().code() + " exports, not " + extension);
        }
        return ResolvedProfile.of(mapping);
    }

    /**
     * Mapping profile of one import call.
     *
     * @param name        profile name, reported to the parser for diagnostics and for its local fallback
     * @param profileYaml full YAML body, {@code null} when the profile is only known to the parser
     */
    private record ResolvedProfile(String name, String profileYaml) {

        /**
         * Wraps a stored profile, or nothing when none was found.
         *
         * @param mapping stored profile, {@code null} letting the parser apply its own format default
         * @return profile of the call
         */
        private static ResolvedProfile of(SourceMapping mapping) {
            return mapping == null ? new ResolvedProfile(null, null)
                    : new ResolvedProfile(mapping.getName(), mapping.getProfileYaml());
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw BizException.invalidParam("file name has no extension");
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw BizException.invalidParam("unsupported chat export extension: " + extension);
        }
        return extension;
    }

    private void writeObject(String objectKey, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        objectStorage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    private byte[] readAll(InputStream stream) {
        try (InputStream input = stream) {
            return input.readAllBytes();
        } catch (Exception e) {
            log.error("read staged chat export failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "read staged chat export failed", e);
        }
    }
}
