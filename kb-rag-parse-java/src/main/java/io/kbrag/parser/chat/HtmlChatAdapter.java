package io.kbrag.parser.chat;

import io.kbrag.parser.error.ChatMappingException;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.model.ChatMessage;
import io.kbrag.parser.model.ChatSkippedStats;
import io.kbrag.parser.support.Whitespace;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTML DOM adapter for chat log exports (M8-CONTRACTS.md §0.2).
 *
 * <p>Security posture, per requirement §4.2:
 *
 * <ul>
 *   <li>{@code <script>}/{@code <style>} bodies are dropped before anything is read, so they are never
 *       surfaced as message text - and nothing here executes anything in any case.
 *   <li>No remote resource is ever fetched. An {@code <img>} node is inspected only for
 *       <i>presence</i>, to emit the fixed {@code [IMAGE]} placeholder; its {@code src} is never read
 *       or dereferenced. That is true of every parser in this service - no HTTP client is implemented
 *       anywhere - but it is called out here because HTML is the one format whose content invites the
 *       mistake.
 *   <li>jsoup is used purely as a tokenizer over bytes already in hand: the base URI is empty and
 *       {@code Jsoup.connect} is never called.
 * </ul>
 *
 * <p>The Python service hand-rolls a minimal selector engine over the stdlib tokenizer, supporting
 * {@code tag}, {@code .class}, {@code #id} and {@code tag.class} - enough for the built-in template
 * without taking on bs4. jsoup brings a full CSS selector engine, which is a strict superset: every
 * profile written for the Python service works unchanged here, and a profile written against this one
 * may use a selector the Python service would reject. That direction of incompatibility is worth
 * knowing about when a profile is authored here and deployed there.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class HtmlChatAdapter {

    /** Selectors the message-node schema cannot work without. */
    private static final List<String> REQUIRED_SELECTORS = List.of("message", "sender", "time", "content");

    private static final String SELECTOR_MESSAGE = "message";
    private static final String SELECTOR_SENDER = "sender";
    private static final String SELECTOR_TIME = "time";
    private static final String SELECTOR_CONTENT = "content";
    private static final String SELECTOR_IMAGE = "image";
    private static final String SELECTOR_VOICE = "voice";
    private static final String SELECTOR_VIDEO = "video";

    private static final String DEFAULT_IMAGE_SELECTOR = "img";
    private static final String DEFAULT_VOICE_SELECTOR = "audio";
    private static final String DEFAULT_VIDEO_SELECTOR = "video";

    /** Element bodies never surfaced as message text (M8-CONTRACTS.md §0.2 "剥离 script/style"). */
    private static final String STRIPPED_ELEMENTS = "script, style";

    private HtmlChatAdapter() {
    }

    /**
     * What one HTML export produced.
     *
     * @param messages the parsed messages, in document order
     * @param skipped  per-reason skip counters
     */
    public record HtmlParseResult(List<ChatMessage> messages, ChatSkippedStats skipped) {
    }

    /**
     * @param htmlText  the decoded export
     * @param selectors the profile's {@code html:} section
     * @param sessionId session id, used as the message id prefix
     * @return the parsed messages and the skip counters
     * @throws ChatMappingException when a required selector is missing, a selector is malformed, or the
     *                              {@code message} selector matches no node at all
     */
    public static HtmlParseResult parse(String htmlText, Map<String, String> selectors, String sessionId) {
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_SELECTORS) {
            String value = selectors.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            log.error("html parse failed, errorCode={}, reason=missing_selectors, missing={}",
                    ErrorCode.PARSE_FAILED, missing);
            throw new ChatMappingException(
                    "mapping profile 'html:' section is missing required selector(s): " + missing);
        }

        Document document = Jsoup.parse(htmlText, "", Parser.htmlParser());
        document.select(STRIPPED_ELEMENTS).remove();

        String messageSelector = selectors.get(SELECTOR_MESSAGE);
        Elements messageNodes = select(document, messageSelector);
        if (messageNodes.isEmpty()) {
            log.error("html parse failed, errorCode={}, reason=message_selector_matched_nothing, selector={}",
                    ErrorCode.PARSE_FAILED, messageSelector);
            throw new ChatMappingException("html: selector message='" + messageSelector
                    + "' matched no nodes; check the file is a supported HTML chat export format, or "
                    + "supply a custom 'html:' selector via mapping_profile/profile_yaml");
        }

        String voiceSelector = selectors.getOrDefault(SELECTOR_VOICE, DEFAULT_VOICE_SELECTOR);
        String videoSelector = selectors.getOrDefault(SELECTOR_VIDEO, DEFAULT_VIDEO_SELECTOR);
        String imageSelector = selectors.getOrDefault(SELECTOR_IMAGE, DEFAULT_IMAGE_SELECTOR);

        List<ChatMessage> messages = new ArrayList<>();
        ChatSkippedStats skipped = new ChatSkippedStats();

        for (Element node : messageNodes) {
            if (containsDescendant(node, voiceSelector)) {
                skipped.incrementVoice();
                log.info("html message skipped, reason=msg_type_excluded, msgType=voice");
                continue;
            }
            if (containsDescendant(node, videoSelector)) {
                skipped.incrementVideo();
                log.info("html message skipped, reason=msg_type_excluded, msgType=video");
                continue;
            }

            String sendTimeText = textOf(firstDescendant(node, selectors.get(SELECTOR_TIME)));
            Long sendTimeMs = sendTimeText.isEmpty() ? null : ValueNormalizer.parseSendTimeMs(sendTimeText);
            if (sendTimeMs == null) {
                skipped.incrementOther();
                log.info("html message skipped, reason=unparseable_send_time");
                continue;
            }

            boolean hasImage = containsDescendant(node, imageSelector);
            messages.add(ChatMessage.builder()
                    .msgId(sessionId + "-" + (messages.size() + 1))
                    .sender(textOf(firstDescendant(node, selectors.get(SELECTOR_SENDER))))
                    .isSelf(false)
                    .sendTime(sendTimeMs)
                    .msgType(hasImage ? MsgType.IMAGE : MsgType.TEXT)
                    .content(hasImage
                            ? MsgType.IMAGE_PLACEHOLDER_TEXT
                            : textOf(firstDescendant(node, selectors.get(SELECTOR_CONTENT))))
                    .build());
        }

        return new HtmlParseResult(messages, skipped);
    }

    private static Elements select(Element root, String selector) {
        try {
            return root.select(selector);
        } catch (Selector.SelectorParseException | IllegalArgumentException ex) {
            log.error("html parse failed, errorCode={}, reason=invalid_selector, selector={}",
                    ErrorCode.PARSE_FAILED, selector);
            throw new ChatMappingException("invalid html: selector '" + selector + "'", ex);
        }
    }

    /**
     * Finds the first matching <i>descendant</i>.
     *
     * <p>jsoup's own {@code select} matches the element itself as well; excluding it keeps a selector
     * that also describes the message node - {@code image: div.message}, say - from making every
     * message look like it contains one.
     */
    private static Element firstDescendant(Element node, String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        for (Element candidate : select(node, selector)) {
            if (candidate != node) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsDescendant(Element node, String selector) {
        return firstDescendant(node, selector) != null;
    }

    /** Concatenates the node's text as written, then trims - matching the Python adapter's {@code _text_of}. */
    private static String textOf(Element node) {
        return node == null ? "" : Whitespace.strip(node.wholeText());
    }
}
