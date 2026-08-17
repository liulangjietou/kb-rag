package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Upsert payload for one model price.
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelPriceRequest(
        @NotBlank @Size(max = 64) String provider,
        @NotBlank @Size(max = 32) String capability,
        @NotBlank @Size(max = 128) String model,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @JsonProperty("input_price_micros") @NotNull @Min(0) Long inputPriceMicros,
        @JsonProperty("output_price_micros") @NotNull @Min(0) Long outputPriceMicros,
        @NotNull Boolean enabled) {
}
