package io.kbrag.domain.model;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one place that knows a stored snapshot can have two shapes: a pre M5 row carrying a single
 * {@code kb_id} and an M5 row carrying {@code kb_refs}. A released snapshot is evidence behind a gate
 * verdict and is never migrated, so the read has to absorb both (M5 contract section 1).
 *
 * @author owlzhangfq@gmail.com
 */
class AppConfigSnapshotTest {

    @Test
    void shouldReadALegacySingleKnowledgeBaseSnapshotAsOneWeightedReference() {
        AppConfigSnapshot snapshot = JsonUtil.parse(
                "{\"kb_id\":\"kb_legacy\",\"retrieval\":{\"top_n\":5}}", AppConfigSnapshot.class);

        assertEquals(List.of(new KbRef("kb_legacy", KbRef.DEFAULT_WEIGHT)), snapshot.getKbRefs());
        assertEquals(List.of("kb_legacy"), snapshot.kbIds());
        assertEquals(1, snapshot.getKbRefs().get(0).effectiveWeight());
    }

    @Test
    void shouldReadTheMultiBaseShapeWithItsWeights() {
        AppConfigSnapshot snapshot = JsonUtil.parse(
                "{\"kb_refs\":[{\"kb_id\":\"kb_a\",\"weight\":3},{\"kb_id\":\"kb_b\",\"weight\":1}]}",
                AppConfigSnapshot.class);

        assertEquals(List.of("kb_a", "kb_b"), snapshot.kbIds());
        assertEquals(3, snapshot.getKbRefs().get(0).effectiveWeight());
        assertEquals(1, snapshot.getKbRefs().get(1).effectiveWeight());
    }

    @Test
    void shouldPreferTheMultiBaseShapeWhenBothArePresent() {
        // A client that sends both is a client mid migration; the shape that can express more wins.
        AppConfigSnapshot snapshot = JsonUtil.parse(
                "{\"kb_id\":\"kb_legacy\",\"kb_refs\":[{\"kb_id\":\"kb_a\",\"weight\":2}]}",
                AppConfigSnapshot.class);

        assertEquals(List.of("kb_a"), snapshot.kbIds());
    }

    @Test
    void shouldReturnTheWeightExactlyAsWritten() {
        // Repairing a zero weight here would hide it from the validation that rejects it; the tolerance the
        // retrieval side needs lives in KbRef instead.
        AppConfigSnapshot snapshot = JsonUtil.parse(
                "{\"kb_refs\":[{\"kb_id\":\"kb_a\",\"weight\":0},{\"kb_id\":\"kb_b\"}]}",
                AppConfigSnapshot.class);

        assertEquals(0, snapshot.getKbRefs().get(0).weight().intValue());
        assertNull(snapshot.getKbRefs().get(1).weight());
        assertEquals(1, snapshot.getKbRefs().get(1).effectiveWeight());
    }

    @Test
    void shouldDropAnEntryThatNamesNoKnowledgeBase() {
        AppConfigSnapshot snapshot = JsonUtil.parse(
                "{\"kb_refs\":[{\"kb_id\":\"  \",\"weight\":1},{\"kb_id\":\"kb_a\",\"weight\":1}]}",
                AppConfigSnapshot.class);

        assertEquals(List.of("kb_a"), snapshot.kbIds());
    }

    @Test
    void shouldReadNoKnowledgeBaseFromAnEmptySnapshot() {
        AppConfigSnapshot snapshot = JsonUtil.parse("{}", AppConfigSnapshot.class);

        assertTrue(snapshot.getKbRefs().isEmpty());
        assertTrue(snapshot.kbIds().isEmpty());
    }

    @Test
    void shouldTreatAMissingRoutingBlockAsOff() {
        AppConfigSnapshot snapshot = JsonUtil.parse("{\"kb_id\":\"kb_legacy\"}", AppConfigSnapshot.class);

        assertFalse(snapshot.routingOrDefaults().isEnabled());
        assertNull(snapshot.routingOrDefaults().getPrompt());
    }

    @Test
    void shouldReadTheRoutingBlock() {
        AppConfigSnapshot snapshot = JsonUtil.parse(
                "{\"kb_refs\":[{\"kb_id\":\"kb_a\",\"weight\":1}],"
                        + "\"routing\":{\"enabled\":true,\"prompt\":\"pick one\"}}",
                AppConfigSnapshot.class);

        assertTrue(snapshot.routingOrDefaults().isEnabled());
        assertEquals("pick one", snapshot.routingOrDefaults().getPrompt());
    }

    @Test
    void shouldSerialiseTheMultiBaseShapeAndNeverWriteTheLegacyField() {
        // A legacy snapshot read and written back comes out in the M5 shape, so a console reading a version
        // detail never has to know which shape the row was stored in.
        AppConfigSnapshot snapshot = JsonUtil.parse("{\"kb_id\":\"kb_legacy\"}", AppConfigSnapshot.class);

        String json = JsonUtil.toJson(snapshot);
        Map<String, Object> document = JsonUtil.parse(json, new TypeReference<Map<String, Object>>() {
        });

        assertTrue(document.containsKey("kb_refs"), json);
        assertFalse(document.containsKey("kb_id"), json);
        assertEquals(List.of("kb_legacy"),
                JsonUtil.parse(json, AppConfigSnapshot.class).kbIds());
    }
}
