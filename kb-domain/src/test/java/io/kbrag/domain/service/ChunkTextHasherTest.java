package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Covers the normalisation behind {@code chunk_text_hash}, which is what lets a disable annotation be
 * inherited across document versions.
 */
class ChunkTextHasherTest {

    private static final int SHA256_HEX_LENGTH = 64;

    private final ChunkTextHasher hasher = new ChunkTextHasher();

    @Test
    void shouldProduceHexDigestOfFixedLength() {
        assertEquals(SHA256_HEX_LENGTH, hasher.hash("knowledge base").length());
    }

    @Test
    void shouldIgnoreWhitespaceDifferences() {
        assertEquals(hasher.hash("retrieval augmented generation"),
                hasher.hash("  retrieval\naugmented\t generation  "));
    }

    @Test
    void shouldFoldFullwidthCharactersOntoHalfwidth() {
        assertEquals(hasher.hash("ABC123"), hasher.hash("ＡＢＣ１２３"));
    }

    @Test
    void shouldKeepCjkTextStable() {
        assertEquals(hasher.hash("知识库检索"), hasher.hash("知识库 检索\n"));
    }

    @Test
    void shouldPreserveCaseDifferences() {
        assertNotEquals(hasher.hash("Knowledge"), hasher.hash("knowledge"));
    }

    @Test
    void shouldDistinguishDifferentText() {
        assertNotEquals(hasher.hash("chunk one"), hasher.hash("chunk two"));
    }

    @Test
    void shouldTreatNullAsEmpty() {
        assertEquals(hasher.hash(""), hasher.hash(null));
    }
}
