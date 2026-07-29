package io.kbrag.domain.service;

import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Page splitting strategy, the M14 contract section 4: cuts the document on the page boundaries the
 * parser reported rather than on anything found in the markdown.
 *
 * <p>Not a {@link TextSplitter} because it does not work off a flat string: page boundaries live in
 * the parse artifact, so the pipeline hands this strategy the {@link ParsedDocument} directly. A
 * format without a page concept - a plain text or an html file - yields a single page holding the
 * whole document, so the strategy always produces at least the fixed length behaviour rather than
 * nothing.
 *
 * <p>Every chunk carries its one based {@link ChunkMetadataKeys#PAGE_NO} so the retrieval side can
 * filter by page. A page whose text alone exceeds the token budget is re-split by
 * {@link FixedLengthTextSplitter}, and each resulting chunk keeps that page number: an over long page
 * is still one page, just several chunks.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "page";

    /** Page number of the synthetic single page a format without pages collapses to. */
    private static final int SINGLE_PAGE_NO = 1;

    private final FixedLengthTextSplitter fixedLengthTextSplitter;
    private final TokenEstimator tokenEstimator;

    /**
     * Cuts the parsed document into one chunk per page, falling back to the whole markdown when the
     * format carried no pages.
     *
     * @param parsed   parse artifact, may be {@code null} when none was readable
     * @param markdown merged markdown, used as the single page of a format without pages
     * @param params   split parameters
     * @return ordered chunks, each tagged with its page number
     */
    public List<SplitChunk> split(ParsedDocument parsed, String markdown, SplitParams params) {
        List<ParsedDocument.ParsedPage> pages = parsed == null ? List.of() : parsed.pagesOrEmpty();
        List<SplitChunk> chunks = new ArrayList<>();
        int seq = 0;
        if (CollectionUtils.isEmpty(pages)) {
            emitPage(SINGLE_PAGE_NO, markdown, params, chunks, seq);
            return chunks;
        }
        for (ParsedDocument.ParsedPage page : pages) {
            seq = emitPage(page.getPageNo(), page.getText(), params, chunks, seq);
        }
        return chunks;
    }

    /**
     * Appends the chunks of one page, re-splitting the page when its text exceeds the token budget.
     *
     * @param pageNo one based page number
     * @param text   page text
     * @param params split parameters
     * @param chunks accumulator the new chunks are appended to
     * @param seq    next chunk sequence number
     * @return sequence number after the appended chunks
     */
    private int emitPage(int pageNo, String text, SplitParams params, List<SplitChunk> chunks, int seq) {
        if (text == null || text.isBlank()) {
            return seq;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.PAGE_NO, String.valueOf(pageNo));
        if (tokenEstimator.estimate(text) <= params.getMaxTokens()) {
            chunks.add(new SplitChunk(seq++, text.trim(), tokenEstimator.estimate(text.trim()), metadata));
            return seq;
        }
        for (SplitChunk piece : fixedLengthTextSplitter.split(text, params)) {
            chunks.add(new SplitChunk(seq++, piece.getContent(), piece.getTokenCount(),
                    new LinkedHashMap<>(metadata)));
        }
        return seq;
    }
}
