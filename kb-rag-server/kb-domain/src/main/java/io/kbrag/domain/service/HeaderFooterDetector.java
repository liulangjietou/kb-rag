package io.kbrag.domain.service;

import io.kbrag.domain.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects running headers and footers by looking for lines that repeat across pages.
 *
 * <p>Position matters as much as repetition. A phrase that appears on every page in the body is a
 * legitimate recurring sentence; the same phrase appearing on every page as the first or last line is
 * furniture. Only the outer lines of a page are candidates, which is what keeps the detector from
 * eating a heading that happens to be reused.
 *
 * <p>A single page document has no repetition to observe, so nothing is ever detected for it. That is
 * deliberate: guessing from one sample would delete real content on the strength of no evidence.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class HeaderFooterDetector {

    /** Number of leading and trailing lines of a page that may be furniture. */
    private static final int EDGE_LINES = 2;

    /** Share of pages a line has to appear on before it is treated as furniture. */
    private static final double DEFAULT_RATIO_THRESHOLD = 0.6d;

    /** Below this number of pages there is not enough evidence to call anything a header. */
    private static final int MIN_PAGES = 2;

    /** A line longer than this is body text that happens to sit at a page edge. */
    private static final int MAX_LINE_LENGTH = 120;

    /**
     * Collects the lines to remove from the whole document.
     *
     * @param pages per page text
     * @return normalised furniture lines, empty when there is not enough evidence
     */
    public Set<String> detect(List<ParsedDocument.ParsedPage> pages) {
        return detect(pages, DEFAULT_RATIO_THRESHOLD);
    }

    /**
     * Collects the lines to remove using an explicit threshold.
     *
     * @param pages          per page text
     * @param ratioThreshold share of pages a line has to appear on, between 0 exclusive and 1 inclusive
     * @return normalised furniture lines, empty when there is not enough evidence
     */
    public Set<String> detect(List<ParsedDocument.ParsedPage> pages, double ratioThreshold) {
        if (CollectionUtils.isEmpty(pages) || pages.size() < MIN_PAGES) {
            return Set.of();
        }
        Map<String, Set<Integer>> pagesByLine = new HashMap<>();
        for (int index = 0; index < pages.size(); index++) {
            for (String candidate : edgeLines(pages.get(index).getText())) {
                pagesByLine.computeIfAbsent(candidate, line -> new HashSet<>()).add(index);
            }
        }
        int required = (int) Math.ceil(pages.size() * ratioThreshold);
        Set<String> furniture = new LinkedHashSet<>();
        for (Map.Entry<String, Set<Integer>> entry : pagesByLine.entrySet()) {
            if (entry.getValue().size() >= required) {
                furniture.add(entry.getKey());
            }
        }
        if (!furniture.isEmpty()) {
            log.info("header footer candidates detected, pages={}, requiredPages={}, lines={}",
                    pages.size(), required, furniture.size());
        }
        return furniture;
    }

    /**
     * Extracts the candidate lines of one page.
     *
     * @param text page text
     * @return normalised leading and trailing lines
     */
    private List<String> edgeLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String normalised = normalise(line);
            if (!normalised.isEmpty() && normalised.length() <= MAX_LINE_LENGTH) {
                lines.add(normalised);
            }
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(EDGE_LINES, lines.size()); i++) {
            candidates.add(lines.get(i));
        }
        for (int i = Math.max(0, lines.size() - EDGE_LINES); i < lines.size(); i++) {
            candidates.add(lines.get(i));
        }
        return new ArrayList<>(candidates);
    }

    /**
     * Normalises a line so the same header is recognised across pages.
     *
     * <p>Whitespace is collapsed and digits are dropped, because a page number is the one part of a
     * footer that changes on every page and comparing the raw text would never find a match.
     *
     * @param line raw line
     * @return comparable form
     */
    public String normalise(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceAll("\\d+", "").replaceAll("\\s+", " ").trim();
    }
}
