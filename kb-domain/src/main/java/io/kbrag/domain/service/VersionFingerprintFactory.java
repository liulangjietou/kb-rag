package io.kbrag.domain.service;

import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.model.KbIndexConfig;
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
     * <p>The very same function computes the knowledge base level
     * {@code current_config_fingerprint} and the per version {@code chunk_fingerprint}: comparing the
     * two is how a configuration change is turned into the {@code config_stale} flag, and that
     * comparison is only meaningful while both sides are produced here.
     *
     * @param config knowledge base index configuration
     * @return hexadecimal digest
     */
    public String chunkFingerprint(KbIndexConfig config) {
        return HashUtil.sha256Hex(String.join(SEPARATOR,
                "strategy=" + config.getSplitStrategy(),
                "chunk_max_tokens=" + config.getChunkMaxTokens(),
                "chunk_overlap=" + config.getChunkOverlap(),
                config.parentChildOrDisabled().fingerprintSegment()));
    }
}
