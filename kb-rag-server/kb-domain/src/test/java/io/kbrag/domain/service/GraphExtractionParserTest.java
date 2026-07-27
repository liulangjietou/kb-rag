package io.kbrag.domain.service;

import io.kbrag.domain.model.GraphEntity;
import io.kbrag.domain.model.GraphRelation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hard validation of the extraction model output, requirement section 4.4: a malformed answer,
 * an over long entity name and a relation endpoint outside the entity list are all rejected as a whole so
 * the caller can count the chunk as skipped.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphExtractionParserTest {

    private final GraphExtractionParser parser = new GraphExtractionParser();

    @Test
    void shouldParseAWellFormedAnswer() {
        GraphExtractionParser.Result result = parser.parse("""
                {"entities":[{"name":"苹果公司","type":"organization"},{"name":"乔布斯","type":"person"}],
                 "relations":[{"source":"乔布斯","type":"founded","target":"苹果公司"}]}""");

        assertNotNull(result);
        assertEquals(List.of(new GraphEntity("苹果公司", "organization"),
                new GraphEntity("乔布斯", "person")), result.entities());
        assertEquals(List.of(new GraphRelation("乔布斯", "founded", "苹果公司")), result.relations());
    }

    @Test
    void shouldTolerateACodeFenceAndSurroundingProse() {
        GraphExtractionParser.Result result = parser.parse("""
                Here is the graph:
                ```json
                {"entities":[{"name":"Neo4j","type":"product"}],"relations":[]}
                ```
                """);

        assertNotNull(result);
        assertEquals(List.of(new GraphEntity("Neo4j", "product")), result.entities());
    }

    @Test
    void shouldRejectAnAnswerThatIsNotJson() {
        assertNull(parser.parse("I cannot extract anything from this passage."));
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
    }

    @Test
    void shouldRejectMalformedJson() {
        assertNull(parser.parse("{\"entities\":[{\"name\":\"broken\",}]}"));
    }

    @Test
    void shouldRejectAnObjectThatDoesNotCarryTheContractedEntityArray() {
        assertNull(parser.parse("[{\"name\":\"a\"}]"));
        assertNull(parser.parse("{\"result\":\"nothing to extract\"}"));
        assertNull(parser.parse("{\"entities\":\"none\"}"));
    }

    @Test
    void shouldRejectAnEntityNameLongerThanTheLimit() {
        String tooLong = "x".repeat(GraphExtractionParser.MAX_ENTITY_NAME_LENGTH + 1);

        assertNull(parser.parse("{\"entities\":[{\"name\":\"" + tooLong + "\"}],\"relations\":[]}"));
    }

    @Test
    void shouldAcceptAnEntityNameExactlyAtTheLimit() {
        String atLimit = "x".repeat(GraphExtractionParser.MAX_ENTITY_NAME_LENGTH);

        GraphExtractionParser.Result result =
                parser.parse("{\"entities\":[{\"name\":\"" + atLimit + "\"}],\"relations\":[]}");

        assertNotNull(result);
        assertEquals(atLimit, result.entities().get(0).name());
    }

    @Test
    void shouldRejectARelationWhoseEndpointWasNeverExtracted() {
        assertNull(parser.parse("""
                {"entities":[{"name":"A","type":"x"}],
                 "relations":[{"source":"A","type":"knows","target":"B"}]}"""));
    }

    @Test
    void shouldDefaultTheMissingTypes() {
        GraphExtractionParser.Result result = parser.parse("""
                {"entities":[{"name":"A"},{"name":"B"}],"relations":[{"source":"A","target":"B"}]}""");

        assertNotNull(result);
        assertEquals(GraphExtractionParser.DEFAULT_ENTITY_TYPE, result.entities().get(0).type());
        assertEquals(GraphExtractionParser.DEFAULT_RELATION_TYPE, result.relations().get(0).type());
    }

    @Test
    void shouldDeduplicateEntityNamesKeepingTheFirstCategory() {
        GraphExtractionParser.Result result = parser.parse("""
                {"entities":[{"name":"A","type":"person"},{"name":"A","type":"organization"}],
                 "relations":[]}""");

        assertNotNull(result);
        assertEquals(List.of(new GraphEntity("A", "person")), result.entities());
    }

    @Test
    void shouldAcceptAnAnswerThatExtractedNothing() {
        GraphExtractionParser.Result result = parser.parse("{\"entities\":[],\"relations\":[]}");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldDropAnEntityWithoutAUsableName() {
        GraphExtractionParser.Result result =
                parser.parse("{\"entities\":[{\"name\":\"  \"},{\"name\":\"A\"}],\"relations\":[]}");

        assertNotNull(result);
        assertEquals(List.of(new GraphEntity("A", GraphExtractionParser.DEFAULT_ENTITY_TYPE)),
                result.entities());
    }
}
