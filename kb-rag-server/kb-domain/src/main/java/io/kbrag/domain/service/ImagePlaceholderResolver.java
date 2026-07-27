package io.kbrag.domain.service;

import io.kbrag.domain.model.ProxiedContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the {@code [[IMAGE:id]]} placeholders of a parsed document with the textual proxies of the
 * images.
 *
 * <p><b>Why the proxy goes back where the placeholder was.</b> An image inside a document belongs to the
 * paragraph that introduces it. Appending the descriptions at the end of the document would make the
 * splitter cut them away from that paragraph, and a retrieval hit on the description would return text
 * that never mentions what it is describing. Splicing the proxy in place lets the ordinary splitter do
 * the grouping, and the requirement asks for exactly that.
 *
 * <p>A placeholder with no proxy is deleted rather than left in place: a vision model that was not
 * configured, or that failed, must not leak an internal marker into an answer.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ImagePlaceholderResolver {

    /** Placeholder shape agreed with the parser. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[\\[IMAGE:([A-Za-z0-9_\\-]+)]]");

    /** Label that introduces a textual proxy in the resolved markdown. */
    public static final String PROXY_LABEL = "[图片内容] ";

    private static final String LINE_BREAK = "\n";

    /**
     * Builds the placeholder of an image id, used by the standalone image path and by the tests.
     *
     * @param sourceImageId parser supplied image id
     * @return placeholder text
     */
    public String placeholderOf(String sourceImageId) {
        return "[[IMAGE:" + sourceImageId + "]]";
    }

    /**
     * Resolves every placeholder.
     *
     * @param markdown markdown carrying the placeholders
     * @param proxies  textual proxy per parser supplied image id, insertion ordered
     * @return resolved markdown and the position of every inserted proxy
     */
    public ProxiedContent resolve(String markdown, Map<String, ProxyTarget> proxies) {
        if (markdown == null || markdown.isEmpty()) {
            return new ProxiedContent(markdown == null ? "" : markdown, List.of());
        }
        Map<String, ProxyTarget> targets = proxies == null ? new LinkedHashMap<>() : proxies;
        Matcher matcher = PLACEHOLDER.matcher(markdown);
        StringBuilder resolved = new StringBuilder(markdown.length());
        List<ProxiedContent.ImagePlacement> placements = new ArrayList<>();
        int cursor = 0;
        int dropped = 0;
        while (matcher.find()) {
            resolved.append(markdown, cursor, matcher.start());
            cursor = matcher.end();
            ProxyTarget target = targets.get(matcher.group(1));
            if (target == null || target.textProxy() == null || target.textProxy().isBlank()) {
                dropped++;
                continue;
            }
            String block = LINE_BREAK + PROXY_LABEL + target.textProxy().trim() + LINE_BREAK;
            int start = resolved.length();
            resolved.append(block);
            placements.add(new ProxiedContent.ImagePlacement(
                    target.imageId(), target.objectKey(), start, resolved.length()));
        }
        resolved.append(markdown, cursor, markdown.length());
        if (dropped > 0) {
            log.info("image placeholders dropped without a textual proxy, count={}", dropped);
        }
        return new ProxiedContent(resolved.toString(), placements);
    }

    /**
     * What a placeholder is replaced with.
     *
     * @param imageId   business id of the image asset
     * @param objectKey object storage key of the image binary
     * @param textProxy description and transcription produced by the vision model
     */
    public record ProxyTarget(String imageId, String objectKey, String textProxy) {
    }
}
