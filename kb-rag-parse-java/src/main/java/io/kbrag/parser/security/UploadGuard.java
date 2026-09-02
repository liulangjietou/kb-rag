package io.kbrag.parser.security;

import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.FileTooLargeException;
import lombok.extern.slf4j.Slf4j;

/**
 * Upload size cap (requirement doc §4.2 / M1-CONTRACTS.md §6).
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class UploadGuard {

    private UploadGuard() {
    }

    /**
     * Fast-fails if the uploaded file exceeds the configured size cap.
     *
     * @param content      raw uploaded bytes
     * @param maxSizeBytes configured cap
     */
    public static void ensureFileSizeWithinLimit(byte[] content, long maxSizeBytes) {
        int size = content == null ? 0 : content.length;
        if (size > maxSizeBytes) {
            log.error("upload rejected, errorCode={}, reason=file_too_large, sizeBytes={}, limitBytes={}",
                    ErrorCode.PARSE_FAILED, size, maxSizeBytes);
            throw new FileTooLargeException(
                    "file size " + size + " bytes exceeds limit " + maxSizeBytes + " bytes");
        }
    }
}
