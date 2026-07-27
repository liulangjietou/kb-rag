package io.kbrag.infrastructure.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ParsedChatFile;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.port.DocumentParserClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP adapter of the Python parser service.
 *
 * <p>The current request id travels in the {@code X-Request-Id} header so the Java and Python logs of
 * one upload can be joined.
 *
 * <p>Every field the M3 parser added is read defensively: an older parser that answers without
 * {@code images}, {@code warnings} or {@code scanned} yields the same result it did before, so the two
 * services can be deployed in either order.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class HttpDocumentParserClient implements DocumentParserClient {

    private static final String PARSE_PATH = "/api/v1/parse";
    private static final String PARSE_CHAT_PATH = "/api/v1/parse/chat";
    private static final String HEALTH_PATH = "/health";
    private static final String FORM_FILE = "file";
    private static final String FORM_FILE_EXT = "file_ext";
    private static final String FORM_MAPPING_PROFILE = "mapping_profile";

    /**
     * Full YAML body of the mapping profile, which the parser prefers over its own copy of the named file.
     *
     * <p>Sent as a form field rather than as a second file part: it is a text value of the request, and a
     * file part would make the parser treat the profile as an upload it has to validate like the export.
     */
    private static final String FORM_PROFILE_YAML = "profile_yaml";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_MARKDOWN = "markdown";
    private static final String FIELD_PAGES = "pages";
    private static final String FIELD_PAGE_NO = "page_no";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_SCANNED = "scanned";
    private static final String FIELD_OCR_SOURCE = "ocr_source";
    private static final String FIELD_IMAGES = "images";
    private static final String FIELD_IMAGE_ID = "image_id";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_MEDIA_TYPE = "media_type";
    private static final String FIELD_CONTENT_BASE64 = "content_base64";
    private static final String FIELD_WARNINGS = "warnings";
    private static final String FIELD_SESSIONS = "sessions";
    private static final String FIELD_SESSION_ID = "session_id";
    private static final String FIELD_SESSION_NAME = "session_name";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_MSG_ID = "msg_id";
    private static final String FIELD_SENDER = "sender";
    private static final String FIELD_IS_SELF = "is_self";
    private static final String FIELD_SEND_TIME = "send_time";
    private static final String FIELD_MSG_TYPE = "msg_type";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_SKIPPED = "skipped";
    private static final String FIELD_STATUS = "status";
    private static final String STATUS_UP = "UP";

    private final RestClient restClient;

    public HttpDocumentParserClient(KbProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getParser().getTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getParser().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ParsedDocument parse(String fileName, String fileExt, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(FORM_FILE, asResource(fileName, content));
        form.add(FORM_FILE_EXT, fileExt);
        return toParsedDocument(fileName, post(PARSE_PATH, form, fileName));
    }

    @Override
    public ParsedChatFile parseChat(String fileName, String fileExt, byte[] content, String mappingProfile,
                                    String profileYaml) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(FORM_FILE, asResource(fileName, content));
        form.add(FORM_FILE_EXT, fileExt);
        if (mappingProfile != null && !mappingProfile.isBlank()) {
            form.add(FORM_MAPPING_PROFILE, mappingProfile);
        }
        if (profileYaml != null && !profileYaml.isBlank()) {
            form.add(FORM_PROFILE_YAML, profileYaml);
        }
        return toParsedChatFile(fileName, post(PARSE_CHAT_PATH, form, fileName));
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            String body = restClient.get().uri(HEALTH_PATH).retrieve().body(String.class);
            JsonNode node = JsonUtil.parse(body, JsonNode.class);
            boolean up = node != null && STATUS_UP.equalsIgnoreCase(node.path(FIELD_STATUS).asText());
            return up ? HealthStatus.up("parser reachable") : HealthStatus.down("parser reported down");
        } catch (Exception e) {
            log.error("parser health check failed, errorCode={}", ErrorCode.PARSE_FAILED, e);
            return HealthStatus.down("parser unreachable");
        }
    }

    private String post(String path, MultiValueMap<String, Object> form, String fileName) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(RequestIdHolder.HEADER_NAME, RequestIdHolder.get())
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("call parser service failed, errorCode={}, path={}, fileName={}",
                    ErrorCode.PARSE_FAILED, path, fileName, e);
            throw new BizException(ErrorCode.PARSE_FAILED, "parser service unreachable", e);
        }
    }

    /**
     * Unwraps the parser envelope.
     *
     * @param fileName file the call was about, used in the log line
     * @param body     raw response body
     * @return the {@code data} node
     */
    private JsonNode requireData(String fileName, String body) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        if (root == null) {
            log.error("parser returned empty body, errorCode={}, fileName={}", ErrorCode.PARSE_FAILED, fileName);
            throw new BizException(ErrorCode.PARSE_FAILED, "parser returned an empty body");
        }
        String code = root.path(FIELD_CODE).asText();
        if (!ErrorCode.OK.name().equals(code)) {
            String message = root.path(FIELD_MESSAGE).asText();
            log.error("parser rejected document, errorCode={}, fileName={}, parserCode={}",
                    ErrorCode.PARSE_FAILED, fileName, code);
            throw new BizException(ErrorCode.PARSE_FAILED, message);
        }
        return root.path(FIELD_DATA);
    }

    private ParsedDocument toParsedDocument(String fileName, String body) {
        JsonNode data = requireData(fileName, body);
        List<ParsedDocument.ParsedPage> pages = new ArrayList<>();
        for (JsonNode page : data.path(FIELD_PAGES)) {
            pages.add(ParsedDocument.ParsedPage.builder()
                    .pageNo(page.path(FIELD_PAGE_NO).asInt())
                    .text(page.path(FIELD_TEXT).asText())
                    .scanned(page.path(FIELD_SCANNED).asBoolean(false))
                    .ocrSource(page.path(FIELD_OCR_SOURCE).asText(null))
                    .build());
        }
        List<ParsedDocument.ParsedImage> images = new ArrayList<>();
        for (JsonNode image : data.path(FIELD_IMAGES)) {
            ParsedDocument.ParsedImage parsed = toParsedImage(fileName, image);
            if (parsed != null) {
                images.add(parsed);
            }
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode warning : data.path(FIELD_WARNINGS)) {
            warnings.add(warning.asText());
        }
        return ParsedDocument.builder()
                .markdown(data.path(FIELD_MARKDOWN).asText())
                .pages(pages)
                .images(images)
                .warnings(warnings)
                .build();
    }

    /**
     * Decodes one image entry.
     *
     * <p>An entry whose payload cannot be decoded is dropped with an info log rather than failing the
     * document: the text of the file is the valuable part, and one unreadable image must not cost it.
     *
     * @param fileName file the call was about
     * @param image    image node
     * @return decoded image, {@code null} when the entry is unusable
     */
    private ParsedDocument.ParsedImage toParsedImage(String fileName, JsonNode image) {
        String imageId = image.path(FIELD_IMAGE_ID).asText(null);
        String base64 = image.path(FIELD_CONTENT_BASE64).asText(null);
        if (imageId == null || imageId.isBlank() || base64 == null || base64.isBlank()) {
            log.info("skip parser image without an id or a payload, fileName={}", fileName);
            return null;
        }
        try {
            return ParsedDocument.ParsedImage.builder()
                    .imageId(imageId)
                    .pageNo(image.hasNonNull(FIELD_PAGE_NO) ? image.path(FIELD_PAGE_NO).asInt() : null)
                    .kind(image.path(FIELD_KIND).asText(null))
                    .mediaType(image.path(FIELD_MEDIA_TYPE).asText(null))
                    .content(Base64.getDecoder().decode(base64))
                    .build();
        } catch (IllegalArgumentException e) {
            log.info("skip parser image with an undecodable payload, fileName={}, imageId={}",
                    fileName, imageId);
            return null;
        }
    }

    private ParsedChatFile toParsedChatFile(String fileName, String body) {
        JsonNode data = requireData(fileName, body);
        List<ParsedChatFile.ChatSession> sessions = new ArrayList<>();
        for (JsonNode session : data.path(FIELD_SESSIONS)) {
            List<ParsedChatFile.ChatMessageRecord> messages = new ArrayList<>();
            for (JsonNode message : session.path(FIELD_MESSAGES)) {
                messages.add(ParsedChatFile.ChatMessageRecord.builder()
                        .msgId(message.path(FIELD_MSG_ID).asText(null))
                        .sender(message.path(FIELD_SENDER).asText(null))
                        .self(message.path(FIELD_IS_SELF).asBoolean(false))
                        .sendTime(message.hasNonNull(FIELD_SEND_TIME)
                                ? message.path(FIELD_SEND_TIME).asLong() : null)
                        .msgType(message.path(FIELD_MSG_TYPE).asText(null))
                        .content(message.path(FIELD_CONTENT).asText(""))
                        .build());
            }
            sessions.add(ParsedChatFile.ChatSession.builder()
                    .sessionId(session.path(FIELD_SESSION_ID).asText(null))
                    .sessionName(session.path(FIELD_SESSION_NAME).asText(null))
                    .messages(messages)
                    .build());
        }
        Map<String, Integer> skipped = new LinkedHashMap<>();
        JsonNode skippedNode = data.path(FIELD_SKIPPED);
        skippedNode.fieldNames().forEachRemaining(name -> skipped.put(name, skippedNode.path(name).asInt()));
        log.info("chat export parsed, fileName={}, sessions={}, skipped={}",
                fileName, sessions.size(), skipped);
        return ParsedChatFile.builder().sessions(sessions).skipped(skipped).build();
    }

    private Resource asResource(String fileName, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }
}
