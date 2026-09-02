package io.kbrag.parser.chat;

import io.kbrag.parser.support.Whitespace;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A loaded chat-log mapping profile: the three independent shapes a chat export can arrive in.
 *
 * <ul>
 *   <li><b>csv/xlsx</b> - for each logical target field, an ordered list of candidate source column
 *       names (M3-CONTRACTS.md §2.2). Column matching is case-insensitive and ignores whitespace, so
 *       an export with slightly different header casing or spacing resolves without touching code.
 *   <li><b>txt</b> - an ordered list of line-header regex templates (M8-CONTRACTS.md §0.1).
 *   <li><b>html</b> - a set of DOM node selectors (M8-CONTRACTS.md §0.2).
 * </ul>
 *
 * <p>Onboarding a brand-new export source is therefore "write a yml document", never "change parser
 * code" - which is the whole point of the indirection.
 *
 * @author owlzhangfq@gmail.com
 */
public class MappingProfile {

    /**
     * Logical target fields a profile may define candidates for. Only {@code content} is a hard
     * requirement at parse time; the rest degrade gracefully when unresolved.
     */
    public static final List<String> TARGET_FIELDS = List.of(
            "session_id", "session_name", "sender", "is_self", "send_time", "msg_type", "content", "msg_id");

    public static final String FIELD_SESSION_ID = "session_id";
    public static final String FIELD_SESSION_NAME = "session_name";
    public static final String FIELD_SENDER = "sender";
    public static final String FIELD_IS_SELF = "is_self";
    public static final String FIELD_SEND_TIME = "send_time";
    public static final String FIELD_MSG_TYPE = "msg_type";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_MSG_ID = "msg_id";

    private final String name;
    private final Map<String, List<String>> candidates;
    private final List<TxtLinePattern> txtPatterns;
    private final Map<String, String> htmlSelectors;

    public MappingProfile(String name, Map<String, List<String>> candidates,
                          List<TxtLinePattern> txtPatterns, Map<String, String> htmlSelectors) {
        this.name = name;
        this.candidates = candidates;
        this.txtPatterns = txtPatterns;
        this.htmlSelectors = htmlSelectors;
    }

    public String getName() {
        return name;
    }

    public List<TxtLinePattern> getTxtPatterns() {
        return Collections.unmodifiableList(txtPatterns);
    }

    public Map<String, String> getHtmlSelectors() {
        return Collections.unmodifiableMap(htmlSelectors);
    }

    /**
     * Maps each target field to the first candidate column that actually exists in the upload.
     *
     * @param header the actual column names present in the file (csv header row / xlsx first row)
     * @return every entry in {@link #TARGET_FIELDS}, mapped to the matching actual header name or to
     *         null where no candidate matched
     */
    public Map<String, String> resolve(List<String> header) {
        Map<String, String> normalizedToActual = new HashMap<>();
        for (String actual : header) {
            // First occurrence wins, matching Python's dict comprehension over the header row.
            normalizedToActual.putIfAbsent(normalizeHeader(actual), actual);
        }
        Map<String, String> resolved = new HashMap<>();
        for (String field : TARGET_FIELDS) {
            String match = null;
            for (String candidate : candidates.getOrDefault(field, List.of())) {
                String actual = normalizedToActual.get(normalizeHeader(candidate));
                if (actual != null) {
                    match = actual;
                    break;
                }
            }
            resolved.put(field, match);
        }
        return resolved;
    }

    /** Case- and space-insensitive key used purely for header matching. */
    private static String normalizeHeader(String name) {
        if (name == null) {
            return "";
        }
        // Whitespace removal goes through the Python-semantics helper rather than a regex: a header
        // pasted out of a rendered table routinely carries a non-breaking space, which Java's own \s
        // does not match.
        return Whitespace.removeAll(name).toLowerCase(Locale.ROOT);
    }
}
