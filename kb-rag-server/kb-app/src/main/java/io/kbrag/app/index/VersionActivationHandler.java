package io.kbrag.app.index;

import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.eval.EvalCaseStalenessService;
import io.kbrag.app.graph.GraphExtractionService;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Makes a freshly built version the active one and runs everything that depends on that.
 *
 * <p>Not to be confused with {@link DocumentVersionActivator}, which this class calls: the activator owns
 * the single active version invariant and nothing else, while this class owns the consequences of an
 * activation. The distinction is load bearing - the activator is also used by the chat import path and by
 * a manual rollback, which need the switch without necessarily needing the same follow ups, and the
 * invariant must not depend on any of them succeeding.
 *
 * <p>Split out of {@link IndexPipelineService} because these five collaborators exist in that class for
 * this one moment and nothing else. Gathering them here also gives the follow up chain a single place to
 * grow: the next thing that has to react to an activation is added once, not at each of the three build
 * paths that reach this point.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionActivationHandler {

    private final DocumentVersionActivator versionActivator;
    private final AnnotationInheritanceService annotationInheritanceService;
    private final EvalCaseStalenessService evalCaseStalenessService;
    private final VersionRetentionService versionRetentionService;
    private final GraphExtractionService graphExtractionService;

    /**
     * Activates a version and runs every follow up that an activation triggers.
     *
     * @param document document record
     * @param version  version that finished building
     */
    public void activateAndFollowUp(Document document, DocumentVersion version) {
        versionActivator.activate(document, version);
        annotationInheritanceService.inherit(document, version);
        evalCaseStalenessService.markStale(document.getDocId(), version.getVersionId());
        versionRetentionService.submit(document.getDocId());
        // Requirement section 4.9: an activation is what invalidates the entities and relations the
        // superseded versions contributed, otherwise the graph route would keep answering out of a corpus
        // the other two routes can no longer see - version isolation broken from the side.
        graphExtractionService.onVersionActivated(document, version);
    }
}
