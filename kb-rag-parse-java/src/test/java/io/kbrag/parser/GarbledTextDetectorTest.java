package io.kbrag.parser;

import io.kbrag.parser.parser.GarbledTextDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The garbled text-layer heuristic (M3-CONTRACTS.md §2.1 扫描页判定 extension).
 *
 * @author owlzhangfq@gmail.com
 */
class GarbledTextDetectorTest {

    private static final int DEFAULT_RATIO_PCT = 50;

    /**
     * Representative of a broken ToUnicode extraction of a Chinese document: mostly Myanmar-block and
     * box-drawing glyphs, with a few surviving ASCII digits and percent signs that were
     * standard-encoded in the original font.
     */
    static final String GARBLED_TEXT =
            "ဢၽာ ▓▓▓ ၥၦၧ 5551 ▓ ၬၭၮ 0.3% ▓▓ ၯၰၱၲၳ ၴၵၶၷ ▓▓ ၸၹၺ 365 ၻၼၽ ▓▓▓ ၾၿ";

    @Test
    void detectsWrongCodepointGlyphSoup() {
        assertTrue(GarbledTextDetector.isGarbled(GARBLED_TEXT, DEFAULT_RATIO_PCT));
    }

    @Test
    void acceptsNormalTextAndEdgeInputs() {
        // Normal Chinese (with fullwidth and CJK punctuation) and English pages must never be
        // misclassified, nor must empty or whitespace-only input.
        assertFalse(GarbledTextDetector.isGarbled(
                "京东云工厂架构文档：容量 24 核，费率 0.3%，期限 365 天。", DEFAULT_RATIO_PCT));
        assertFalse(GarbledTextDetector.isGarbled(
                "Hello kb-rag PDF, this page has a normal text layer.", DEFAULT_RATIO_PCT));
        assertFalse(GarbledTextDetector.isGarbled("", DEFAULT_RATIO_PCT));
        assertFalse(GarbledTextDetector.isGarbled("   \n\t ", DEFAULT_RATIO_PCT));
    }

    @Test
    void acceptsJapaneseKana() {
        assertFalse(GarbledTextDetector.isGarbled(
                "これはテスト文書です。ページの本文はここにあります。", DEFAULT_RATIO_PCT));
    }

    @Test
    void thresholdIsConfigurable() {
        // At 0% nothing is ever garbled - the escape hatch for a corpus this heuristic misjudges.
        assertFalse(GarbledTextDetector.isGarbled(GARBLED_TEXT, 0));
    }
}
