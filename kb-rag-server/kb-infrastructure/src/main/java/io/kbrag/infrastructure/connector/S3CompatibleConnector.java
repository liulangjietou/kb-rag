package io.kbrag.infrastructure.connector;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.ExtSourceConfig;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * S3/OSS compatible connector, the M14 contract section 2.1, first implementation of the SPI.
 *
 * <p>Reuses the MinIO SDK the object storage adapter already depends on: the S3 wire protocol is
 * what MinIO, OSS and every compatible store speak, so one client covers them all. Unlike
 * {@code MinioObjectStorage} the client here is built per call from the source's own credentials -
 * every registered source points at a different store, so there is no bean to share.
 *
 * <p>The endpoint is deliberately not passed through the SSRF guard: it is an administrator
 * configuration value, not end user input, and its legitimate values are exactly the intranet
 * addresses the guard exists to block.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class S3CompatibleConnector implements ExternalConnector {

    private static final int COPY_BUFFER_SIZE = 8192;

    /** Routing key stored in {@code t_kb_ext_source.source_type}. */
    static final String TYPE = "s3";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<RemoteObject> listObjects(ExtSourceConfig config) {
        MinioClient client = buildClient(config);
        List<RemoteObject> objects = new ArrayList<>();
        // One beyond the cap on purpose: the caller tells a truncated listing from a complete one
        // by the extra element, without a second remote round trip.
        int limit = config.maxObjects() + 1;
        try {
            Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(config.bucket())
                    .prefix(config.prefix() == null ? "" : config.prefix())
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                objects.add(new RemoteObject(
                        item.objectName(),
                        null,
                        stripQuotes(item.etag()),
                        item.size(),
                        lastModifiedOf(item)));
                if (objects.size() >= limit) {
                    break;
                }
            }
            return objects;
        } catch (Exception e) {
            log.error("list objects failed, errorCode={}, bucket={}", ErrorCode.INTERNAL_ERROR, config.bucket(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "list objects failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] fetchObject(ExtSourceConfig config, String objectKey) {
        MinioClient client = buildClient(config);
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(config.bucket())
                .object(objectKey)
                .build())) {
            return readBounded(stream, config.maxContentBytes());
        } catch (Exception e) {
            log.error("fetch object failed, errorCode={}, bucket={}, object={}",
                    ErrorCode.INTERNAL_ERROR, config.bucket(), objectKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "fetch object failed: " + e.getMessage(), e);
        }
    }

    @Override
    public HealthStatus testConnection(ExtSourceConfig config) {
        try {
            boolean exists = buildClient(config).bucketExists(BucketExistsArgs.builder()
                    .bucket(config.bucket())
                    .build());
            return exists
                    ? HealthStatus.up("bucket reachable")
                    : HealthStatus.down("bucket not found: " + config.bucket());
        } catch (Exception e) {
            log.info("ext source connection test failed, bucket={}, reason={}", config.bucket(), e.getMessage());
            return HealthStatus.down("connection failed: " + e.getMessage());
        }
    }

    /**
     * Builds a client bound to one source's endpoint and credentials, with the configured budget
     * applied to connect, read and write alike - the SDK default of five minutes would let one
     * dead store stall a whole scheduled pass.
     */
    private MinioClient buildClient(ExtSourceConfig config) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.timeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(config.timeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.timeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(config.endpoint())
                .credentials(config.accessKey(), config.secretKey())
                .httpClient(httpClient);
        if (config.region() != null && !config.region().isBlank()) {
            builder.region(config.region());
        }
        return builder.build();
    }

    /** S3 etags arrive wrapped in double quotes; the stored comparison value must not keep them. */
    private String stripQuotes(String etag) {
        if (etag == null) {
            return null;
        }
        return etag.replace("\"", "");
    }

    /** Some stores omit the timestamp in listings; absence must not fail the whole scan. */
    private LocalDateTime lastModifiedOf(Item item) {
        try {
            return item.lastModified() == null ? null : item.lastModified().toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads one object without allowing a remote store to grow the process heap past upload policy. */
    private byte[] readBounded(InputStream stream, long maxBytes) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if ((long) body.size() + read > maxBytes) {
                throw BizException.invalidParam("对象超过上传大小上限，读取已中止");
            }
            body.write(buffer, 0, read);
        }
        return body.toByteArray();
    }
}
