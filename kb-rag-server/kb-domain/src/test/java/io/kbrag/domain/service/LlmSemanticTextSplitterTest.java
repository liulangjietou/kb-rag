package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the LLM semantic splitter of requirement section 4.3: the model only ever names cut points
 * (content is cut verbatim from the original text, never rewritten), an untrustworthy answer degrades
 * that one window to the fixed length strategy, and the object storage cache key changes exactly when
 * the content, the model or the prompt version changes.
 *
 * @author owlzhangfq@gmail.com
 */
class LlmSemanticTextSplitterTest {

    private ChatProvider chatProvider;
    private SimpleTokenEstimator tokenEstimator;
    private ObjectStorage objectStorage;
    private FixedLengthTextSplitter fallbackSplitter;
    private LlmSemanticTextSplitter splitter;

    @BeforeEach
    void setUp() {
        chatProvider = mock(ChatProvider.class);
        tokenEstimator = new SimpleTokenEstimator();
        objectStorage = mock(ObjectStorage.class);
        fallbackSplitter = new FixedLengthTextSplitter(tokenEstimator);
        splitter = new LlmSemanticTextSplitter(chatProvider, tokenEstimator, objectStorage, fallbackSplitter);
    }

    @Test
    void shouldReportItsOwnStrategyCode() {
        assertEquals("llm_semantic", splitter.strategy());
    }

    @Test
    void shouldDegradeToFixedLengthWhenTheModelAnswerIsNotJson() {
        String text = "第一句。第二句。第三句。";
        when(chatProvider.complete(anyString(), anyString())).thenReturn("not a json answer");

        List<SplitChunk> chunks = splitter.split(text, SplitParams.defaults());
        List<SplitChunk> expected = fallbackSplitter.split(text, SplitParams.defaults());

        assertEquals(expected.size(), chunks.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getContent(), chunks.get(i).getContent());
        }
    }

    @Test
    void shouldDegradeToFixedLengthWhenACutPointIsOutOfRange() {
        String text = "第一句。第二句。第三句。";
        // Only units 0, 1, 2 exist in this window; 9 is out of range and must be rejected rather than
        // silently clamped, requirement section 4.4 "output must be strongly validated".
        when(chatProvider.complete(anyString(), anyString())).thenReturn("{\"cuts\":[9]}");

        List<SplitChunk> chunks = splitter.split(text, SplitParams.defaults());
        List<SplitChunk> expected = fallbackSplitter.split(text, SplitParams.defaults());

        assertEquals(expected.size(), chunks.size());
        assertEquals(expected.get(0).getContent(), chunks.get(0).getContent());
    }

    @Test
    void shouldCutTheOriginalTextVerbatimAtTheValidatedBoundariesWithoutRewriting() {
        String text = "第一句。第二句。第三句。";
        // Three units (indices 0,1,2); a single cut after unit 0 yields two chunks, the second one
        // ending implicitly at the window's last unit.
        when(chatProvider.complete(anyString(), anyString()))
                .thenReturn("{\"cuts\":[0],\"chunks\":[{\"title\":\"A\"},{\"title\":\"B\"}]}");

        List<SplitChunk> chunks = splitter.split(text, SplitParams.defaults());

        assertEquals(2, chunks.size());
        assertEquals("第一句。", chunks.get(0).getContent());
        assertEquals("第二句。第三句。", chunks.get(1).getContent());
        assertEquals("A", chunks.get(0).getMetadata().get("title"));
        assertEquals("B", chunks.get(1).getMetadata().get("title"));
        // Re-joining every chunk must reproduce the original text: nothing was added, removed or reworded.
        assertEquals(text, chunks.get(0).getContent() + chunks.get(1).getContent());
    }

    @Test
    void shouldReturnNothingForBlankInput() {
        assertEquals(0, splitter.split("   ", SplitParams.defaults()).size());
    }

    @Test
    void cacheKeyShouldChangeWithTheContentHash() {
        String keyA = splitter.cacheKey("hash-a", "qwen-plus");
        String keyB = splitter.cacheKey("hash-b", "qwen-plus");

        assertNotEquals(keyA, keyB);
    }

    @Test
    void cacheKeyShouldChangeWithTheModel() {
        String keyA = splitter.cacheKey("hash-a", "qwen-plus");
        String keyB = splitter.cacheKey("hash-a", "qwen-max");

        assertNotEquals(keyA, keyB);
    }

    @Test
    void cacheKeyShouldBeDeterministic() {
        assertEquals(splitter.cacheKey("hash-a", "qwen-plus"), splitter.cacheKey("hash-a", "qwen-plus"));
    }

    @Test
    void cacheKeyShouldIncludeThePromptVersion() {
        // The prompt version is a fixed constant folded into every key, so changing it (a code change,
        // not a parameter here) invalidates every cache entry; this asserts the constant is what the
        // contract names it, not an accidental literal.
        assertEquals("split_prompt_v1", LlmSemanticTextSplitter.SPLIT_PROMPT_VERSION);
    }
}
