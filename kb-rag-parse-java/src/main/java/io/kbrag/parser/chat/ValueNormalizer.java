package io.kbrag.parser.chat;

import io.kbrag.parser.support.Whitespace;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Value normalization for raw chat-log cell values (M3-CONTRACTS.md §2.2): boolean coercion for
 * {@code is_self}, epoch-millisecond normalization for {@code send_time}, and {@code msg_type}
 * bucketing.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ValueNormalizer {

    private static final Set<String> TRUE_TOKENS = Set.of("1", "true", "yes", "y", "是", "t");

    /**
     * Below this a numeric timestamp is epoch seconds; at or above, epoch milliseconds. The two ranges
     * never overlap in practice: 10^11 seconds is the year ~5138, above any realistic seconds value,
     * and 10^11 milliseconds is 1973-03-03, below any realistic value for a chat export.
     */
    private static final double SECONDS_MS_BOUNDARY = 1e11;

    private static final int MILLIS_PER_SECOND = 1000;

    /**
     * Accepts exactly what Python's {@code float()} accepts of a plain number, and nothing more.
     * Java's {@code Double.parseDouble} additionally takes hex literals and {@code d}/{@code f}
     * suffixes, which would make this method claim to understand timestamps it has no business
     * reading.
     */
    private static final Pattern NUMERIC_PATTERN =
            Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /** Common string timestamp formats, tried in order; the first that parses wins. */
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

    private static final DateTimeFormatter DATE_ONLY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final List<String> VOICE_TOKENS = List.of("voice", "语音", "audio");
    private static final List<String> VIDEO_TOKENS = List.of("video", "视频");
    private static final List<String> IMAGE_TOKENS = List.of("image", "图片", "照片", "picture", "photo");
    private static final List<String> TEXT_TOKENS = List.of("text", "文本", "文字");

    /**
     * WeChat/MemoTrace-style numeric type codes. These are the widely documented public conventions and
     * have not been validated against a real export sample yet - recalibrate once one is available
     * (M3-CONTRACTS.md §2.2 "⚠️ 真实样例待验证").
     */
    private static final Set<Integer> NUMERIC_VOICE_CODES = Set.of(34);
    private static final Set<Integer> NUMERIC_VIDEO_CODES = Set.of(43, 62);
    private static final Set<Integer> NUMERIC_IMAGE_CODES = Set.of(3);
    private static final Set<Integer> NUMERIC_TEXT_CODES = Set.of(1);

    private ValueNormalizer() {
    }

    /**
     * @param rawValue the source cell value
     * @return best-effort boolean coercion; anything unrecognized is false
     */
    public static boolean coerceIsSelf(Object rawValue) {
        if (rawValue == null) {
            return false;
        }
        return TRUE_TOKENS.contains(Whitespace.strip(stringify(rawValue)).toLowerCase(Locale.ROOT));
    }

    /**
     * Normalizes a raw {@code send_time} value to epoch milliseconds.
     *
     * <p>Accepts epoch seconds, epoch milliseconds, a native date value (as an xlsx date-formatted
     * cell produces), or one of a handful of common string formats.
     *
     * @param rawValue the source cell value
     * @return epoch milliseconds, or null when the value is missing or matches no supported
     *         representation - the caller then counts that message as skipped
     */
    public static Long parseSendTimeMs(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (rawValue instanceof Date date) {
            return date.getTime();
        }
        if (rawValue instanceof Instant instant) {
            return instant.toEpochMilli();
        }

        String text = Whitespace.strip(stringify(rawValue));
        if (text.isEmpty()) {
            return null;
        }

        Double numeric = tryParseNumber(text);
        if (numeric != null) {
            return numeric >= SECONDS_MS_BOUNDARY
                    ? (long) (double) numeric
                    : (long) (numeric * MILLIS_PER_SECOND);
        }

        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, format)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ex) {
                // Try the next format; exhausting them all is what "unparseable" means here.
            }
        }
        try {
            return LocalDate.parse(text, DATE_ONLY_FORMAT)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * Buckets a raw {@code msg_type} source value.
     *
     * <p>A missing or blank source value defaults to {@code text}: a chat export with no type column at
     * all holds plain text messages, not unclassifiable ones.
     *
     * @param rawValue the source cell value
     * @return one of {@code text|image|voice|video|other}
     */
    public static String classifyMsgType(Object rawValue) {
        if (rawValue == null) {
            return MsgType.TEXT;
        }
        String text = Whitespace.strip(stringify(rawValue));
        if (text.isEmpty()) {
            return MsgType.TEXT;
        }

        String lowered = text.toLowerCase(Locale.ROOT);
        if (anyTokenMatches(lowered, VOICE_TOKENS)) {
            return MsgType.VOICE;
        }
        if (anyTokenMatches(lowered, VIDEO_TOKENS)) {
            return MsgType.VIDEO;
        }
        if (anyTokenMatches(lowered, IMAGE_TOKENS)) {
            return MsgType.IMAGE;
        }
        if (anyTokenMatches(lowered, TEXT_TOKENS)) {
            return MsgType.TEXT;
        }

        Integer numeric = tryParseInt(text);
        if (numeric != null) {
            if (NUMERIC_VOICE_CODES.contains(numeric)) {
                return MsgType.VOICE;
            }
            if (NUMERIC_VIDEO_CODES.contains(numeric)) {
                return MsgType.VIDEO;
            }
            if (NUMERIC_IMAGE_CODES.contains(numeric)) {
                return MsgType.IMAGE;
            }
            if (NUMERIC_TEXT_CODES.contains(numeric)) {
                return MsgType.TEXT;
            }
        }
        return MsgType.OTHER;
    }

    /**
     * Renders a raw cell value as text without letting a numeric cell surface as {@code 30.0}, which is
     * what a bare {@code String.valueOf} would do to every number POI hands back as a double.
     *
     * @param rawValue the source cell value
     * @return its string form
     */
    public static String stringify(Object rawValue) {
        if (rawValue == null) {
            return "";
        }
        if (rawValue instanceof Double doubleValue) {
            return io.kbrag.parser.support.NumberFormatting.formatNumeric(doubleValue);
        }
        if (rawValue instanceof Boolean booleanValue) {
            // Python renders a bool as "True"/"False".
            return booleanValue ? "True" : "False";
        }
        return String.valueOf(rawValue);
    }

    /**
     * True when the token occurs as a whole ASCII word, or as a plain substring for a non-ASCII (CJK)
     * token where word boundaries do not apply. Avoids false positives like "text" inside "context"
     * while still matching a compound Chinese label such as "语音消息".
     */
    private static boolean anyTokenMatches(String loweredText, List<String> tokens) {
        for (String token : tokens) {
            if (isAscii(token)) {
                if (Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(loweredText).find()) {
                    return true;
                }
            } else if (loweredText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String token) {
        return token.chars().allMatch(ch -> ch < 128);
    }

    private static Double tryParseNumber(String text) {
        if (!NUMERIC_PATTERN.matcher(text).matches()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer tryParseInt(String text) {
        Double numeric = tryParseNumber(text);
        return numeric == null ? null : (int) (double) numeric;
    }
}
