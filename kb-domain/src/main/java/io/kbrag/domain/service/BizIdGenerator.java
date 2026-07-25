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

    private String generate(String prefix) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_LENGTH);
        return prefix + KbConstants.ID_SEPARATOR + random;
    }
}
