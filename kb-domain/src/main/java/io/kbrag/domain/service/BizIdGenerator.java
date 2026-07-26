package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Generates the prefixed business identifiers exposed through the API.
 *
 * <p>Business keys are used instead of the auto increment primary key so identifiers can be logged,
 * embedded in index names and shared with the console without leaking row counts.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class BizIdGenerator {

    /** Hexadecimal characters kept from the random part. */
    private static final int RANDOM_LENGTH = 16;

    /**
     * Generates a knowledge base id.
     *
     * @return prefixed identifier
     */
    public String knowledgeBaseId() {
        return generate(KbConstants.KB_ID_PREFIX);
    }

    /**
     * Generates a document id.
     *
     * @return prefixed identifier
     */
    public String documentId() {
        return generate(KbConstants.DOC_ID_PREFIX);
    }

    /**
     * Generates a document version id.
     *
     * @return prefixed identifier
     */
    public String documentVersionId() {
        return generate(KbConstants.VERSION_ID_PREFIX);
    }

    /**
     * Generates a chunk id.
     *
     * @return prefixed identifier
     */
    public String chunkId() {
        return generate(KbConstants.CHUNK_ID_PREFIX);
    }

    /**
     * Generates an asynchronous task id.
     *
     * @return prefixed identifier
     */
    public String taskId() {
        return generate(KbConstants.TASK_ID_PREFIX);
    }

    /**
     * Generates an image asset id.
     *
     * @return prefixed identifier
     */
    public String imageAssetId() {
        return generate(KbConstants.IMAGE_ASSET_ID_PREFIX);
    }

    /**
     * Generates a chat import upload token.
     *
     * @return prefixed identifier
     */
    public String uploadToken() {
        return generate(KbConstants.UPLOAD_TOKEN_PREFIX);
    }

    /**
     * Generates a chunk annotation id.
     *
     * @return prefixed identifier
     */
    public String annotationId() {
        return generate(KbConstants.ANNOTATION_ID_PREFIX);
    }

    /**
     * Generates an evaluation data set id.
     *
     * @return prefixed identifier
     */
    public String evalDatasetId() {
        return generate(KbConstants.EVAL_DATASET_ID_PREFIX);
    }

    /**
     * Generates an evaluation case id.
     *
     * @return prefixed identifier
     */
    public String evalCaseId() {
        return generate(KbConstants.EVAL_CASE_ID_PREFIX);
    }

    /**
     * Generates an evaluation run id.
     *
     * @return prefixed identifier
     */
    public String evalRunId() {
        return generate(KbConstants.EVAL_RUN_ID_PREFIX);
    }

    /**
     * Generates an evaluation result row id.
     *
     * @return prefixed identifier
     */
    public String evalResultId() {
        return generate(KbConstants.EVAL_RESULT_ID_PREFIX);
    }

    /**
     * Generates an application id.
     *
     * @return prefixed identifier
     */
    public String appId() {
        return generate(KbConstants.APP_ID_PREFIX);
    }

    /**
     * Generates an application version id.
     *
     * @return prefixed identifier
     */
    public String appVersionId() {
        return generate(KbConstants.APP_VERSION_ID_PREFIX);
    }

    /**
     * Generates an API key row id; the key material itself is minted by {@link ApiKeyFactory}.
     *
     * @return prefixed identifier
     */
    public String apiKeyId() {
        return generate(KbConstants.API_KEY_ID_PREFIX);
    }

    /**
     * Generates an outbound call audit row id.
     *
     * @return prefixed identifier
     */
    public String apiAuditLogId() {
        return generate(KbConstants.API_AUDIT_LOG_ID_PREFIX);
    }

    private String generate(String prefix) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_LENGTH);
        return prefix + KbConstants.ID_SEPARATOR + random;
    }
}
