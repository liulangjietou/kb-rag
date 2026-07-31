package io.kbrag.api.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builders of the little JSON Schema fragments the tool catalogues are made of.
 *
 * <p>Plain maps rather than a schema library: the schemas are advertised verbatim to the MCP
 * client and never evaluated on this side, so a dependency would buy nothing but a second way
 * to spell {@code {"type":"string"}}.
 *
 * @author owlzhangfq@gmail.com
 */
public final class McpJsonSchemas {

    private McpJsonSchemas() {
    }

    /**
     * Builds an object schema.
     *
     * @param properties property name to schema fragment, in declaration order
     * @param required   names of the required properties
     * @return object schema
     */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * Builds a string property.
     *
     * @param description property description shown to the calling model
     * @return string schema fragment
     */
    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * Builds an integer property.
     *
     * @param description property description shown to the calling model
     * @return integer schema fragment
     */
    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /**
     * Builds a number property.
     *
     * @param description property description shown to the calling model
     * @return number schema fragment
     */
    public static Map<String, Object> number(String description) {
        return Map.of("type", "number", "description", description);
    }

    /**
     * Builds a boolean property.
     *
     * @param description property description shown to the calling model
     * @return boolean schema fragment
     */
    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    /**
     * Builds an array property.
     *
     * @param items       schema of one element
     * @param description property description shown to the calling model
     * @return array schema fragment
     */
    public static Map<String, Object> array(Map<String, Object> items, String description) {
        return Map.of("type", "array", "items", items, "description", description);
    }

    /**
     * Builds a free form object property, for caller defined payloads stored verbatim.
     *
     * @param description property description shown to the calling model
     * @return object schema fragment without a property list
     */
    public static Map<String, Object> freeObject(String description) {
        return Map.of("type", "object", "description", description);
    }
}
