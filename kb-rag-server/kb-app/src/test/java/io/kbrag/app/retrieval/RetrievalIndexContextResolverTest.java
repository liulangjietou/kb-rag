package io.kbrag.app.retrieval;

import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the three calling contexts of requirement section 4.4 and the snapshot fallback of section 4.7: a
 * released version reads its own frozen indices, everything else reads the live aliases, a version released
 * before snapshots existed reads the live aliases without being reported as degraded, and a frozen index that
 * vanished degrades instead of failing.
 *
 * @author owlzhangfq@gmail.com
 */
class RetrievalIndexContextResolverTest {

    private static final String KB_ID = "kb_alpha";
    private static final String ES_ALIAS = "kb_alpha_es";
    private static final String VECTOR_ALIAS = "kb_alpha_qdrant";
    private static final String SNAPSHOT_ES = "kb_alpha_bm25_s1";
    private static final String SNAPSHOT_VECTOR = "kb_alpha_tev4_s1";
    private static final String ACTIVE_VERSION = "dv_current";
    private static final String FROZEN_VERSION = "dv_frozen";

    private IndexAliasManager indexAliasManager;
    private ActiveVersionResolver activeVersionResolver;
    private FulltextStore fulltextStore;
    private VectorStore vectorStore;
    private RetrievalIndexContextResolver resolver;

    @BeforeEach
    void setUp() {
        indexAliasManager = mock(IndexAliasManager.class);
        activeVersionResolver = mock(ActiveVersionResolver.class);
        fulltextStore = mock(FulltextStore.class);
        vectorStore = mock(VectorStore.class);
        when(indexAliasManager.fulltextAlias(KB_ID)).thenReturn(ES_ALIAS);
        when(indexAliasManager.vectorAlias(KB_ID)).thenReturn(VECTOR_ALIAS);
        when(activeVersionResolver.activeVersionIds(KB_ID)).thenReturn(List.of(ACTIVE_VERSION));
        // A pass-through ACL: document visibility is not the decision under test here, and trimming
        // nothing keeps every version-set assertion below meaningful.
        DocumentAclService documentAclService = mock(DocumentAclService.class);
        when(documentAclService.trimRestricted(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        resolver = new RetrievalIndexContextResolver(indexAliasManager, activeVersionResolver,
                documentAclService, fulltextStore, vectorStore);
    }

    @Test
    void shouldReadTheFrozenSnapshotWhenAReleasedVersionServesTheCall() {
        when(fulltextStore.indexExists(SNAPSHOT_ES)).thenReturn(true);
        when(vectorStore.indexExists(SNAPSHOT_VECTOR)).thenReturn(true);

        RetrievalIndexContextResolver.IndexContext context = resolver.resolve(KB_ID, snapshotBoundCommand());

        assertEquals(SNAPSHOT_ES, context.fulltextIndex());
        assertEquals(SNAPSHOT_VECTOR, context.vectorIndex());
        // The frozen set, not today's active versions: filtering a snapshot by the current pointer is what made
        // a rollback recall nothing.
        assertEquals(List.of(FROZEN_VERSION), context.visibleVersionIds());
        assertTrue(context.snapshotBound());
        assertFalse(context.snapshotDegraded());
        verify(activeVersionResolver, never()).activeVersionIds(anyString());
    }

    @Test
    void shouldReadTheLiveAliasWhenNoSnapshotIsBound() {
        RetrievalIndexContextResolver.IndexContext context = resolver.resolve(KB_ID,
                RetrievalCommand.builder().query("q").build());

        // The console debug page, a chat preview, an evaluation run and a beta call against a test version all
        // land here: they exist to observe the corpus as it is now.
        assertEquals(ES_ALIAS, context.fulltextIndex());
        assertEquals(VECTOR_ALIAS, context.vectorIndex());
        assertEquals(List.of(ACTIVE_VERSION), context.visibleVersionIds());
        assertFalse(context.snapshotBound());
        assertFalse(context.snapshotDegraded());
        verify(fulltextStore, never()).indexExists(anyString());
    }

    @Test
    void shouldNotReportALegacyReleaseWithoutASnapshotAsDegraded() {
        // A version released before this milestone carries neither column. That is a historical data shape and
        // not a fault, so it must not be reported: a marker here would make every legacy call look broken.
        RetrievalIndexContextResolver.IndexContext context = resolver.resolve(KB_ID,
                RetrievalCommand.builder()
                        .query("q")
                        .indexOverride(Map.of())
                        .visibleVersionIdsOverride(Map.of())
                        .build());

        assertEquals(ES_ALIAS, context.fulltextIndex());
        assertEquals(List.of(ACTIVE_VERSION), context.visibleVersionIds());
        assertFalse(context.snapshotDegraded());
    }

    @Test
    void shouldDegradeToTheLiveAliasWhenTheFrozenFulltextIndexIsGone() {
        when(fulltextStore.indexExists(SNAPSHOT_ES)).thenReturn(false);

        RetrievalIndexContextResolver.IndexContext context = resolver.resolve(KB_ID, snapshotBoundCommand());

        assertTrue(context.snapshotDegraded());
        assertFalse(context.snapshotBound());
        // Both halves fall back together: the live index with the frozen set would filter out everything
        // indexed since the release.
        assertEquals(ES_ALIAS, context.fulltextIndex());
        assertEquals(VECTOR_ALIAS, context.vectorIndex());
        assertEquals(List.of(ACTIVE_VERSION), context.visibleVersionIds());
    }

    @Test
    void shouldDegradeWhenOnlyTheFrozenVectorIndexIsGone() {
        when(fulltextStore.indexExists(SNAPSHOT_ES)).thenReturn(true);
        when(vectorStore.indexExists(SNAPSHOT_VECTOR)).thenReturn(false);

        RetrievalIndexContextResolver.IndexContext context = resolver.resolve(KB_ID, snapshotBoundCommand());

        assertTrue(context.snapshotDegraded());
        assertEquals(ES_ALIAS, context.fulltextIndex());
    }

    @Test
    void shouldFallBackWhenTheFrozenVisibilitySetIsEmptyForTheBase() {
        // Half a binding is no binding: a snapshot index with no frozen versions would recall nothing, so the
        // call is served live rather than emptily.
        RetrievalIndexContextResolver.IndexContext context = resolver.resolve(KB_ID,
                RetrievalCommand.builder()
                        .query("q")
                        .indexOverride(Map.of(KB_ID, new RetrievalIndexOverride(SNAPSHOT_ES, SNAPSHOT_VECTOR)))
                        .visibleVersionIdsOverride(Map.of(KB_ID, List.of()))
                        .build());

        assertEquals(ES_ALIAS, context.fulltextIndex());
        assertFalse(context.snapshotBound());
        assertFalse(context.snapshotDegraded());
    }

    private RetrievalCommand snapshotBoundCommand() {
        return RetrievalCommand.builder()
                .query("q")
                .indexOverride(Map.of(KB_ID, new RetrievalIndexOverride(SNAPSHOT_ES, SNAPSHOT_VECTOR)))
                .visibleVersionIdsOverride(Map.of(KB_ID, List.of(FROZEN_VERSION)))
                .build();
    }
}
