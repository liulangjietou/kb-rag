package io.kbrag.app.chat;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short lived registry of staged chat uploads.
 *
 * <p>The parse result itself lives in object storage; only the pointer and its expiry are kept in
 * memory, following the same single instance assumption as the console session store. Expiring the token
 * is what stops an abandoned preview from being confirmed days later against a knowledge base whose
 * documents have since changed.
 *
 * <p>The knowledge base id is part of the entry and is verified on lookup, so a token issued for one
 * knowledge base cannot be replayed against another.
 *
 * <p>The token is supplied by the caller rather than generated here, because the staged object key embeds
 * it: generating it in two places would let the registry entry and the storage layout disagree.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUploadTokenStore {

    private final KbProperties properties;

    private final Map<String, StagedUpload> staged = new ConcurrentHashMap<>();

    /**
     * Registers a staged upload.
     *
     * @param token     token the object key was built from
     * @param kbId      target knowledge base
     * @param objectKey object storage key of the serialised parse result
     * @param fileName  original file name, shown in logs
     */
    public void register(String token, String kbId, String objectKey, String fileName) {
        purgeExpired();
        Instant expiresAt = Instant.now()
                .plusSeconds(properties.getChatImport().getUploadTokenTtlMinutes() * 60L);
        staged.put(token, new StagedUpload(kbId, objectKey, fileName, expiresAt));
        log.info("chat upload staged, kbId={}, fileName={}, expiresAt={}", kbId, fileName, expiresAt);
    }

    /**
     * Resolves a token, failing fast when it is unknown, expired or bound to another knowledge base.
     *
     * @param kbId  knowledge base the confirmation targets
     * @param token token presented by the caller
     * @return staged upload
     */
    public StagedUpload require(String kbId, String token) {
        StagedUpload upload = token == null ? null : staged.get(token);
        if (upload == null || upload.expiresAt().isBefore(Instant.now())) {
            staged.remove(token);
            throw BizException.invalidParam("upload_token is unknown or expired");
        }
        if (!upload.kbId().equals(kbId)) {
            throw BizException.invalidParam("upload_token belongs to another knowledge base");
        }
        return upload;
    }

    /**
     * Drops a token after it was consumed.
     *
     * @param token token to drop
     */
    public void consume(String token) {
        staged.remove(token);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        staged.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    /**
     * One staged upload.
     *
     * @param kbId      target knowledge base
     * @param objectKey object storage key of the serialised parse result
     * @param fileName  original file name
     * @param expiresAt expiry instant
     */
    public record StagedUpload(String kbId, String objectKey, String fileName, Instant expiresAt) {
    }
}
