package io.kbrag.domain.enums;

/**
 * Content nature of a chunk, mirrored into the engine side {@code chunk_type} keyword field.
 */
public enum ChunkType {

    /** Plain document text. */
    TEXT,

    /** Textual proxy of an image produced by the vision provider. */
    IMAGE,

    /** Aggregated chat messages. */
    CHAT_LOG;

    /**
     * Lower case literal stored in the search engines.
     *
     * @return engine side value
     */
    public String code() {
        return name().toLowerCase();
    }
}
