package io.kbrag.parser.chat;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.error.ChatMappingException;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.model.ChatMessage;
import io.kbrag.parser.support.Whitespace;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * TXT line-template adapter for chat log exports (M8-CONTRACTS.md §0.1).
 *
 * <p>A TXT export is read line by line against an ordered list of line-header templates, each carrying
 * the named captures {@code send_time}/{@code sender} and, optionally, {@code content} - present only
 * for templates whose message body can start on the same line as its header. Two templates ship
 * built-in (see {@code resources/mappings/liuhen_txt.yml}): 留痕/MemoTrace, whose header sits on its own
 * line, and the WeChat PC client's, whose body may be inline.
 *
 * <p>A line matching no template is continuation content of the current message - which is how a
 * multi-line message body survives - unless no message has started yet, in which case it counts toward
 * the unmatched-line ratio. That distinction is the whole design of the fast-fail below: a file whose
 * lines mostly match nothing almost certainly means the wrong template was picked, and answering with a
 * near-empty session would hide that; but a legitimate multi-line body must never be able to trip the
 * same wire. So the numerator counts only lines that arrived before any message had begun, and the
 * denominator counts only non-blank lines.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class TxtChatAdapter {

    private TxtChatAdapter() {
    }

    /**
     * What one TXT export produced.
     *
     * @param messages     the parsed messages, in file order
     * @param skippedOther count of headers that matched structurally but carried an unparseable time
     */
    public record TxtParseResult(List<ChatMessage> messages, int skippedOther) {
    }

    /**
     * @param text      the decoded export
     * @param patterns  the profile's {@code txt:} templates
     * @param sessionId session id, used as the message id prefix
     * @return the parsed messages and the skip count
     * @throws ChatMappingException when no templates are configured, or too few lines match one
     */
    public static TxtParseResult parse(String text, List<TxtLinePattern> patterns, String sessionId) {
        if (patterns.isEmpty()) {
            log.error("txt parse failed, errorCode={}, reason=no_patterns_configured", ErrorCode.PARSE_FAILED);
            throw new ChatMappingException("mapping profile has no 'txt:' line patterns configured");
        }

        List<ChatMessage> messages = new ArrayList<>();
        PendingMessage current = null;
        int skippedOther = 0;
        int consideredLines = 0;
        int unmatchedLines = 0;

        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (Whitespace.isBlank(line)) {
                continue;  // blank lines are structural separators only, never counted
            }
            consideredLines++;

            HeaderMatch header = matchHeader(line, patterns);
            if (header == null) {
                if (current != null) {
                    current.contentLines.add(line);
                } else {
                    unmatchedLines++;
                }
                continue;
            }

            Long sendTimeMs = ValueNormalizer.parseSendTimeMs(
                    header.pattern.group(header.matcher, TxtLinePattern.GROUP_SEND_TIME));
            if (sendTimeMs == null) {
                // The header regex matched but its captured timestamp does not parse: a per-message
                // data problem, not a template mismatch, so it does not count toward the unmatched
                // ratio. Mirrors the csv/xlsx skipped.other bucket.
                log.info("txt line skipped, reason=unparseable_send_time");
                skippedOther++;
                continue;
            }

            flush(current, messages, sessionId);
            String inlineContent = header.pattern.group(header.matcher, TxtLinePattern.GROUP_CONTENT);
            current = new PendingMessage(
                    trimToEmpty(header.pattern.group(header.matcher, TxtLinePattern.GROUP_SENDER)),
                    sendTimeMs);
            if (inlineContent != null && !Whitespace.isBlank(inlineContent)) {
                current.contentLines.add(inlineContent);
            }
        }
        flush(current, messages, sessionId);

        if (consideredLines > 0) {
            double unmatchedRatio = (double) unmatchedLines / consideredLines;
            if (unmatchedRatio > ParserConstants.TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD) {
                log.error("txt parse failed, errorCode={}, reason=unmatched_line_ratio_exceeded, "
                                + "unmatchedLines={}, consideredLines={}, threshold={}",
                        ErrorCode.PARSE_FAILED, unmatchedLines, consideredLines,
                        ParserConstants.TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD);
                throw new ChatMappingException(unmatchedLines + "/" + consideredLines + " lines ("
                        + Math.round(unmatchedRatio * 100) + "%) matched no configured txt: line template "
                        + "(threshold " + Math.round(
                        ParserConstants.TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD * 100) + "%); "
                        + "check the file is a supported TXT chat export format, or supply a custom "
                        + "'txt:' pattern via mapping_profile/profile_yaml");
            }
        }

        return new TxtParseResult(messages, skippedOther);
    }

    /** A message whose header has been read and whose body may still be accumulating. */
    private static final class PendingMessage {
        private final String sender;
        private final long sendTime;
        private final List<String> contentLines = new ArrayList<>();

        private PendingMessage(String sender, long sendTime) {
            this.sender = sender;
            this.sendTime = sendTime;
        }
    }

    private static void flush(PendingMessage pending, List<ChatMessage> messages, String sessionId) {
        if (pending == null) {
            return;
        }
        messages.add(ChatMessage.builder()
                .msgId(sessionId + "-" + (messages.size() + 1))
                .sender(pending.sender)
                .isSelf(false)
                .sendTime(pending.sendTime)
                .msgType(MsgType.TEXT)
                .content(Whitespace.strip(String.join("\n", pending.contentLines)))
                .build());
    }

    /** A template and the matcher it produced, so the caller can read captures by profile name. */
    private record HeaderMatch(TxtLinePattern pattern, Matcher matcher) {
    }

    private static HeaderMatch matchHeader(String line, List<TxtLinePattern> patterns) {
        for (TxtLinePattern pattern : patterns) {
            Matcher matcher = pattern.matchHeader(line);
            if (matcher != null) {
                return new HeaderMatch(pattern, matcher);
            }
        }
        return null;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : Whitespace.strip(value);
    }
}
