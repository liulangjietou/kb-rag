package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;

import java.util.List;

/**
 * Splitting strategy abstraction.
 *
 * <p>M1 ships the fixed length strategy only; the remaining strategies of the requirement document
 * plug in as additional implementations without touching the pipeline.
 */
public interface TextSplitter {

    /**
     * Strategy identifier persisted in the split fingerprint.
     *
     * @return strategy code
     */
    String strategy();

    /**
     * Splits a text into chunks.
     *
     * @param text   source text, blank input yields an empty list
     * @param params strategy parameters
     * @return ordered chunks
     */
    List<SplitChunk> split(String text, SplitParams params);
}
