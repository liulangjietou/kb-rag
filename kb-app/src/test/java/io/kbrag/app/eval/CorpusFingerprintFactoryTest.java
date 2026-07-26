package io.kbrag.app.eval;

import io.kbrag.app.dict.IkDictService;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.IkDict;
import io.kbrag.domain.enums.DictStatus;
import io.kbrag.domain.enums.DictType;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.IkDictMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the corpus fingerprint composition of requirement section 4.6: it must change whenever the
 * active version set, the embedding model or the ik dictionary changes, and must be perfectly stable
 * otherwise so an unrelated call can never make two runs incomparable.
 *
 * @author owlzhangfq@gmail.com
 */
class CorpusFingerprintFactoryTest {

    private static final String KB_ID = "kb_test";

    private DocumentMapper documentMapper;
    private EmbeddingProvider embeddingProvider;
    private IkDictMapper ikDictMapper;
    private CorpusFingerprintFactory factory;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        ikDictMapper = mock(IkDictMapper.class);
        factory = new CorpusFingerprintFactory(documentMapper, embeddingProvider, new IkDictService(ikDictMapper));

        when(documentMapper.selectList(any())).thenReturn(List.of(document("dv_1"), document("dv_2")));
        when(embeddingProvider.model()).thenReturn("text-embedding-v4");
        when(embeddingProvider.dimension()).thenReturn(1024);
        when(ikDictMapper.selectList(any())).thenReturn(List.of(dictEntry("产品名")));
    }

    @Test
    void shouldBeStableForTheSameCorpusState() {
        assertEquals(factory.fingerprint(KB_ID), factory.fingerprint(KB_ID));
    }

    @Test
    void shouldChangeWhenTheActiveVersionSetChanges() {
        String before = factory.fingerprint(KB_ID);
        when(documentMapper.selectList(any())).thenReturn(List.of(document("dv_1"), document("dv_3")));

        assertNotEquals(before, factory.fingerprint(KB_ID));
    }

    @Test
    void shouldChangeWhenTheEmbeddingModelChanges() {
        String before = factory.fingerprint(KB_ID);
        when(embeddingProvider.model()).thenReturn("text-embedding-v5");

        assertNotEquals(before, factory.fingerprint(KB_ID));
    }

    @Test
    void shouldChangeWhenTheDictionaryChanges() {
        String before = factory.fingerprint(KB_ID);
        when(ikDictMapper.selectList(any())).thenReturn(List.of(dictEntry("产品名"), dictEntry("新词")));

        assertNotEquals(before, factory.fingerprint(KB_ID));
    }

    private Document document(String versionId) {
        Document document = new Document();
        document.setKbId(KB_ID);
        document.setCurrentVersionId(versionId);
        return document;
    }

    private IkDict dictEntry(String word) {
        IkDict entry = new IkDict();
        entry.setWord(word);
        entry.setDictType(DictType.EXT);
        entry.setStatus(DictStatus.ENABLED);
        return entry;
    }
}
