package io.kbrag.domain.service;

import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParentChildParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the promise the split fingerprint makes: two configurations that would cut a document
 * differently must never share a digest, because a shared digest is what lets a rebuild be skipped.
 *
 * <p>The M14 strategies are the cases worth pinning down. They were unreachable through the
 * configuration gate, so nothing ever checked that selecting one marks the stored documents stale -
 * and switching from fixed length to page changes every chunk in the knowledge base.
 *
 * @author owlzhangfq@gmail.com
 */
class VersionFingerprintFactoryTest {

    private static final String VISION_MODEL = "qwen-vl";

    private final VersionFingerprintFactory factory = new VersionFingerprintFactory();

    @Test
    void shouldGiveEveryStrategyItsOwnChunkFingerprint() {
        String fixedLength = factory.chunkFingerprint(config(FixedLengthTextSplitter.STRATEGY_CODE));
        String page = factory.chunkFingerprint(config(PageSplitter.STRATEGY_CODE));
        String heading = factory.chunkFingerprint(config(HeadingTextSplitter.STRATEGY_CODE));
        String separator = factory.chunkFingerprint(config(SeparatorTextSplitter.STRATEGY_CODE));
        String llmSemantic = factory.chunkFingerprint(config(LlmSemanticTextSplitter.STRATEGY_CODE));

        // Switching to any of these re-cuts the whole knowledge base, so each has to mark documents
        // stale against every other one.
        assertNotEquals(fixedLength, page);
        assertNotEquals(fixedLength, heading);
        assertNotEquals(fixedLength, separator);
        assertNotEquals(fixedLength, llmSemantic);
        assertNotEquals(page, heading);
        assertNotEquals(heading, separator);
    }

    @Test
    void shouldKeepTheSameStrategyStable() {
        // The other half of the promise: an unchanged configuration must not invent a difference, or
        // every save would rebuild a corpus that has not moved.
        assertEquals(factory.chunkFingerprint(config(PageSplitter.STRATEGY_CODE)),
                factory.chunkFingerprint(config(PageSplitter.STRATEGY_CODE)));
    }

    @Test
    void shouldSeparateATwoLevelBaseFromASingleLevelOne() {
        KbIndexConfig single = config(FixedLengthTextSplitter.STRATEGY_CODE);
        KbIndexConfig twoLevel = config(FixedLengthTextSplitter.STRATEGY_CODE);
        ParentChildParams params = new ParentChildParams();
        params.setEnabled(true);
        params.setParentMaxTokens(1200);
        params.setChildMaxTokens(300);
        params.setChildOverlap(50);
        twoLevel.setParentChild(params);

        // The two level mode is what actually runs, and the strategy code stays fixed_length in both, so
        // the parent child segment is the only thing that can tell the two builds apart.
        assertNotEquals(factory.chunkFingerprint(single), factory.chunkFingerprint(twoLevel));
    }

    @Test
    void shouldFoldTheHeadingDepthInOnlyForTheHeadingStrategy() {
        KbIndexConfig shallow = config(HeadingTextSplitter.STRATEGY_CODE);
        shallow.setSplitHeadingLevel(2);
        KbIndexConfig deep = config(HeadingTextSplitter.STRATEGY_CODE);
        deep.setSplitHeadingLevel(3);

        assertNotEquals(factory.chunkFingerprint(shallow), factory.chunkFingerprint(deep));

        // A strategy that never reads the depth must not rebuild because someone left a value in the
        // field: the parameter belongs to the strategy, not to the knowledge base.
        KbIndexConfig pageWithDepth = config(PageSplitter.STRATEGY_CODE);
        pageWithDepth.setSplitHeadingLevel(3);
        assertEquals(factory.chunkFingerprint(config(PageSplitter.STRATEGY_CODE)),
                factory.chunkFingerprint(pageWithDepth));
    }

    @Test
    void shouldCombineBothStagesIntoTheConfigurationFingerprint() {
        KbIndexConfig config = config(PageSplitter.STRATEGY_CODE);

        assertEquals(factory.configFingerprint(config, VISION_MODEL),
                factory.versionFingerprint(factory.parseFingerprint(config, VISION_MODEL),
                        factory.chunkFingerprint(config)));
    }

    @Test
    void shouldReportNoVersionFingerprintUntilBothStagesRan() {
        // A version that never recorded one side cannot be compared with the knowledge base digest, and
        // answering with a plausible value would make a half built document look up to date.
        assertNull(factory.versionFingerprint(null, "chunk"));
        assertNull(factory.versionFingerprint("parse", null));
    }

    private KbIndexConfig config(String strategy) {
        KbIndexConfig config = new KbIndexConfig();
        config.setSplitStrategy(strategy);
        config.setChunkMaxTokens(600);
        config.setChunkOverlap(100);
        return config;
    }
}
