package io.kbrag.domain.enums;

import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.FixedLengthTextSplitter;
import io.kbrag.domain.service.HeadingTextSplitter;
import io.kbrag.domain.service.LlmSemanticTextSplitter;
import io.kbrag.domain.service.PageSplitter;
import io.kbrag.domain.service.SeparatorTextSplitter;
import io.kbrag.domain.service.SimpleTokenEstimator;
import io.kbrag.domain.service.SplitterRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Covers the correspondence this enum exists to enforce: the codes an operator may save and the
 * strategies that actually run have to be the same set.
 *
 * <p>The regression these cases pin down cost the three M14 strategies their whole delivery. The enum
 * named two codes while five splitters were registered, so the configuration gate rejected
 * {@code separator}, {@code heading} and {@code page} on the only write path there is - the console
 * offered the forms, the beans were in the container, and no knowledge base could ever be configured
 * for them. A code that resolves to nothing and a code that resolves to something other than itself are
 * the two ways that can happen again, so both are asserted here rather than in a splitter's own test.
 *
 * @author owlzhangfq@gmail.com
 */
class SplitStrategyTest {

    private final SimpleTokenEstimator tokenEstimator = new SimpleTokenEstimator();
    private final FixedLengthTextSplitter fixedLength = new FixedLengthTextSplitter(tokenEstimator);

    @Test
    void shouldNameEveryShippedStrategy() {
        // Reading the codes off the implementations rather than off string literals: a splitter that
        // renames its code without joining the enum fails here instead of at an operator's save.
        assertNotNull(SplitStrategy.from(FixedLengthTextSplitter.STRATEGY_CODE));
        assertNotNull(SplitStrategy.from(LlmSemanticTextSplitter.STRATEGY_CODE));
        assertNotNull(SplitStrategy.from(SeparatorTextSplitter.STRATEGY_CODE));
        assertNotNull(SplitStrategy.from(HeadingTextSplitter.STRATEGY_CODE));
        assertNotNull(SplitStrategy.from(PageSplitter.STRATEGY_CODE));
    }

    @Test
    void shouldResolveEveryConfigurableCodeToItsOwnSplitter() {
        SplitterRouter router = new SplitterRouter(
                List.of(fixedLength, llmSemantic(), separator(), heading()), fixedLength);

        for (SplitStrategy strategy : SplitStrategy.values()) {
            if (strategy == SplitStrategy.PAGE) {
                // The page strategy needs the page boundaries as well as the text, so the index pipeline
                // dispatches it instead of the router; PageSplitterTest covers that route.
                continue;
            }
            // The router falls back to fixed length for an unknown code, which is what turned the M14
            // strategies into configuration that read as applied while it never ran.
            assertEquals(strategy.code(), router.resolve(strategy.code()).strategy(),
                    "a saveable strategy must not fall back to another one");
        }
    }

    @Test
    void shouldRejectACodeNoImplementationAnswersTo() {
        // parent_child is the case worth naming: it is a two level mode switched on by its own flag, not
        // a code an operator selects, and no TextSplitter answers to it.
        assertNull(SplitStrategy.from("parent_child"));
        assertNull(SplitStrategy.from("table_row"));
        assertNull(SplitStrategy.from(""));
        assertNull(SplitStrategy.from(null));
    }

    @Test
    void shouldNormaliseTheStoredCode() {
        // The column keeps a plain string, so the gate normalises before persisting: an uppercase code
        // written verbatim would later miss the router's lookup and fall through to fixed length.
        assertEquals(SplitStrategy.PAGE, SplitStrategy.from("  PAGE  "));
        assertEquals("page", SplitStrategy.from("Page").code());
    }

    private LlmSemanticTextSplitter llmSemantic() {
        return new LlmSemanticTextSplitter(mock(ChatProvider.class), tokenEstimator,
                mock(ObjectStorage.class), fixedLength);
    }

    private SeparatorTextSplitter separator() {
        return new SeparatorTextSplitter(fixedLength);
    }

    private HeadingTextSplitter heading() {
        return new HeadingTextSplitter(fixedLength);
    }
}
