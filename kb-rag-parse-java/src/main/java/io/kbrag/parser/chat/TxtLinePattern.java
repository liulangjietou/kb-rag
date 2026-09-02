package io.kbrag.parser.chat;

import java.util.regex.Matcher;

/**
 * One compiled {@code txt:} line-header template (M8-CONTRACTS.md §0.1).
 *
 * <p>Fields are addressed by the name the profile author wrote, not by a Java group name: the
 * translation to numbered groups happens in {@link PythonRegexTranslator}, and this class is where
 * that indirection is hidden from the adapter.
 *
 * @author owlzhangfq@gmail.com
 */
public class TxtLinePattern {

    /** Named groups a template must capture; {@code content} is optional. */
    public static final String GROUP_SEND_TIME = "send_time";
    public static final String GROUP_SENDER = "sender";
    public static final String GROUP_CONTENT = "content";

    private final String name;
    private final PythonRegexTranslator.TranslatedPattern translated;

    public TxtLinePattern(String name, PythonRegexTranslator.TranslatedPattern translated) {
        this.name = name;
        this.translated = translated;
    }

    public String getName() {
        return name;
    }

    /**
     * Matches this template against the start of a line, the way Python's {@code re.match} does - the
     * template describes a line <i>header</i>, so the rest of the line is free text, not a mismatch.
     *
     * @param line one non-blank line of the export
     * @return the matcher when the header matched, otherwise null
     */
    public Matcher matchHeader(String line) {
        Matcher matcher = translated.pattern().matcher(line);
        return matcher.lookingAt() ? matcher : null;
    }

    /**
     * @param matcher a matcher returned by {@link #matchHeader}
     * @param groupName group name as written in the profile
     * @return the captured text, or null when this template does not capture that name
     */
    public String group(Matcher matcher, String groupName) {
        Integer index = translated.groupsByName().get(groupName);
        return index == null ? null : matcher.group(index);
    }

    /**
     * @param groupName group name as written in the profile
     * @return true when this template captures it
     */
    public boolean hasGroup(String groupName) {
        return translated.hasGroup(groupName);
    }
}
