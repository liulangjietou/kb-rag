package io.kbrag.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

/**
 * Single JSON facade of the service, backed by one shared {@link ObjectMapper}.
 *
 * <p>All serialization inside the service goes through this class so mapper configuration cannot
 * drift between modules.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtil() {
    }

    /**
     * Exposes the shared mapper for framework integration points.
     *
     * @return shared object mapper instance
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Serializes an object to its JSON representation.
     *
     * @param value object to serialize, {@code null} yields {@code null}
     * @return JSON string
     */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.error("serialize object to json failed, errorCode={}, type={}",
                    ErrorCode.INTERNAL_ERROR, value.getClass().getName(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "serialize json failed", e);
        }
    }

    /**
     * Deserializes a JSON string into the given type.
     *
     * @param json  JSON string, blank yields {@code null}
     * @param clazz target type
     * @param <T>   target type
     * @return deserialized instance
     */
    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("parse json failed, errorCode={}, type={}", ErrorCode.INTERNAL_ERROR, clazz.getName(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "parse json failed", e);
        }
    }

    /**
     * Deserializes a JSON string into a generic type.
     *
     * @param json     JSON string, blank yields {@code null}
     * @param typeRef  target type reference
     * @param <T>      target type
     * @return deserialized instance
     */
    public static <T> T parse(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            log.error("parse json failed, errorCode={}, type={}", ErrorCode.INTERNAL_ERROR, typeRef.getType(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "parse json failed", e);
        }
    }
}
