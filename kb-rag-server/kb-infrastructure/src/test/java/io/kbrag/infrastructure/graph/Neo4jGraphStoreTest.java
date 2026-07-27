package io.kbrag.infrastructure.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two pieces of the Neo4j adapter that are pure functions of their input and therefore
 * provable without a server: the full text query it builds out of the tokenised terms, and the hop bound
 * that is spliced into the traversal statement as a literal.
 *
 * <p><b>The Cypher statements themselves are left to the integration acceptance</b> (M7 contract section
 * 4): a mocked driver would only assert that a string this class also produces was passed to it, which
 * proves nothing about whether Neo4j accepts it. What is unit tested here is exactly what a wrong value
 * would turn into an injection or a runaway traversal.
 *
 * @author owlzhangfq@gmail.com
 */
class Neo4jGraphStoreTest {

    @Test
    void shouldJoinTheTermsWithADisjunction() {
        assertEquals("苹果公司 OR 创始人",
                Neo4jGraphStore.luceneQueryOf(List.of("苹果公司", "创始人")));
    }

    @Test
    void shouldEscapeTheCharactersTheQueryParserWouldReadAsSyntax() {
        assertEquals("c\\+\\+", Neo4jGraphStore.luceneQueryOf(List.of("c++")));
        assertEquals("a\\:b", Neo4jGraphStore.luceneQueryOf(List.of("a:b")));
        assertEquals("\\(x\\)", Neo4jGraphStore.luceneQueryOf(List.of("(x)")));
        assertEquals("\\*", Neo4jGraphStore.luceneQueryOf(List.of("*")));
    }

    @Test
    void shouldProduceAnEmptyQueryWhenThereIsNothingToSearch() {
        assertTrue(Neo4jGraphStore.luceneQueryOf(List.of()).isEmpty());
        assertTrue(Neo4jGraphStore.luceneQueryOf(null).isEmpty());
    }

    @Test
    void shouldClampTheHopBoundOfTheTraversal() {
        assertEquals(2, Neo4jGraphStore.boundedHops(2));
        assertEquals(0, Neo4jGraphStore.boundedHops(-1));
        assertEquals(5, Neo4jGraphStore.boundedHops(50));
    }
}
