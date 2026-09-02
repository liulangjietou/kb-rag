package io.kbrag.parser.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Translates a Python-flavoured line-template regex into an equivalent {@link Pattern}.
 *
 * <p>This class exists because of where the templates come from. A mapping profile's {@code txt:}
 * patterns are authored against the Python service - they ship in its {@code app/mappings/*.yml}, and
 * once profiles moved into {@code t_kb_source_mapping} (M8-CONTRACTS.md §0.7) kb-rag-server sends that
 * same YAML body to whichever parser is deployed. A Java port that could not read them would not be a
 * port; it would be a second, incompatible profile format that every operator has to maintain twice.
 *
 * <p>Two differences have to be bridged:
 *
 * <ul>
 *   <li><b>Named group syntax.</b> Python writes {@code (?P<name>...)} and {@code (?P=name)}; Java
 *       writes {@code (?<name>...)} and {@code \k<name>}. A plain textual rewrite is not enough,
 *       because Java additionally restricts a group name to {@code [a-zA-Z][a-zA-Z0-9]*} - and the
 *       contract's own group names, {@code send_time} above all, contain an underscore Java rejects
 *       outright. Named groups are therefore rewritten to <i>numbered</i> groups and the names are
 *       kept in a side table, which sidesteps the naming rule entirely and works for any name a
 *       profile author picks.
 *   <li><b>Unicode character classes.</b> Python 3's {@code \d}/{@code \w}/{@code \s} are
 *       Unicode-aware; Java's are ASCII-only unless told otherwise. Compilation therefore sets
 *       {@code UNICODE_CHARACTER_CLASS}, so a template written against Python behaves the same here.
 * </ul>
 *
 * <p>Assigning the numbers correctly means knowing which parentheses actually open a capturing group,
 * so the scan below tracks escapes and character classes rather than pattern-matching on text: inside
 * {@code [(\)]} a parenthesis is a literal, and after a backslash it is too.
 *
 * @author owlzhangfq@gmail.com
 */
public final class PythonRegexTranslator {

    private PythonRegexTranslator() {
    }

    /**
     * A translated pattern together with the group number each source name refers to.
     *
     * @param pattern     the compiled Java pattern
     * @param groupsByName source group name to its 1-based capturing group number
     */
    public record TranslatedPattern(Pattern pattern, Map<String, Integer> groupsByName) {

        /**
         * @param name group name as written in the profile
         * @return true when the translated pattern captures that name
         */
        public boolean hasGroup(String name) {
            return groupsByName.containsKey(name);
        }
    }

    /**
     * @param regex the profile's regex, in Python or Java flavour
     * @return the compiled pattern and its name-to-number table
     * @throws PatternSyntaxException when the translated regex is not valid
     */
    public static TranslatedPattern translate(String regex) {
        StringBuilder translated = new StringBuilder(regex.length());
        Map<String, Integer> groupsByName = new LinkedHashMap<>();
        int groupNumber = 0;
        boolean inCharClass = false;

        int i = 0;
        while (i < regex.length()) {
            char ch = regex.charAt(i);

            if (ch == '\\' && i + 1 < regex.length()) {
                translated.append(ch).append(regex.charAt(i + 1));
                i += 2;
                continue;
            }
            if (inCharClass) {
                translated.append(ch);
                if (ch == ']') {
                    inCharClass = false;
                }
                i++;
                continue;
            }
            if (ch == '[') {
                inCharClass = true;
                translated.append(ch);
                i++;
                // A ']' immediately after '[' or '[^' is a literal, not the class terminator.
                if (i < regex.length() && regex.charAt(i) == '^') {
                    translated.append(regex.charAt(i));
                    i++;
                }
                if (i < regex.length() && regex.charAt(i) == ']') {
                    translated.append(regex.charAt(i));
                    i++;
                }
                continue;
            }
            if (ch != '(') {
                translated.append(ch);
                i++;
                continue;
            }

            // A '(' - decide what kind of group it opens.
            if (!regex.startsWith("(?", i)) {
                groupNumber++;
                translated.append('(');
                i++;
                continue;
            }

            int named = readNamedGroupOpen(regex, i);
            if (named >= 0) {
                groupNumber++;
                groupsByName.put(regex.substring(nameStart(regex, i), named), groupNumber);
                translated.append('(');
                i = named + 1;
                continue;
            }
            int backref = readNamedBackreferenceEnd(regex, i);
            if (backref >= 0) {
                String name = regex.substring(i + "(?P=".length(), backref);
                Integer target = groupsByName.get(name);
                if (target == null) {
                    throw new PatternSyntaxException(
                            "backreference to undefined group '" + name + "'", regex, i);
                }
                translated.append("\\").append(target);
                i = backref + 1;
                continue;
            }
            // Every other "(?" form - (?:, (?=, (?!, (?<=, (?<!, (?#, inline flags - is non-capturing
            // and passes through untouched.
            translated.append('(');
            i++;
        }

        return new TranslatedPattern(
                Pattern.compile(translated.toString(), Pattern.UNICODE_CHARACTER_CLASS),
                Map.copyOf(groupsByName));
    }

    /**
     * @return index of the '>' closing a named-group opener at {@code start}, or -1 when this is not
     *         one - which includes the lookbehind forms {@code (?<=} and {@code (?<!}
     */
    private static int readNamedGroupOpen(String regex, int start) {
        int nameStart = nameStart(regex, start);
        if (nameStart < 0) {
            return -1;
        }
        int end = regex.indexOf('>', nameStart);
        if (end < 0 || end == nameStart) {
            return -1;
        }
        return end;
    }

    /**
     * @return index where the group name begins, or -1 when {@code start} does not open a named group
     */
    private static int nameStart(String regex, int start) {
        if (regex.startsWith("(?P<", start)) {
            return start + "(?P<".length();
        }
        if (regex.startsWith("(?<", start)) {
            char next = start + 3 < regex.length() ? regex.charAt(start + 3) : '\0';
            // "(?<=" and "(?<!" are lookbehinds, not named groups.
            if (next != '=' && next != '!') {
                return start + "(?<".length();
            }
        }
        return -1;
    }

    /**
     * @return index of the ')' closing a {@code (?P=name)} backreference at {@code start}, or -1
     */
    private static int readNamedBackreferenceEnd(String regex, int start) {
        if (!regex.startsWith("(?P=", start)) {
            return -1;
        }
        int end = regex.indexOf(')', start);
        return end > start + "(?P=".length() ? end : -1;
    }
}
