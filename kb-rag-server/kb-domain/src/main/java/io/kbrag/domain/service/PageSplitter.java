package io.kbrag.domain.service;

import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.model.PageRange;
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
 * <p>Not a {@link TextSplitter} because it needs one input more than a flat string: the boundaries
 * {@link PagedContentAssembler} recorded while it assembled the cleaned document. The pipeline
 * therefore hands this strategy both, which is what lets it cut exactly the text every other strategy
 * is cut from - cleaned, masked and with the image proxies already spliced in. Reading the parse
 * artifact's raw page text instead, as this strategy did until the boundaries existed, bypassed all
 * three: personal data reached the index unmasked and no chunk could ever be linked to an image.
 *
 * <p>A format without a page concept - a plain text or an html file - reports no boundaries, so the
 * whole document becomes a single page and the strategy still produces the fixed length behaviour
 * rather than nothing.
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

    /** Page number of the synthetic single page a format without boundaries collapses to. */
    private static final int SINGLE_PAGE_NO = 1;

    private final FixedLengthTextSplitter fixedLengthTextSplitter;
    private final TokenEstimator tokenEstimator;

    /**
     * Cuts the cleaned markdown into one chunk per page.
     *
     * @param markdown   cleaned markdown with the image proxies spliced in
     * @param pageRanges page boundaries inside {@code markdown}, empty makes the whole text one page
     * @param params     split parameters
     * @return ordered chunks, each tagged with its page number
     */
    public List<SplitChunk> split(String markdown, List<PageRange> pageRanges, SplitParams params) {
        List<SplitChunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }
        if (CollectionUtils.isEmpty(pageRanges)) {
            emitPage(SINGLE_PAGE_NO, markdown, params, chunks, 0);
            return chunks;
        }
        int seq = 0;
        for (PageRange range : pageRanges) {
            if (range.getStart() < 0 || range.getEnd() > markdown.length()
                    || range.getStart() >= range.getEnd()) {
                log.info("page range outside the cleaned markdown, pageNo={}, start={}, end={}, length={}",
                        range.getPageNo(), range.getStart(), range.getEnd(), markdown.length());
                continue;
            }
            seq = emitPage(range.getPageNo(), markdown.substring(range.getStart(), range.getEnd()),
                    params, chunks, seq);
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
