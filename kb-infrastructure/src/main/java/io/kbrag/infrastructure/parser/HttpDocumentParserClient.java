package io.kbrag.infrastructure.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
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
import java.util.List;

/**
 * HTTP adapter of the Python parser service.
 *
 * <p>The current request id travels in the {@code X-Request-Id} header so the Java and Python logs of
 * one upload can be joined.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class HttpDocumentParserClient implements DocumentParserClient {

    private static final String PARSE_PATH = "/api/v1/parse";
    private static final String HEALTH_PATH = "/health";
    private static final String FORM_FILE = "file";
    private static final String FORM_FILE_EXT = "file_ext";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_MARKDOWN = "markdown";
    private static final String FIELD_PAGES = "pages";
    private static final String FIELD_PAGE_NO = "page_no";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_IMAGES = "images";
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
        String body;
        try {
            body = restClient.post()
                    .uri(PARSE_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(RequestIdHolder.HEADER_NAME, RequestIdHolder.get())
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("call parser service failed, errorCode={}, fileName={}", ErrorCode.PARSE_FAILED, fileName, e);
            throw new BizException(ErrorCode.PARSE_FAILED, "parser service unreachable", e);
        }
        return toParsedDocument(fileName, body);
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

    private ParsedDocument toParsedDocument(String fileName, String body) {
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
        JsonNode data = root.path(FIELD_DATA);
        List<ParsedDocument.ParsedPage> pages = new ArrayList<>();
        for (JsonNode page : data.path(FIELD_PAGES)) {
            pages.add(ParsedDocument.ParsedPage.builder()
                    .pageNo(page.path(FIELD_PAGE_NO).asInt())
                    .text(page.path(FIELD_TEXT).asText())
                    .build());
        }
        List<String> images = new ArrayList<>();
        for (JsonNode image : data.path(FIELD_IMAGES)) {
            images.add(image.asText());
        }
        return ParsedDocument.builder()
                .markdown(data.path(FIELD_MARKDOWN).asText())
                .pages(pages)
                .images(images)
                .build();
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
