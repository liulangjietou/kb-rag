package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.ModelPrice;

/**
 * Model price row. Prices are per one million tokens in currency 10^-6 units.
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelPriceResponse(
        String provider,
        String capability,
        String model,
        String currency,
        @JsonProperty("input_price_micros") long inputPriceMicros,
        @JsonProperty("output_price_micros") long outputPriceMicros,
        boolean enabled) {

    public static ModelPriceResponse from(ModelPrice price) {
        return new ModelPriceResponse(price.getProvider(), price.getCapability(), price.getModel(),
                price.getCurrency(), valueOf(price.getInputPriceMicros()),
                valueOf(price.getOutputPriceMicros()), price.getEnabled() != null && price.getEnabled() == 1);
    }

    private static long valueOf(Long value) {
        return value == null ? 0L : value;
    }
}
