package io.kbrag.infrastructure.storage;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MinIO backed object storage.
 *
 * <p>The bucket stays private: downloads are always served through a time limited pre signed URL, so
 * a leaked link expires instead of granting permanent unauthenticated access to the original file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioObjectStorage implements ObjectStorage {

    /** Part size handed to the MinIO client when the stream length is known. */
    private static final long UNKNOWN_PART_SIZE = -1L;

    private final MinioClient minioClient;
    private final KbProperties properties;

    @Override
    public void put(String objectKey, InputStream content, long size, String contentType) {
        String bucket = properties.getMinio().getBucket();
        try {
            ensureBucket(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(content, size, UNKNOWN_PART_SIZE)
                    .contentType(contentType)
                    .build());
            log.info("object stored, bucket={}, object={}, size={}", bucket, objectKey, size);
        } catch (Exception e) {
            log.error("store object failed, errorCode={}, object={}", ErrorCode.INTERNAL_ERROR, objectKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "store object failed", e);
        } finally {
            closeQuietly(content);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("read object failed, errorCode={}, object={}", ErrorCode.INTERNAL_ERROR, objectKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "read object failed", e);
        }
    }

    @Override
    public String presignedUrl(String objectKey, Duration ttl) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getMinio().getBucket())
                    .object(objectKey)
                    .expiry((int) ttl.getSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            log.error("presign object failed, errorCode={}, object={}", ErrorCode.INTERNAL_ERROR, objectKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "presign object failed", e);
        }
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            // Probing the configured bucket is enough to prove connectivity and credentials, and it
            // does not require the account to be allowed to list every bucket.
            minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .build());
            return HealthStatus.up("minio reachable");
        } catch (Exception e) {
            log.error("minio health check failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            return HealthStatus.down("minio unreachable");
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("bucket created, bucket={}", bucket);
        }
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception e) {
            log.info("close upload stream failed, reason={}", e.getMessage());
        }
    }
}
