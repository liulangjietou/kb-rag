package io.kbrag.domain.service;

import io.kbrag.common.util.HashUtil;
import org.springframework.stereotype.Component;

/**
 * Produces the reuse fingerprints of a document version.
 *
 * <p>A version may only reuse the artifacts of an earlier build when the whole six element tuple
 * matches: content hash, parse fingerprint, split fingerprint and embedding version. Computing the
 * fingerprints in one place is what keeps that guarantee auditable as stages gain parameters.
 *
 * <p>M1 has a single parse route and a single split strategy, so the inputs are few; every value that
 * will become configurable later already has its own slot in the fingerprint string, which keeps the
 * hash stable for unchanged configurations.
 */
@Component
public class VersionFingerprintFactory {

    private static final String SEPARATOR = "|";

    /** Parse route of M1: the HTTP parser service with no cleaning and no vision model. */
    private static final String PARSE_ROUTE = "route=http-parser";
    private static final String CLEANING_RULES = "clean=none";
    private static final String MASKING = "mask=off";
    private static final String VISION_MODEL = "vlm=none";
    private static final String VISION_PROMPT = "vlm_prompt=none";
    private static final String OCR_ENGINE = "ocr=none";

    private static final String PARENT_CHILD = "parent_child=off";

    /**
     * Fingerprint of the parse stage inputs.
     *
     * @return hexadecimal digest
     */
    public String parseFingerprint() {
        return HashUtil.sha256Hex(String.join(SEPARATOR,
                PARSE_ROUTE, CLEANING_RULES, MASKING, VISION_MODEL, VISION_PROMPT, OCR_ENGINE));
    }

    /**
     * Fingerprint of the split stage inputs.
     *
     * @param strategy      splitter strategy code
     * @param maxTokens     maximum estimated tokens per chunk
     * @param overlapTokens overlap in estimated tokens
     * @return hexadecimal digest
     */
    public String chunkFingerprint(String strategy, int maxTokens, int overlapTokens) {
        return HashUtil.sha256Hex(String.join(SEPARATOR,
                "strategy=" + strategy,
                "max_tokens=" + maxTokens,
                "overlap_tokens=" + overlapTokens,
                PARENT_CHILD));
    }
}
