package io.kbrag.infrastructure.provider;

import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.port.ModelCallMeter;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the provider-independent metering boundary and compatible usage parsing.
 *
 * @author owlzhangfq@gmail.com
 */
class ModelUsageSupportTest {

    @Test
    void shouldResolveConfiguredBillingProviderWithAdapterFallback() {
        assertEquals("azure", ModelUsageSupport.billingProvider(" Azure ", "dashscope"));
        assertEquals("dashscope", ModelUsageSupport.billingProvider(" ", "dashscope"));
    }

    @Test
    void shouldParseOpenAiAndNativeTokenNames() {
        ModelTokenUsage compatible = ModelUsageSupport.usageOf(
                "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}");
        ModelTokenUsage nativeUsage = ModelUsageSupport.usageOf(
                "{\"usage\":{\"input_tokens\":20,\"output_tokens\":5,\"total_tokens\":25}}");

        assertEquals(new ModelTokenUsage(12, 3, 15, true), compatible);
        assertEquals(new ModelTokenUsage(20, 5, 25, true), nativeUsage);
        assertFalse(ModelUsageSupport.usageOf("{\"output\":{}}").known());
    }

    @Test
    void shouldSettleBeforeDecodingBecauseTheProviderHasAlreadyBilledTheResponse() {
        RecordingMeter meter = new RecordingMeter();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ModelUsageSupport.execute(meter,
                        new ModelCallSpec("dashscope", ModelCallSpec.CHAT, "qwen", 100),
                        () -> "{\"usage\":{\"total_tokens\":7}}",
                        body -> { throw new IllegalArgumentException("bad payload"); }));

        assertEquals("bad payload", failure.getMessage());
        assertTrue(meter.succeeded, "provider spend must be settled before response content is decoded");
        assertFalse(meter.failed, "a local decoder failure must not release already billed provider spend");
        assertEquals(7L, meter.usage.get().totalTokens());
    }

    @Test
    void shouldReserveUtf8InputAndFullOutputBudgetConservatively() {
        long tokens = ModelUsageSupport.chatUpperBound("系统", List.of(ChatMessage.user("hello")), 256);

        assertTrue(tokens >= "系统hello".getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 256L);
    }

    @Test
    void shouldReserveDecodedPixelsWhenAnImageCompressesBelowItsDimensions() throws Exception {
        BufferedImage flatImage = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(flatImage, "png", output);

        long tokens = ModelUsageSupport.imageUpperBound(List.of(output.toByteArray()));

        assertTrue(tokens >= 320L * 200L);
    }

    @Test
    void shouldSaturateProviderCountersInsteadOfOverflowing() {
        ModelTokenUsage usage = new ModelTokenUsage(Long.MAX_VALUE, 1L, 0L, true);

        assertEquals(Long.MAX_VALUE, usage.totalTokens());
    }

    private static final class RecordingMeter implements ModelCallMeter {
        private final AtomicReference<ModelTokenUsage> usage = new AtomicReference<>();
        private boolean succeeded;
        private boolean failed;

        @Override
        public ModelCallTicket reserve(ModelCallSpec spec) {
            return new ModelCallTicket("mu_test", spec.reservedTokens(), true);
        }

        @Override
        public void succeed(ModelCallTicket ticket, ModelTokenUsage modelTokenUsage) {
            succeeded = true;
            usage.set(modelTokenUsage);
        }

        @Override
        public void fail(ModelCallTicket ticket, Throwable cause) {
            failed = true;
        }
    }
}
