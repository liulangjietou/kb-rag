package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.enums.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the version visibility set cache: it is read once per knowledge base, and an activation switch drops it
 * so an operator sees the effect of the switch immediately rather than after an expiry.
 *
 * @author owlzhangfq@gmail.com
 */
class ActiveVersionResolverTest {

    private static final String KB_ID = "kb_1";
    private static final String OTHER_KB_ID = "kb_2";
    private static final String DOC_ID = "doc_1";
    private static final String FIRST_VERSION = "dv_1";
    private static final String SECOND_VERSION = "dv_2";

    private DocumentMapper documentMapper;
    private ActiveVersionResolver resolver;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class, DocumentVersion.class);
        documentMapper = mock(DocumentMapper.class);
        resolver = new ActiveVersionResolver(documentMapper, new KbProperties());
    }

    @Test
    void shouldReadTheSetOnlyOncePerKnowledgeBase() {
        when(documentMapper.selectList(any())).thenReturn(List.of(document(FIRST_VERSION)));

        assertEquals(List.of(FIRST_VERSION), resolver.activeVersionIds(KB_ID));
        assertEquals(List.of(FIRST_VERSION), resolver.activeVersionIds(KB_ID));

        // The set is the mandatory filter of every recall route, so it is read at least once per base per
        // search while it only changes when an operator switches a version.
        verify(documentMapper, times(1)).selectList(any());
    }

    @Test
    void shouldReloadTheSetAfterAnActivationInvalidatedIt() {
        when(documentMapper.selectList(any()))
                .thenReturn(List.of(document(FIRST_VERSION)))
                .thenReturn(List.of(document(SECOND_VERSION)));

        assertEquals(List.of(FIRST_VERSION), resolver.activeVersionIds(KB_ID));
        resolver.invalidate(KB_ID);

        assertEquals(List.of(SECOND_VERSION), resolver.activeVersionIds(KB_ID));
        verify(documentMapper, times(2)).selectList(any());
    }

    @Test
    void shouldInvalidateOneBaseWithoutDroppingAnother() {
        when(documentMapper.selectList(any())).thenReturn(List.of(document(FIRST_VERSION)));
        resolver.activeVersionIds(KB_ID);
        resolver.activeVersionIds(OTHER_KB_ID);

        resolver.invalidate(KB_ID);
        resolver.activeVersionIds(OTHER_KB_ID);

        // Two loads for the initial fill plus one for the reload of the invalidated base only.
        verify(documentMapper, times(2)).selectList(any());
    }

    @Test
    void shouldIgnoreAnInvalidationWithoutAKnowledgeBase() {
        resolver.invalidate(null);

        verify(documentMapper, times(0)).selectList(any());
    }

    @Test
    void shouldBeInvalidatedByTheActivationItself() {
        DocumentVersionMapper documentVersionMapper = mock(DocumentVersionMapper.class);
        when(documentVersionMapper.selectList(any())).thenReturn(List.of());
        when(documentMapper.selectList(any()))
                .thenReturn(List.of(document(FIRST_VERSION)))
                .thenReturn(List.of(document(SECOND_VERSION)));
        DocumentVersionActivator activator =
                new DocumentVersionActivator(documentMapper, documentVersionMapper, resolver);

        assertEquals(List.of(FIRST_VERSION), resolver.activeVersionIds(KB_ID));
        activator.activate(document(FIRST_VERSION), version(SECOND_VERSION));

        // The invalidation is wired into the activation rather than left to the caller, so no activation path
        // can forget it.
        assertEquals(List.of(SECOND_VERSION), resolver.activeVersionIds(KB_ID));
    }

    @Test
    void shouldFilterTheSetThroughTheGovernanceGate() {
        // The resolver is the single place governance takes effect: the trash flag, the review state
        // and both bounds of the validity window must all be part of the row filter, or a governed
        // document would keep serving queries.
        when(documentMapper.selectList(any())).thenReturn(List.of(document(FIRST_VERSION)));
        resolver.activeVersionIds(KB_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Document>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(documentMapper).selectList(captor.capture());

        String where = captor.getValue().getSqlSegment();
        assertTrue(where.contains("trashed"));
        assertTrue(where.contains("publish_status"));
        assertTrue(where.contains("effective_at IS NULL OR effective_at <="));
        assertTrue(where.contains("expires_at IS NULL OR expires_at >"));
    }

    private Document document(String currentVersionId) {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setCurrentVersionId(currentVersionId);
        document.setProcessStatus(ProcessStatus.INDEXED);
        return document;
    }

    private DocumentVersion version(String versionId) {
        DocumentVersion version = new DocumentVersion();
        version.setDocId(DOC_ID);
        version.setVersionId(versionId);
        return version;
    }
}
