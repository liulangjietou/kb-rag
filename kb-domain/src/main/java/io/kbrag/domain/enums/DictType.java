package io.kbrag.domain.enums;

import java.util.Locale;

/**
 * Kind of ik dictionary a word belongs to.
 *
 * <p>The two kinds are served through two different remote dictionary URLs, which is what the ik
 * plugin expects: one file adds domain terms the tokenizer must keep whole, the other removes noise
 * words from the index.
 *
 * @author owlzhangfq@gmail.com
 */
public enum DictType {

    /** Extension dictionary, {@code remote_ext_dict}. */
    EXT,

    /** Stop word dictionary, {@code remote_ext_stopwords}. */
    STOP;

    /**
     * Lower case literal used in the API and in the dictionary URL path.
     *
     * @return API side value
     */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a dictionary kind from its literal.
     *
     * @param value API value, case insensitive
     * @return matching kind, {@code null} when blank or unknown
     */
    public static DictType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DictType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
