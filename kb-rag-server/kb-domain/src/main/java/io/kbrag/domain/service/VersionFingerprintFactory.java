package io.kbrag.domain.service;

import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.MetadataRule;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the reuse fingerprints of a document version.
 *
 * <p>A version may only reuse the artifacts of an earlier build when the whole six element tuple
 * matches: content hash, parse fingerprint, split fingerprint and embedding version. Computing the
 * fingerprints in one place is what keeps that guarantee auditable as stages gain parameters.
 *
 * <p><b>Two fingerprints, one staleness question.</b> The parse fingerprint covers everything that
 * decides what text a document yields — the cleaning rules and the vision model — while the split
 * fingerprint covers how that text is cut. The console asks a single question, "is this document built
 * with the current configuration", so {@link #configFingerprint} combines both and
 * {@link #versionFingerprint} recombines the two values stored on the version. Comparing only the split
 * side would let a cleaning rule change go unnoticed.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class VersionFingerprintFactory {

    private static final String SEPARATOR = "|";

    /** Parse route: the HTTP parser service. */
    private static final String PARSE_ROUTE = "route=http-parser";

    /** Version of the fixed vision prompt, bumped whenever the prompt text changes. */
    private static final String VISION_PROMPT_VERSION = "vlm_prompt=v1";

    /** Local OCR engine, deliberately absent: scanned pages go through the vision model instead. */
    private static final String OCR_ENGINE = "ocr=none";

    /**
     * Fingerprint of the parse stage inputs.
     *
     * @param config      knowledge base index configuration, supplies the cleaning rules
     * @param visionModel vision model name, {@code none} when unconfigured
     * @return hexadecimal digest
     */
    public String parseFingerprint(KbIndexConfig config, String visionModel) {
        return HashUtil.sha256Hex(String.join(SEPARATOR,
                PARSE_ROUTE,
                "clean=" + config.cleanRulesOrDefaults().fingerprintSegment(),
                "vlm=" + visionModel,
                VISION_PROMPT_VERSION,
                OCR_ENGINE));
    }

    /**
     * Fingerprint of the split stage inputs.
     *
     * @param config knowledge base index configuration
     * @return hexadecimal digest
     */
    public String chunkFingerprint(KbIndexConfig config) {
        List<String> segments = new ArrayList<>(List.of(
                "strategy=" + config.getSplitStrategy(),
                "chunk_max_tokens=" + config.getChunkMaxTokens(),
                "chunk_overlap=" + config.getChunkOverlap(),
                config.parentChildOrDisabled().fingerprintSegment()));
        // Appended only when rules exist: a knowledge base that never declared any keeps the exact
        // fingerprint it had before M14, so shipping the feature marks nothing configuration stale.
        if (CollectionUtils.isNotEmpty(config.metadataRulesOrEmpty())) {
            segments.add("metadata_rules=" + config.metadataRulesOrEmpty().stream()
                    .map(MetadataRule::fingerprintSegment)
                    .reduce((left, right) -> left + ";" + right)
                    .orElse(""));
        }
        // Only the strategy that reads a parameter folds it in, for the same reason: a fixed length or
        // llm_semantic base never carried these keys, so adding them unconditionally would rebuild every
        // pre-M14 knowledge base for a value it does not use.
        if (SeparatorTextSplitter.STRATEGY_CODE.equals(config.getSplitStrategy())) {
            String separator = config.getSplitSeparator() == null || config.getSplitSeparator().isEmpty()
                    ? SeparatorTextSplitter.DEFAULT_SEPARATOR : config.getSplitSeparator();
            segments.add("split_separator=" + separator + ";is_regex=" + config.isSplitSeparatorIsRegex());
        } else if (HeadingTextSplitter.STRATEGY_CODE.equals(config.getSplitStrategy())) {
            int level = config.getSplitHeadingLevel() < HeadingTextSplitter.MIN_HEADING_LEVEL
                    || config.getSplitHeadingLevel() > HeadingTextSplitter.MAX_HEADING_LEVEL
                    ? HeadingTextSplitter.DEFAULT_HEADING_LEVEL : config.getSplitHeadingLevel();
            segments.add("split_heading_level=" + level);
        }
        // Appended only when on, for the same compatibility reason as the metadata rules: a knowledge
        // base that never turned multimodal indexing on keeps the exact fingerprint it had before M14,
        // so shipping the feature marks nothing configuration stale, while flipping the switch on marks
        // every affected document stale so a rebuild backfills or clears the multimodal vectors.
        if (config.isMultimodalEnabled()) {
            segments.add("multimodal_enabled=true");
        }
        return HashUtil.sha256Hex(String.join(SEPARATOR, segments));
    }

    /**
     * Fingerprint stored on the knowledge base as {@code current_config_fingerprint}.
     *
     * @param config      knowledge base index configuration
     * @param visionModel vision model name, {@code none} when unconfigured
     * @return hexadecimal digest covering the parse and the split inputs
     */
    public String configFingerprint(KbIndexConfig config, String visionModel) {
        return versionFingerprint(parseFingerprint(config, visionModel), chunkFingerprint(config));
    }

    /**
     * Recombines the two fingerprints a version stores, so it can be compared with
     * {@link #configFingerprint}.
     *
     * @param parseFingerprint value of {@code t_kb_document_version.parse_fingerprint}
     * @param chunkFingerprint value of {@code t_kb_document_version.chunk_fingerprint}
     * @return hexadecimal digest, {@code null} when either side was never recorded
     */
    public String versionFingerprint(String parseFingerprint, String chunkFingerprint) {
        if (parseFingerprint == null || chunkFingerprint == null) {
            return null;
        }
        return HashUtil.sha256Hex(parseFingerprint + SEPARATOR + chunkFingerprint);
    }
}
