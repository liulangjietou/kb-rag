package io.kbrag.parser.chat;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.error.ChatMappingException;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.error.UnsupportedFormatException;
import io.kbrag.parser.model.ChatMessage;
import io.kbrag.parser.model.ChatParseData;
import io.kbrag.parser.model.ChatSession;
import io.kbrag.parser.model.ChatSkippedStats;
import io.kbrag.parser.security.ZipSafetyGuard;
import io.kbrag.parser.support.TextDecoder;
import io.kbrag.parser.support.Whitespace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a csv/xlsx/txt/html chat export into sessions of messages (M3-CONTRACTS.md §2.2, widened by
 * M8-CONTRACTS.md §0.1/§0.2/§0.3).
 *
 * <p>csv/xlsx rows are read generically into {@code {actual header name -> raw value}} maps,
 * independent of which export tool produced the file; a {@link MappingProfile} then resolves each
 * logical field to whichever actual header is present. txt/html have no column-naming ambiguity to
 * resolve - their adapters already produce the normalized fields from a line-header regex or a DOM
 * selector - so they bypass {@link MappingProfile#resolve} entirely and are always modelled as a
 * single session, because a txt/html export is one conversation dump rather than a multi-room table.
 *
 * <p>Onboarding a new tabular export source therefore remains a matter of adding a mapping profile,
 * never touching this class.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogParser {

    /** M8-CONTRACTS.md §0.1/§0.2 widened the whitelist from the original csv/xlsx. */
    public static final Set<String> SUPPORTED_CHAT_FILE_EXTENSIONS = Set.of("csv", "xlsx", "txt", "html");

    /**
     * Built-in default per file_ext, applied only when the caller passes no mapping_profile at all.
     * csv/xlsx keep the original {@code memotrace} default; txt/html get their own, since a
     * column-name profile has no {@code txt:}/{@code html:} section to fall back on.
     */
    private static final Map<String, String> DEFAULT_PROFILE_BY_EXT = Map.of(
            "csv", ParserConstants.DEFAULT_CHAT_MAPPING_PROFILE,
            "xlsx", ParserConstants.DEFAULT_CHAT_MAPPING_PROFILE,
            "txt", "liuhen_txt",
            "html", "liuhen_html");

    private static final String DEFAULT_SESSION_ID = "default";

    /**
     * Message types dropped from the output and only counted (M3-CONTRACTS.md §2.2). Every other
     * bucket, {@code other} included, is kept: an unclassifiable type is still a message somebody sent.
     */
    private static final Set<String> EXCLUDED_MSG_TYPES = Set.of(MsgType.VOICE, MsgType.VIDEO);

    private final MappingProfileLoader profileLoader;

    /**
     * Resolves the built-in default mapping profile for an extension.
     *
     * <p>An unrecognized extension falls back to the csv/xlsx default, because it will fail the
     * file_ext whitelist in {@link #parse} regardless of which profile name was attempted.
     *
     * @param fileExt the requested extension
     * @return the default profile name
     */
    public static String defaultMappingProfileFor(String fileExt) {
        return DEFAULT_PROFILE_BY_EXT.getOrDefault(normalizeExt(fileExt),
                ParserConstants.DEFAULT_CHAT_MAPPING_PROFILE);
    }

    /**
     * @param content        raw export bytes
     * @param filename       original filename; its stem names the session for txt/html
     * @param fileExt        {@code csv|xlsx|txt|html}
     * @param mappingProfile profile name
     * @param profileYaml    full profile YAML body, taking priority over the name when non-blank
     * @return the parsed sessions and skip counters
     */
    public ChatParseData parse(byte[] content, String filename, String fileExt, String mappingProfile,
                               String profileYaml) {
        String normalizedExt = normalizeExt(fileExt);
        if (!SUPPORTED_CHAT_FILE_EXTENSIONS.contains(normalizedExt)) {
            log.error("unsupported chat file_ext, errorCode={}, fileExt={}", ErrorCode.PARSE_FAILED, fileExt);
            throw new UnsupportedFormatException("unsupported chat file_ext '" + fileExt
                    + "', supported: " + SUPPORTED_CHAT_FILE_EXTENSIONS.stream().sorted().toList());
        }
        return switch (normalizedExt) {
            case "txt" -> parseTxtChat(content, filename, mappingProfile, profileYaml);
            case "html" -> parseHtmlChat(content, filename, mappingProfile, profileYaml);
            default -> parseTabularChat(content, normalizedExt, mappingProfile, profileYaml);
        };
    }

    /** A TXT export is one conversation dump - always a single session, named after the file. */
    private ChatParseData parseTxtChat(byte[] content, String filename, String mappingProfile,
                                       String profileYaml) {
        MappingProfile profile = profileLoader.load(mappingProfile, profileYaml);
        String sessionId = sessionIdFromFilename(filename);
        TxtChatAdapter.TxtParseResult result =
                TxtChatAdapter.parse(TextDecoder.decode(content), profile.getTxtPatterns(), sessionId);

        ChatSkippedStats skipped = new ChatSkippedStats();
        skipped.setOther(result.skippedOther());
        return toSingleSession(result.messages(), sessionId, skipped);
    }

    /** Same single-session modelling as TXT. */
    private ChatParseData parseHtmlChat(byte[] content, String filename, String mappingProfile,
                                        String profileYaml) {
        MappingProfile profile = profileLoader.load(mappingProfile, profileYaml);
        String sessionId = sessionIdFromFilename(filename);
        HtmlChatAdapter.HtmlParseResult result =
                HtmlChatAdapter.parse(TextDecoder.decode(content), profile.getHtmlSelectors(), sessionId);
        return toSingleSession(result.messages(), sessionId, result.skipped());
    }

    private static ChatParseData toSingleSession(List<ChatMessage> messages, String sessionId,
                                                 ChatSkippedStats skipped) {
        if (messages.isEmpty()) {
            return ChatParseData.builder().sessions(List.of()).skipped(skipped).build();
        }
        ChatSession session = ChatSession.builder()
                .sessionId(sessionId)
                .sessionName(sessionId)
                .messages(messages)
                .build();
        return ChatParseData.builder().sessions(List.of(session)).skipped(skipped).build();
    }

    private ChatParseData parseTabularChat(byte[] content, String fileExt, String mappingProfile,
                                           String profileYaml) {
        List<Map<String, Object>> rows = "csv".equals(fileExt) ? readCsvRows(content) : readXlsxRows(content);
        if (rows.isEmpty()) {
            return ChatParseData.builder().build();
        }

        MappingProfile profile = profileLoader.load(mappingProfile, profileYaml);
        List<String> header = new ArrayList<>(rows.get(0).keySet());
        Map<String, String> resolved = profile.resolve(header);

        if (resolved.get(MappingProfile.FIELD_CONTENT) == null) {
            log.error("chat mapping missing required column, errorCode={}, profile={}, field=content",
                    ErrorCode.PARSE_FAILED, mappingProfile);
            throw new ChatMappingException("mapping profile '" + mappingProfile
                    + "' could not resolve required column 'content' from header " + header);
        }

        Map<String, ChatSession> sessions = new LinkedHashMap<>();
        ChatSkippedStats skipped = new ChatSkippedStats();

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            if (!hasAnyValue(row)) {
                continue;  // a fully blank row (common xlsx used-range padding) is not a message
            }

            String msgType = ValueNormalizer.classifyMsgType(raw(row, resolved.get(MappingProfile.FIELD_MSG_TYPE)));
            if (EXCLUDED_MSG_TYPES.contains(msgType)) {
                if (MsgType.VOICE.equals(msgType)) {
                    skipped.incrementVoice();
                } else {
                    skipped.incrementVideo();
                }
                log.info("chat message skipped, reason=msg_type_excluded, msgType={}, rowIndex={}",
                        msgType, rowIndex);
                continue;
            }

            Long sendTimeMs = ValueNormalizer.parseSendTimeMs(
                    raw(row, resolved.get(MappingProfile.FIELD_SEND_TIME)));
            if (sendTimeMs == null) {
                skipped.incrementOther();
                log.info("chat message skipped, reason=unparseable_send_time, rowIndex={}", rowIndex);
                continue;
            }

            String sessionId = valueOrDefault(
                    value(row, resolved.get(MappingProfile.FIELD_SESSION_ID)), DEFAULT_SESSION_ID);
            String sessionName = valueOrDefault(
                    value(row, resolved.get(MappingProfile.FIELD_SESSION_NAME)), sessionId);
            ChatMessage message = ChatMessage.builder()
                    .msgId(valueOrDefault(value(row, resolved.get(MappingProfile.FIELD_MSG_ID)),
                            sessionId + "-" + rowIndex))
                    .sender(valueOrDefault(value(row, resolved.get(MappingProfile.FIELD_SENDER)), ""))
                    .isSelf(ValueNormalizer.coerceIsSelf(raw(row, resolved.get(MappingProfile.FIELD_IS_SELF))))
                    .sendTime(sendTimeMs)
                    .msgType(msgType)
                    .content(valueOrDefault(value(row, resolved.get(MappingProfile.FIELD_CONTENT)), ""))
                    .build();

            sessions.computeIfAbsent(sessionId, id -> ChatSession.builder()
                    .sessionId(id)
                    .sessionName(sessionName)
                    .messages(new ArrayList<>())
                    .build()).getMessages().add(message);
        }

        return ChatParseData.builder()
                .sessions(new ArrayList<>(sessions.values()))
                .skipped(skipped)
                .build();
    }

    private static String sessionIdFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return DEFAULT_SESSION_ID;
        }
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = Whitespace.strip(base);
        return base.isEmpty() ? DEFAULT_SESSION_ID : base;
    }

    private static boolean hasAnyValue(Map<String, Object> row) {
        return row.values().stream()
                .anyMatch(value -> value != null && !Whitespace.isBlank(ValueNormalizer.stringify(value)));
    }

    /**
     * @return the original cell value, preserving its type (a date cell stays a date so the time
     *         normalizer can use it directly), or null when the column is unmapped or blank
     */
    private static Object raw(Map<String, Object> row, String column) {
        if (column == null) {
            return null;
        }
        Object value = row.get(column);
        if (value instanceof String text && Whitespace.isBlank(text)) {
            return null;
        }
        return value;
    }

    /** Like {@link #raw}, but stringified and stripped - for text-shaped fields. */
    private static String value(Map<String, Object> row, String column) {
        Object rawValue = raw(row, column);
        if (rawValue == null) {
            return null;
        }
        String text = Whitespace.strip(ValueNormalizer.stringify(rawValue));
        return text.isEmpty() ? null : text;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static List<Map<String, Object>> readCsvRows(byte[] content) {
        String text = TextDecoder.decode(content);
        List<Map<String, Object>> rows = new ArrayList<>();
        // Comma, not a sniffed delimiter: the document csv parser sniffs because an arbitrary
        // spreadsheet export may use ';' or a tab, but a chat export is read against a mapping profile
        // whose candidate column names were written for a comma-separated file. Guessing here would let
        // a mis-sniffed delimiter turn the whole header row into one unmatched column and surface as
        // "content column not found" instead of anything actionable.
        try (CSVParser parser = CSVParser.parse(new StringReader(text), CSVFormat.DEFAULT)) {
            List<String> header = null;
            for (CSVRecord record : parser) {
                if (header == null) {
                    header = new ArrayList<>(record.size());
                    for (String cell : record) {
                        header.add(cell);
                    }
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    // A short row leaves the remaining columns null, as csv.DictReader does.
                    row.put(header.get(i), i < record.size() ? record.get(i) : null);
                }
                rows.add(row);
            }
        } catch (IOException ex) {
            throw new ParseException("failed to read chat csv: " + ex.getMessage(), ex);
        }
        return rows;
    }

    private static List<Map<String, Object>> readXlsxRows(byte[] content) {
        ZipSafetyGuard.ensureZipIsSafe(content);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> header = null;
            for (Row row : sheet) {
                if (header == null) {
                    header = new ArrayList<>();
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        header.add(ValueNormalizer.stringify(cellValue(row.getCell(i))));
                    }
                    continue;
                }
                Map<String, Object> mapped = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    mapped.put(header.get(i), cellValue(row.getCell(i)));
                }
                rows.add(mapped);
            }
            return rows;
        } catch (IOException | RuntimeException ex) {
            throw new ParseException("failed to read chat xlsx: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads a cell as its natural type: a date cell stays a {@link java.time.LocalDateTime} so
     * {@link ValueNormalizer#parseSendTimeMs} can use it without a lossy round trip through text, and a
     * formula cell is read through its cached result rather than being evaluated.
     */
    private static Object cellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue()
                    : (Object) cell.getNumericCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case ERROR, BLANK, _NONE, FORMULA -> null;
        };
    }

    private static String normalizeExt(String fileExt) {
        if (fileExt == null) {
            return "";
        }
        String trimmed = fileExt.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
