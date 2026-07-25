package io.kbrag.api.dto;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.DictStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

/**
 * New availability of a dictionary entry.
 *
 * @param status {@code ENABLED} or {@code DISABLED}
 *
 * @author owlzhangfq@gmail.com
 */
public record IkDictStatusRequest(@NotBlank(message = "must not be blank") String status) {

    /**
     * Resolves the availability, failing fast on an unknown literal.
     *
     * @return dictionary status
     */
    public DictStatus resolvedStatus() {
        try {
            return DictStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("unknown status: " + status);
        }
    }
}
