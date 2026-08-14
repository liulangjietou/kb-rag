package io.kbrag.infrastructure.connector;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.model.ExtSourceConfig;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confluence Cloud REST API v2 connector, the M23 implementation of the external source SPI.
 *
 * <p>The existing source columns deliberately keep their wire names for compatibility, while this
 * connector gives them connector-specific meaning: endpoint is the Confluence site URL, bucket is
 * the space key, access key is the Atlassian account email and secret key is its API token. Region
 * and prefix are unused. No schema or second intake path is introduced.
 *
 * <p>Listing asks only for page identity and version. The stable object key is
 * {@code confluence/{pageId}.html}; {@code pageId:version} is its ETag. Consequently an unchanged
 * page costs no body request, while a changed page is materialised as HTML and then enters the same
 * upload, governance, versioning and indexing pipeline as every other document.
 *
 * <p>Confluence v2 pagination is cursor based. Both {@code _links.next} and the HTTP Link header are
 * accepted, but a next URL must stay on the configured origin before the Authorization header is
 * sent. This is the credential boundary: neither redirects nor a hostile absolute next link can
 * walk the API token to another host.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ConfluenceCloudConnector implements ExternalConnector {

    static final String TYPE = "confluence";

    private static final String API_PREFIX = "/wiki/api/v2";
    private static final String PAGE_KEY_PREFIX = "confluence/";
    private static final String PAGE_KEY_SUFFIX = ".html";
    private static final String STATUS_CURRENT = "current";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_LINK = "Link";
    private static final String USER_AGENT = "kb-rag/1.0 (+confluence-connector)";
    private static final int PAGE_SIZE = 100;
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final long MAX_METADATA_BYTES = 5L * 1024 * 1024;
    private static final long JSON_ENVELOPE_ALLOWANCE_BYTES = 1024L * 1024;
    private static final Pattern PAGE_KEY_PATTERN =
            Pattern.compile("^" + PAGE_KEY_PREFIX + "([0-9]+)\\.html$");
    private static final Pattern NEXT_LINK_PATTERN =
            Pattern.compile("<([^>]+)>\\s*;\\s*rel=\\\"?next\\\"?", Pattern.CASE_INSENSITIVE);

    private final Function<String, URI> endpointValidator;
    private final Function<ExtSourceConfig, HttpClient> clientFactory;

    /** Production constructor: HTTPS-only endpoint validation and a per-source timeout budget. */
    public ConfluenceCloudConnector() {
        this(ConfluenceCloudConnector::validateEndpoint, config -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** Test seam for a loopback HTTP server; production wiring always uses the public constructor. */
    ConfluenceCloudConnector(Function<String, URI> endpointValidator,
                             Function<ExtSourceConfig, HttpClient> clientFactory) {
        this.endpointValidator = endpointValidator;
        this.clientFactory = clientFactory;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validateConfig(ExtSourceConfig config) {
        requireConfig(config);
    }

    @Override
    public List<RemoteObject> listObjects(ExtSourceConfig config) {
        URI origin = requireConfig(config);
        HttpClient client = clientFactory.apply(config);
        String spaceId = resolveSpaceId(config, client, origin);
        long targetSize = Math.max(1L, (long) config.maxObjects() + 1L);
        List<RemoteObject> objects = new ArrayList<>((int) Math.min(targetSize, PAGE_SIZE));
        Set<String> seenPageIds = new HashSet<>();
        Set<URI> visitedPages = new HashSet<>();
        URI next = pagesUri(origin, spaceId, Math.min(PAGE_SIZE, targetSize));

        while (next != null && objects.size() < targetSize) {
            ensureSameOrigin(origin, next);
            if (!visitedPages.add(next)) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "Confluence 分页游标重复，扫描已中止");
            }
            ApiResponse response = get(config, client, next, MAX_METADATA_BYTES);
            PageList page = parse(response.body(), PageList.class);
            if (page == null || page.results() == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "Confluence 页面列表响应不完整");
            }
            if (CollectionUtils.isNotEmpty(page.results())) {
                for (PageSummary summary : page.results()) {
                    requireValidPageSummary(summary);
                    if (!seenPageIds.add(summary.id())) {
                        continue;
                    }
                    objects.add(new RemoteObject(
                            objectKeyOf(summary.id()),
                            summary.title(),
                            etagOf(summary),
                            -1L,
                            modifiedAtOf(summary)));
                    if (objects.size() >= targetSize) {
                        break;
                    }
                }
            }
            next = nextUri(origin, next, response, page.links());
        }
        return objects;
    }

    @Override
    public byte[] fetchObject(ExtSourceConfig config, String objectKey) {
        URI origin = requireConfig(config);
        String pageId = pageIdOf(objectKey);
        URI uri = apiUri(origin, "/pages/" + pageId + "?body-format=storage");
        long responseLimit = safeAdd(config.maxContentBytes(), JSON_ENVELOPE_ALLOWANCE_BYTES);
        PageDetail page = parse(get(config, clientFactory.apply(config), uri, responseLimit).body(), PageDetail.class);
        if (page == null || !pageId.equals(page.id()) || page.title() == null
                || page.body() == null || page.body().storage() == null
                || page.body().storage().value() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Confluence 页面正文响应不完整：" + pageId);
        }
        byte[] html = toHtml(page.title(), page.body().storage().value());
        if (html.length > config.maxContentBytes()) {
            throw BizException.invalidParam("Confluence 页面超过上传大小上限，读取已中止");
        }
        return html;
    }

    @Override
    public HealthStatus testConnection(ExtSourceConfig config) {
        try {
            URI origin = requireConfig(config);
            resolveSpaceId(config, clientFactory.apply(config), origin);
            return HealthStatus.up("Confluence space reachable: " + config.bucket());
        } catch (Exception e) {
            log.info("confluence connection test failed, spaceKey={}, reason={}", config.bucket(), e.getMessage());
            return HealthStatus.down("connection failed: " + e.getMessage());
        }
    }

    private String resolveSpaceId(ExtSourceConfig config, HttpClient client, URI origin) {
        String query = "?keys=" + encode(config.bucket()) + "&status=" + STATUS_CURRENT + "&limit=2";
        SpaceList spaces = parse(get(config, client, apiUri(origin, "/spaces" + query),
                MAX_METADATA_BYTES).body(), SpaceList.class);
        if (spaces == null || CollectionUtils.isEmpty(spaces.results())) {
            throw BizException.notFound("Confluence 空间不存在或当前账号无权访问：" + config.bucket());
        }
        List<SpaceSummary> exact = spaces.results().stream()
                .filter(space -> space != null && space.id() != null && space.key() != null)
                .filter(space -> config.bucket().equalsIgnoreCase(space.key()))
                .toList();
        if (exact.size() != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "Confluence 空间响应不唯一：" + config.bucket());
        }
        return exact.get(0).id();
    }

    private ApiResponse get(ExtSourceConfig config, HttpClient client, URI uri, long maxBytes) {
        ensureSameOrigin(endpointValidator.apply(config.endpoint()), uri);
        String credential = config.accessKey() + ":" + config.secretKey();
        String basic = Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Accept", "application/json")
                .header(HEADER_AUTHORIZATION, "Basic " + basic)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBounded(response.body(), maxBytes);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "Confluence API 请求失败（HTTP " + response.statusCode() + "）");
            }
            return new ApiResponse(new String(body, StandardCharsets.UTF_8),
                    response.headers().firstValue(HEADER_LINK).orElse(null));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Confluence API 请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Confluence API 请求被中断", e);
        }
    }

    private byte[] readBounded(InputStream stream, long maxBytes) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > maxBytes) {
                    throw BizException.invalidParam("Confluence API 响应超过大小上限，读取已中止");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private URI nextUri(URI origin, URI current, ApiResponse response, Links links) {
        String candidate = links == null ? null : links.next();
        if (candidate == null || candidate.isBlank()) {
            candidate = nextFromLinkHeader(response.linkHeader());
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        URI next = current.resolve(candidate.trim());
        ensureSameOrigin(origin, next);
        return next;
    }

    private String nextFromLinkHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = NEXT_LINK_PATTERN.matcher(header);
        return matcher.find() ? matcher.group(1) : null;
    }

    private URI pagesUri(URI origin, String spaceId, long limit) {
        return apiUri(origin, "/spaces/" + encode(spaceId) + "/pages?status="
                + STATUS_CURRENT + "&limit=" + limit);
    }

    private URI apiUri(URI origin, String suffix) {
        return URI.create(origin.toString() + API_PREFIX + suffix);
    }

    private URI requireConfig(ExtSourceConfig config) {
        if (config == null) {
            throw BizException.invalidParam("Confluence 数据源配置不能为空");
        }
        if (config.bucket() == null || config.bucket().isBlank()) {
            throw BizException.invalidParam("Confluence Space Key 不能为空");
        }
        if (config.accessKey() == null || config.accessKey().isBlank()) {
            throw BizException.invalidParam("Atlassian 账号邮箱不能为空");
        }
        if (config.secretKey() == null || config.secretKey().isBlank()) {
            throw BizException.invalidParam("Atlassian API Token 不能为空");
        }
        if (config.maxContentBytes() <= 0) {
            throw BizException.invalidParam("连接器正文大小上限必须大于 0");
        }
        return endpointValidator.apply(config.endpoint());
    }

    static URI validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw BizException.invalidParam("Confluence Site URL 不能为空");
        }
        final URI raw;
        try {
            raw = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("Confluence Site URL 格式不正确");
        }
        if (!"https".equalsIgnoreCase(raw.getScheme())) {
            throw BizException.invalidParam("Confluence Cloud Site URL 必须使用 HTTPS");
        }
        if (raw.getHost() == null || raw.getHost().isBlank() || raw.getUserInfo() != null
                || raw.getQuery() != null || raw.getFragment() != null) {
            throw BizException.invalidParam("Confluence Site URL 不能携带凭据、查询参数或片段");
        }
        String path = raw.getPath() == null ? "" : raw.getPath().replaceAll("/+$", "");
        if (!path.isEmpty() && !"/wiki".equals(path)) {
            throw BizException.invalidParam("Confluence Site URL 只能填写站点根地址或 /wiki 地址");
        }
        String authority = raw.getPort() < 0 ? raw.getHost() : raw.getHost() + ":" + raw.getPort();
        return URI.create("https://" + authority.toLowerCase(Locale.ROOT));
    }

    private void ensureSameOrigin(URI origin, URI target) {
        if (!origin.getScheme().equalsIgnoreCase(target.getScheme())
                || !origin.getHost().equalsIgnoreCase(target.getHost())
                || effectivePort(origin) != effectivePort(target)) {
            throw BizException.invalidParam("Confluence 分页地址越过站点边界，扫描已中止");
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void requireValidPageSummary(PageSummary page) {
        if (page == null || page.id() == null || !page.id().matches("[0-9]+")
                || page.title() == null || page.title().isBlank()
                || page.version() == null || page.version().number() <= 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Confluence 页面列表缺少有效的 id、标题或版本号");
        }
    }

    private String objectKeyOf(String pageId) {
        return PAGE_KEY_PREFIX + pageId + PAGE_KEY_SUFFIX;
    }

    private String pageIdOf(String objectKey) {
        Matcher matcher = PAGE_KEY_PATTERN.matcher(objectKey == null ? "" : objectKey);
        if (!matcher.matches()) {
            throw BizException.invalidParam("Confluence 页面对象 Key 不合法");
        }
        return matcher.group(1);
    }

    private String etagOf(PageSummary page) {
        return page.id() + ":v" + page.version().number();
    }

    private LocalDateTime modifiedAtOf(PageSummary page) {
        if (page.version() == null || page.version().createdAt() == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(page.version().createdAt()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private byte[] toHtml(String title, String storageBody) {
        String html = "<!doctype html><html><head><meta charset=\"UTF-8\"><title>"
                + escapeHtml(title) + "</title></head><body><article>" + storageBody
                + "</article></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private <T> T parse(String json, Class<T> type) {
        return JsonUtil.parse(json, type);
    }

    private record ApiResponse(String body, String linkHeader) {
    }

    private record Links(String next) {
    }

    private record SpaceList(List<SpaceSummary> results) {
    }

    private record SpaceSummary(String id, String key) {
    }

    private record PageList(List<PageSummary> results, @JsonProperty("_links") Links links) {
    }

    private record PageSummary(String id, String title, Version version) {
    }

    private record Version(int number, String createdAt) {
    }

    private record PageDetail(String id, String title, PageBody body) {
    }

    private record PageBody(StorageBody storage) {
    }

    private record StorageBody(String value) {
    }
}
