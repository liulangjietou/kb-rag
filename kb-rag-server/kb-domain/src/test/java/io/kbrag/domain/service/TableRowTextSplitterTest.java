package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableRowTextSplitterTest {

    private final TableRowTextSplitter splitter = new TableRowTextSplitter(new FixedLengthTextSplitter(
            new SimpleTokenEstimator()));

    @Test
    void shouldProduceOneChunkPerTableRowWithHeaderJoin() {
        String markdown = "| name | age |\n| --- | --- |\n| Ann | 20 |\n| Bob | 22 |";
        SplitParams params = SplitParams.of(600, 0, null, null, false, 0, 0, true, 0);
        var chunks = splitter.split(markdown, params);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("name"));
        assertTrue(chunks.get(0).getContent().contains("Ann"));
    }
}