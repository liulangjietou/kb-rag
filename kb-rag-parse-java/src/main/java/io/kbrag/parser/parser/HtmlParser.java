package io.kbrag.parser.parser;

import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ParseException;
import io.kbrag.parser.model.PageContent;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.support.TextDecoder;
import io.kbrag.parser.support.Whitespace;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * General HTML page parser for {@code .html} / {@code .htm} (M12-CONTRACTS.md §2).
 *
 * <p>Performs no network I/O whatsoever: external images, scripts and stylesheets referenced by the
 * page are dropped, never fetched. The outbound-request ban of requirement §4.2 applies to web pages
 * exactly as it does to files, and the SSRF surface of URL import lives entirely in kb-rag-server's
 * fetcher, not here. jsoup is used purely as a tokenizer over bytes already in hand -
 * {@code Jsoup.connect} is never called, and the base URI is deliberately empty so nothing in the
 * document can be resolved to a fetchable location.
 *
 * <p>The Python service reaches the same output with the stdlib's event-based {@code html.parser},
 * to avoid taking on bs4. The JDK ships no HTML parser at all, so a library is unavoidable here; the
 * traversal below reproduces that event stream over the parsed tree - a skipped element takes its
 * whole subtree with it, a heading or block boundary flushes the inline buffer into a line, and
 * everything else flows into the surrounding block.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class HtmlParser implements DocumentParser {

    /** Content inside these is invisible on the rendered page, so it is noise for retrieval. */
    private static final Set<String> SKIPPED_TAGS = Set.of("script", "style", "noscript", "template");

    /**
     * Elements that end the current line of text. Inline tags (a, span, b, em...) are deliberately
     * absent: their text flows into the surrounding block rather than breaking it.
     */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "section", "article", "aside", "header", "footer", "main",
            "nav", "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody",
            "tr", "blockquote", "pre", "figure", "figcaption", "br", "hr");

    private static final Map<String, Integer> HEADING_LEVELS = Map.of(
            "h1", 1, "h2", 2, "h3", 3, "h4", 4, "h5", 5, "h6", 6);

    private static final String TITLE_TAG = "title";
    private static final String LINE_SEPARATOR = "\n\n";

    @Override
    public ParseData parse(byte[] content, String filename) {
        String markdown;
        try {
            Document document = Jsoup.parse(TextDecoder.decode(content), "", Parser.htmlParser());
            markdown = new MarkdownExtractor().extract(document);
        } catch (RuntimeException ex) {
            log.error("html parse failed, errorCode={}, filename={}", ErrorCode.PARSE_FAILED, filename);
            throw new ParseException("failed to parse html file: " + ex.getMessage(), ex);
        }

        return ParseData.builder()
                .markdown(markdown)
                .pages(List.of(PageContent.builder().pageNo(1).text(markdown).markdown(markdown).build()))
                .build();
    }

    /**
     * Turns a parsed document into markdown lines.
     *
     * <p>One instance handles one document; the buffers below are its traversal state.
     */
    private static final class MarkdownExtractor {

        private final List<String> lines = new ArrayList<>();
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder titleParts = new StringBuilder();
        private int headingLevel;

        String extract(Document document) {
            visit(document);
            flush();
            List<String> result = new ArrayList<>(lines);
            String title = collapseWhitespace(titleParts.toString());
            if (!title.isEmpty()) {
                result.add(0, "# " + title);
            }
            // Joining non-empty lines with a blank line yields clean markdown paragraphs and
            // collapses whatever vertical whitespace the page happened to carry.
            return String.join(LINE_SEPARATOR, result);
        }

        private void visit(Node node) {
            if (node instanceof TextNode textNode) {
                buffer.append(textNode.getWholeText());
                return;
            }
            if (!(node instanceof Element element)) {
                // Comments, doctypes and data nodes carry nothing a reader ever saw.
                return;
            }
            String tag = element.normalName().toLowerCase(Locale.ROOT);
            if (SKIPPED_TAGS.contains(tag)) {
                return;
            }
            if (TITLE_TAG.equals(tag)) {
                titleParts.append(element.wholeText());
                return;
            }
            Integer level = HEADING_LEVELS.get(tag);
            if (level != null) {
                flush();
                headingLevel = level;
                visitChildren(element);
                flush();
                headingLevel = 0;
                return;
            }
            if (BLOCK_TAGS.contains(tag)) {
                flush();
                visitChildren(element);
                flush();
                return;
            }
            visitChildren(element);
        }

        private void visitChildren(Element element) {
            for (Node child : element.childNodes()) {
                visit(child);
            }
        }

        private void flush() {
            String text = collapseWhitespace(buffer.toString());
            buffer.setLength(0);
            if (text.isEmpty()) {
                return;
            }
            lines.add(headingLevel > 0 ? "#".repeat(headingLevel) + " " + text : text);
        }

        /**
         * Collapses the runs of indentation whitespace HTML sources are full of.
         *
         * <p>Goes through {@link Whitespace} rather than a regex on {@code \s}, because an HTML page
         * is precisely where {@code &nbsp;} lives: Java's {@code \s} does not match {@code U+00A0},
         * so a naive collapse would leave invisible characters in the corpus that the Python
         * implementation strips.
         */
        private static String collapseWhitespace(String text) {
            return Whitespace.collapse(text);
        }
    }
}
