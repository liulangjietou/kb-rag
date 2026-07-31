package io.kbrag.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binds a {@code tools/call} arguments object onto the transport DTO of the REST twin.
 *
 * <p>The MCP surface reuses the open API's request shapes on purpose - one DTO, one set of
 * constraints, one behaviour on both transports. Bean validation does not run on a Jackson
 * conversion, so this class invokes the validator explicitly and fails the fast-fail way the
 * controllers do: a single {@code INVALID_PARAM} carrying every violation.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpArgumentBinder {

    private final Validator validator;

    /**
     * Converts and validates one arguments object.
     *
     * @param arguments arguments of the tool call
     * @param type      transport DTO type of the REST twin
     * @param <T>       transport DTO type
     * @return validated DTO instance
     */
    public <T> T bind(JsonNode arguments, Class<T> type) {
        T value;
        try {
            value = JsonUtil.mapper().treeToValue(arguments, type);
        } catch (Exception e) {
            log.info("mcp arguments rejected, errorCode={}, type={}, reason={}",
                    ErrorCode.INVALID_PARAM, type.getSimpleName(), e.getMessage());
            throw BizException.invalidParam("arguments do not match the tool's input schema");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw BizException.invalidParam(detail);
        }
        return value;
    }
}
