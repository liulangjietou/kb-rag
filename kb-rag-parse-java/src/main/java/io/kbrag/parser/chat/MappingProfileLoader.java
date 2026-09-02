package io.kbrag.parser.chat;

import io.kbrag.parser.error.ChatMappingException;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.support.Whitespace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * Loads a {@link MappingProfile} from an inline YAML body or from a bundled profile file.
 *
 * <p>Priority is fixed by M8-CONTRACTS.md §0.7: a non-blank {@code profile_yaml} always wins over the
 * named local file. Once mapping profiles live in {@code t_kb_source_mapping}, kb-rag-server sends the
 * body with every request and the files bundled here are only seeds and defaults. The name is still
 * carried alongside, because it is what identifies the profile in this service's log lines - and what
 * gets resolved locally when the caller has no body for it.
 *
 * <p>YAML is read through SnakeYAML's {@code SafeConstructor}, the counterpart of
 * {@code yaml.safe_load}: a mapping profile is data arriving from a request, and a constructor that
 * can instantiate arbitrary classes named in that data is a remote code execution primitive, not a
 * convenience.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class MappingProfileLoader {

    private static final String MAPPINGS_CLASSPATH_PREFIX = "mappings/";
    private static final String MAPPINGS_SUFFIX = ".yml";
    private static final String TXT_SECTION = "txt";
    private static final String TXT_PATTERNS_KEY = "patterns";
    private static final String TXT_PATTERN_NAME_KEY = "name";
    private static final String TXT_PATTERN_REGEX_KEY = "regex";
    private static final String HTML_SECTION = "html";

    /** A txt: template must capture at least these; {@code content} is optional. */
    private static final List<String> TXT_REQUIRED_GROUPS =
            List.of(TxtLinePattern.GROUP_SEND_TIME, TxtLinePattern.GROUP_SENDER);

    /**
     * Loads a mapping profile.
     *
     * @param profileName profile name, also this profile's display name in logs
     * @param profileYaml full YAML body; when non-blank it takes priority over the local file
     * @return the loaded profile
     * @throws ChatMappingException when the YAML is invalid or the named profile does not exist
     */
    public MappingProfile load(String profileName, String profileYaml) {
        if (!Whitespace.isBlank(profileYaml)) {
            Map<String, Object> raw;
            try {
                raw = parseYaml(profileYaml);
            } catch (YAMLException | ClassCastException ex) {
                log.error("chat mapping profile_yaml invalid, errorCode={}, profile={}",
                        ErrorCode.PARSE_FAILED, profileName);
                throw new ChatMappingException("profile_yaml for '" + profileName
                        + "' is not valid yaml: " + ex.getMessage(), ex);
            }
            String displayName = profileName == null || profileName.isBlank() ? "inline" : profileName;
            return fromRaw(displayName, raw);
        }

        ClassPathResource resource = new ClassPathResource(
                MAPPINGS_CLASSPATH_PREFIX + profileName + MAPPINGS_SUFFIX);
        if (!resource.exists()) {
            log.error("chat mapping profile not found, errorCode={}, profile={}",
                    ErrorCode.PARSE_FAILED, profileName);
            throw new ChatMappingException("unknown mapping_profile '" + profileName + "'");
        }
        try (InputStream in = resource.getInputStream()) {
            return fromRaw(profileName, parseYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | YAMLException | ClassCastException ex) {
            log.error("chat mapping profile unreadable, errorCode={}, profile={}",
                    ErrorCode.PARSE_FAILED, profileName);
            throw new ChatMappingException(
                    "mapping_profile '" + profileName + "' could not be read: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String yamlText) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded = yaml.load(yamlText);
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map)) {
            throw new ClassCastException("a mapping profile must be a yaml mapping at the top level");
        }
        return (Map<String, Object>) loaded;
    }

    private MappingProfile fromRaw(String name, Map<String, Object> raw) {
        Map<String, List<String>> candidates = new HashMap<>();
        for (String field : MappingProfile.TARGET_FIELDS) {
            candidates.put(field, stringList(raw.get(field)));
        }
        return new MappingProfile(name, candidates, parseTxtPatterns(name, raw.get(TXT_SECTION)),
                parseHtmlSelectors(raw.get(HTML_SECTION)));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * Compiles the {@code txt:} section's patterns eagerly.
     *
     * <p>A regex typo is a profile-loading concern, so it fails while loading the profile rather than
     * on the first line of the first request that happens to reach it - and the message then names the
     * pattern, which a failure buried in the adapter could not.
     */
    private static List<TxtLinePattern> parseTxtPatterns(String profileName, Object txtSection) {
        if (!(txtSection instanceof Map<?, ?> section)) {
            return List.of();
        }
        Object entries = section.get(TXT_PATTERNS_KEY);
        if (!(entries instanceof List<?> list)) {
            return List.of();
        }
        List<TxtLinePattern> patterns = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> patternMap)) {
                continue;
            }
            Object rawName = patternMap.get(TXT_PATTERN_NAME_KEY);
            String patternName = rawName == null ? "pattern_" + (patterns.size() + 1) : String.valueOf(rawName);
            Object rawRegex = patternMap.get(TXT_PATTERN_REGEX_KEY);
            if (rawRegex == null || String.valueOf(rawRegex).isEmpty()) {
                throw new ChatMappingException("mapping profile '" + profileName + "' txt: pattern '"
                        + patternName + "' is missing 'regex'");
            }
            PythonRegexTranslator.TranslatedPattern translated;
            try {
                translated = PythonRegexTranslator.translate(String.valueOf(rawRegex));
            } catch (PatternSyntaxException ex) {
                throw new ChatMappingException("mapping profile '" + profileName + "' txt: pattern '"
                        + patternName + "' has invalid regex: " + ex.getMessage(), ex);
            }
            List<String> missing = new ArrayList<>();
            for (String required : TXT_REQUIRED_GROUPS) {
                if (!translated.hasGroup(required)) {
                    missing.add(required);
                }
            }
            if (!missing.isEmpty()) {
                throw new ChatMappingException("mapping profile '" + profileName + "' txt: pattern '"
                        + patternName + "' is missing required named group(s) " + missing);
            }
            patterns.add(new TxtLinePattern(patternName, translated));
        }
        return patterns;
    }

    private static Map<String, String> parseHtmlSelectors(Object htmlSection) {
        if (!(htmlSection instanceof Map<?, ?> section)) {
            return Map.of();
        }
        Map<String, String> selectors = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : section.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String value = String.valueOf(entry.getValue());
            if (!value.isEmpty()) {
                selectors.put(String.valueOf(entry.getKey()), value);
            }
        }
        return selectors;
    }
}
