package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Knowledge base level cleaning rules, stored inside {@code index_config.clean_rules}.
 *
 * <p>Every rule defaults to off except the Excel header join, so an existing knowledge base keeps the
 * behaviour it was built with when this configuration block appears. Cleaning removes text, and text
 * removed by mistake can only be recovered by a rebuild, which is why nothing is enabled implicitly.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CleanRules {

    private static final String SEPARATOR = ",";
    private static final String FIELD_SEPARATOR = ";";

    /** Removes the lines that repeat across pages as a header or a footer. */
    @JsonProperty("strip_header_footer")
    private boolean stripHeaderFooter;

    /** Regular expressions whose matches are watermark noise and are dropped. */
    @JsonProperty("strip_watermark_patterns")
    private List<String> stripWatermarkPatterns = new ArrayList<>();

    /** Free form replacements applied in declaration order. */
    @JsonProperty("regex_replacements")
    private List<RegexReplacement> regexReplacements = new ArrayList<>();

    /**
     * Repeats the sheet header on every row of a spreadsheet.
     *
     * <p>Consumed by the parser, carried here so the whole cleaning configuration lives in one place
     * and feeds one fingerprint.
     */
    @JsonProperty("excel_header_join")
    private boolean excelHeaderJoin = true;

    /** Reserved for the document metadata extraction of a later milestone. */
    @JsonProperty("extract_metadata")
    private boolean extractMetadata;

    /** Masking of personal data. */
    @JsonProperty("desensitize")
    private Desensitize desensitize = new Desensitize();

    /**
     * Default rules of a freshly created knowledge base.
     *
     * @return rules with everything off except the Excel header join
     */
    public static CleanRules defaults() {
        return new CleanRules();
    }

    /**
     * Resolves the masking block, never {@code null}.
     *
     * @return masking configuration
     */
    public Desensitize desensitizeOrDisabled() {
        return desensitize == null ? new Desensitize() : desensitize;
    }

    /**
     * Watermark patterns, never {@code null}.
     *
     * @return configured patterns
     */
    public List<String> watermarkPatterns() {
        return stripWatermarkPatterns == null ? List.of() : stripWatermarkPatterns;
    }

    /**
     * Replacements, never {@code null}.
     *
     * @return configured replacements
     */
    public List<RegexReplacement> replacements() {
        return regexReplacements == null ? List.of() : regexReplacements;
    }

    /**
     * Tells whether any rule would change the text at all.
     *
     * @return {@code true} when at least one text altering rule is active
     */
    public boolean anyActive() {
        return stripHeaderFooter
                || CollectionUtils.isNotEmpty(watermarkPatterns())
                || CollectionUtils.isNotEmpty(replacements())
                || desensitizeOrDisabled().isEnabled();
    }

    /**
     * Copies the rules with masking forced on, which is what a chat import always uses.
     *
     * <p>Chat logs are personal data by nature, so the requirement makes masking the default for that
     * path regardless of what the knowledge base configured for documents.
     *
     * @return copy with {@code desensitize.enabled} set
     */
    public CleanRules withDesensitizeEnabled() {
        CleanRules copy = new CleanRules();
        copy.setStripHeaderFooter(stripHeaderFooter);
        copy.setStripWatermarkPatterns(new ArrayList<>(watermarkPatterns()));
        copy.setRegexReplacements(new ArrayList<>(replacements()));
        copy.setExcelHeaderJoin(excelHeaderJoin);
        copy.setExtractMetadata(extractMetadata);
        Desensitize source = desensitizeOrDisabled();
        Desensitize target = new Desensitize();
        target.setEnabled(true);
        target.setPhone(source.isPhone());
        target.setIdCard(source.isIdCard());
        target.setBankCard(source.isBankCard());
        target.setEmail(source.isEmail());
        copy.setDesensitize(target);
        return copy;
    }

    /**
     * Deterministic rendering of the rules, consumed by the parse fingerprint.
     *
     * <p>Order matters: the same rule set has to produce the same string on every call, otherwise a
     * document would be marked stale by a configuration read rather than by a configuration change.
     *
     * @return stable textual form
     */
    public String fingerprintSegment() {
        StringBuilder builder = new StringBuilder();
        builder.append("header_footer=").append(stripHeaderFooter).append(FIELD_SEPARATOR);
        builder.append("watermark=").append(String.join(SEPARATOR, watermarkPatterns())).append(FIELD_SEPARATOR);
        builder.append("replacements=");
        for (RegexReplacement replacement : replacements()) {
            builder.append(replacement.fingerprintSegment()).append(SEPARATOR);
        }
        builder.append(FIELD_SEPARATOR);
        builder.append("excel_header_join=").append(excelHeaderJoin).append(FIELD_SEPARATOR);
        builder.append("extract_metadata=").append(extractMetadata).append(FIELD_SEPARATOR);
        builder.append("mask=").append(desensitizeOrDisabled().fingerprintSegment());
        return builder.toString();
    }

    /**
     * One regular expression replacement.
     */
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RegexReplacement {

        /** Java regular expression to search for. */
        @JsonProperty("pattern")
        private String pattern;

        /** Replacement text, blank deletes the match. */
        @JsonProperty("replacement")
        private String replacement;

        /**
         * Deterministic rendering used by the fingerprint.
         *
         * @return stable textual form
         */
        public String fingerprintSegment() {
            return pattern + "=>" + (replacement == null ? "" : replacement);
        }
    }

    /**
     * Masking switches, one per data category.
     */
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Desensitize {

        /** Master switch of the masking step. */
        @JsonProperty("enabled")
        private boolean enabled;

        /** Masks mainland China mobile numbers. */
        @JsonProperty("phone")
        private boolean phone = true;

        /** Masks mainland China resident identity card numbers. */
        @JsonProperty("id_card")
        private boolean idCard = true;

        /** Masks bank card numbers. */
        @JsonProperty("bank_card")
        private boolean bankCard = true;

        /** Masks the local part of an e-mail address; off by default because it is often business data. */
        @JsonProperty("email")
        private boolean email;

        /**
         * Deterministic rendering used by the fingerprint.
         *
         * @return stable textual form
         */
        public String fingerprintSegment() {
            return "enabled=" + enabled + SEPARATOR + "phone=" + phone + SEPARATOR + "id_card=" + idCard
                    + SEPARATOR + "bank_card=" + bankCard + SEPARATOR + "email=" + email;
        }
    }
}
