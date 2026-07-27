package io.kbrag.domain.enums;

/**
 * Engine backing the vector route, selected by the {@code VECTOR_ENGINE} environment variable.
 *
 * @author owlzhangfq@gmail.com
 */
public enum VectorEngine {

    /** Lite mode, Elasticsearch dense_vector serves both BM25 and kNN. */
    ES,

    /** Full mode, Qdrant serves the vector route while Elasticsearch serves BM25. */
    QDRANT;

    /**
     * Resolves the engine from its configuration literal.
     *
     * @param value configuration value, case insensitive
     * @return matching engine, defaults to {@link #ES} when blank or unknown
     */
    public static VectorEngine from(String value) {
        if (value == null || value.isBlank()) {
            return ES;
        }
        for (VectorEngine engine : values()) {
            if (engine.name().equalsIgnoreCase(value.trim())) {
                return engine;
            }
        }
        return ES;
    }

    /**
     * Lower case literal used inside index names, alias names and registry rows.
     *
     * @return engine code
     */
    public String code() {
        return name().toLowerCase();
    }
}
