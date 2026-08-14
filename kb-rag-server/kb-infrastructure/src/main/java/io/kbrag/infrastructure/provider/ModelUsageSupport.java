package io.kbrag.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.port.ModelCallMeter;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared metering mechanics of model provider adapters.
 *
 * <p>The reservation is deliberately conservative: UTF-8 bytes are an upper bound for byte-pair
 * encoded text tokens, an image reserves at least one token per decoded pixel, and chat adds the full
 * configured output budget. When a response carries real usage it replaces the reservation; when a
 * compatible gateway omits usage, the upper bound is what keeps quota fail-closed.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ModelUsageSupport {

    private static final int MESSAGE_OVERHEAD_TOKENS = 64;

    private ModelUsageSupport() {
    }

    /** Resolves the billing identity independently from the concrete compatible-protocol adapter. */
    public static String billingProvider(String configuredProvider, String adapterDefault) {
        String provider = configuredProvider == null ? "" : configuredProvider.trim();
        if (provider.isEmpty()) {
            provider = adapterDefault;
        }
        return provider.toLowerCase(Locale.ROOT);
    }

    /**
     * Runs one HTTP call inside its quota reservation, settles provider usage, then decodes content.
     *
     * <p>Settlement precedes content decoding because a provider has already billed a syntactically
     * malformed answer. A later decoder failure must not turn that real spend into a released quota.
     */
    public static <T> T execute(ModelCallMeter meter, ModelCallSpec spec, Supplier<String> call,
                                Function<String, T> decoder) {
        ModelCallTicket ticket = meter.reserve(spec);
        String body;
        try {
            body = call.get();
        } catch (RuntimeException e) {
            meter.fail(ticket, e);
            throw e;
        }
        // From this point on the provider may have billed the call. A local settlement or decoder
        // failure must not release its reservation; an unfinished settlement is recovered later.
        meter.succeed(ticket, usageOf(body));
        return decoder.apply(body);
    }

    /** Upper bound of UTF-8 BPE tokens for a text collection. */
    public static long textUpperBound(List<String> texts) {
        long total = 0L;
        if (texts == null) {
            return 1L;
        }
        for (String text : texts) {
            total = safeAdd(total, utf8Length(text));
        }
        return Math.max(1L, total);
    }

    /** Chat input upper bound plus the configured maximum generated tokens. */
    public static long chatUpperBound(String systemPrompt, List<ChatMessage> messages, int maxOutputTokens) {
        long total = utf8Length(systemPrompt);
        if (messages != null) {
            for (ChatMessage message : messages) {
                total = safeAdd(total, utf8Length(message == null ? null : message.getContent()));
            }
            total = safeAdd(total, (long) (messages.size() + 1) * MESSAGE_OVERHEAD_TOKENS);
        }
        return Math.max(1L, safeAdd(total, Math.max(0, maxOutputTokens)));
    }

    /** Text prompt plus binary image bytes plus the maximum generated tokens. */
    public static long visionUpperBound(String prompt, byte[] content, int maxOutputTokens) {
        return Math.max(1L, safeAdd(safeAdd(utf8Length(prompt), imageTokenUpperBound(content)),
                Math.max(0, maxOutputTokens)));
    }

    /** Sum of conservative per-image visual-token reservations. */
    public static long imageUpperBound(List<byte[]> images) {
        long total = 0L;
        if (images != null) {
            for (byte[] image : images) {
                total = safeAdd(total, imageTokenUpperBound(image));
            }
        }
        return Math.max(1L, total);
    }

    /**
     * Reads both OpenAI-compatible and DashScope-native usage field names.
     */
    static ModelTokenUsage usageOf(String body) {
        try {
            JsonNode root = JsonUtil.parse(body, JsonNode.class);
            JsonNode usage = root == null ? null : root.path("usage");
            if (usage == null || usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
                return ModelTokenUsage.unknown();
            }
            long input = firstLong(usage, "prompt_tokens", "input_tokens");
            long output = firstLong(usage, "completion_tokens", "output_tokens");
            long total = firstLong(usage, "total_tokens");
            if (input <= 0L && output <= 0L && total <= 0L) {
                return ModelTokenUsage.unknown();
            }
            if (input == 0L && output == 0L) {
                input = total;
            }
            return new ModelTokenUsage(input, output, total, true);
        } catch (RuntimeException e) {
            return ModelTokenUsage.unknown();
        }
    }

    private static long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.canConvertToLong()) {
                return Math.max(0L, value.asLong());
            }
        }
        return 0L;
    }

    private static long utf8Length(String text) {
        return text == null ? 0L : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Uses image metadata rather than compressed byte size alone. A large, flat PNG can compress to
     * a few kilobytes while still occupying millions of visual patches, so bytes are not an upper bound.
     * Reading dimensions through an {@link ImageReader} avoids decoding the full raster here.
     */
    private static long imageTokenUpperBound(byte[] content) {
        if (content == null || content.length == 0) {
            return 0L;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                return content.length;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return content.length;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long width = Math.max(0, reader.getWidth(0));
                long height = Math.max(0, reader.getHeight(0));
                long pixels = width > 0L && height > Long.MAX_VALUE / width
                        ? Long.MAX_VALUE : width * height;
                return Math.max(content.length, pixels);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return content.length;
        }
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
