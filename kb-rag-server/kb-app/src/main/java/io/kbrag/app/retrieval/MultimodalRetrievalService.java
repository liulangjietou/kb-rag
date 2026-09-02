package io.kbrag.app.retrieval;

import io.kbrag.app.index.MultimodalIndexManager;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.ImageInput;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;
import io.kbrag.domain.port.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The multimodal route and the image dispatch that decides whether it runs at all, the M14 contract
 * sections 6.3 and 7.
 *
 * <p>The counterpart of {@code GraphRetrievalService}: the two are the pipeline's optional routes, they
 * return the same shape of outcome, and neither knows anything about the stages that consume it.
 * {@link MultimodalRouteOutcome} was already modelled on {@code GraphRouteOutcome}; this class completes
 * the symmetry on the executing side, so that "how a route is run" lives next to the collaborators only
 * that route needs - {@link MultimodalIndexManager}, {@link MultimodalEmbeddingProvider} and
 * {@link ImageQueryService} are read here and nowhere else in the pipeline.
 *
 * <p>What stays with {@link RetrievalService} is the decision of <em>whether</em> to ask at all: the
 * snapshot guard sits at the call site for both routes, because "a released version searches its own
 * frozen corpus" is a property of the call rather than of any one route.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalRetrievalService {

    private final MultimodalIndexManager multimodalIndexManager;
    private final MultimodalEmbeddingProvider multimodalEmbeddingProvider;
    private final ImageQueryService imageQueryService;
    private final VectorStore vectorStore;

    /**
     * A candidate knowledge base as the multimodal route sees it.
     *
     * <p>The two fields are all the route needs to address a base, which is deliberately less than the
     * pipeline's own notion of a target: a route that could read the retrieval config would be able to
     * re-decide questions the pipeline already settled.
     *
     * @param kbId        knowledge base business id
     * @param indexConfig index configuration of that base, read for the multimodal switch
     */
    public record Target(String kbId, KbIndexConfig indexConfig) {
    }

    /**
     * What the image dispatch decided: the query every pipeline stage reads and the image vectors, if any,
     * the multimodal route embeds instead of the text.
     *
     * <p>The markers are returned rather than appended to a list the caller passed in: the dispatch is one
     * more stage whose degradations the pipeline collects, and a stage that reaches into the caller's
     * accumulator cannot be tested without one.
     *
     * @param query             query the pipeline runs with, enriched by the vision fallback when it ran
     * @param imageQueryVectors embedded images for the multimodal route, empty on the fallback path
     * @param degraded          markers this dispatch produced, empty when nothing degraded
     */
    public record ImageDispatch(String query, List<float[]> imageQueryVectors, List<String> degraded) {
    }

    /**
     * Decides what the attached images do, the single dispatch point of the M14 contract section 7.
     *
     * <p>Two outcomes, one of them per call. When the searched corpus can embed images - a multimodal
     * provider is configured, at least one selected base has the multimodal switch on, the call is not a
     * frozen release snapshot and a written query is present - the pictures are embedded and steer the
     * multimodal route directly (image to image, image to rendered page). Otherwise they fall back to the
     * vision transcription that folds their description into the query, exactly the path the open API used
     * before this milestone, so a deployment without the multimodal capability keeps its image search.
     *
     * <p><b>A written query stays mandatory on the image route.</b> Pure image retrieval is out of scope for
     * this milestone, so an image only call is never routed to the multimodal space; it takes the vision
     * fallback, whose own gate rejects a call that has nothing at all to search for.
     *
     * <p><b>A release snapshot never takes the image route.</b> The multimodal index holds no frozen copy, so
     * the per base route is off on the snapshot path anyway; sending images there would embed them for a
     * route that cannot run, so a snapshot call transcribes them into its query instead.
     *
     * @param command call parameters, read for the images and the query
     * @param targets knowledge bases the call will search
     * @return the query the pipeline runs with, the image vectors the route embeds, and any markers
     */
    public ImageDispatch dispatchImages(RetrievalCommand command, List<Target> targets) {
        List<String> images = command.getImages();
        if (usableForImages(command, images, targets)) {
            List<String> degraded = new ArrayList<>(1);
            return new ImageDispatch(command.getQuery(), embedQueryImages(images, degraded), degraded);
        }
        ImageQueryService.ImageQueryOutcome outcome = imageQueryService.enrich(command.getQuery(), images);
        return new ImageDispatch(outcome.query(), List.of(), outcome.degraded());
    }

    /**
     * Runs the multimodal route, the M14 contract section 6.3.
     *
     * <p><b>Weighted fusion refuses the route and says so.</b> Weighted fusion blends normalised route
     * scores, and a multimodal similarity is on a scale the text routes never see; there is no meaningful
     * weight for it, so the route is skipped and the caller is told through {@code mm_route_skipped},
     * mirroring the way the graph route is refused under weighted fusion.
     *
     * <p><b>A configured but unreachable provider degrades, it never fails the search.</b> Embedding the
     * query into the multimodal space is the one model call this route makes; when it fails the marker
     * {@code mm_route_unavailable} is reported and the remaining routes answer.
     *
     * @param target            knowledge base being searched
     * @param query             query the other routes ran with
     * @param imageQueryVectors images the caller attached, already embedded, empty for a text only call
     * @param filter            the very predicate the engine side routes were filtered by
     * @param settings          effective retrieval parameters, read for the fusion mode and the recall size
     * @return multimodal route outcome, empty when the route was not asked to run
     */
    public MultimodalRouteOutcome recall(Target target, String query, List<float[]> imageQueryVectors,
                                         RetrievalFilter filter, RetrievalSettings settings) {
        String alias = multimodalIndexManager.multimodalAlias(target.kbId(), target.indexConfig());
        if (alias == null) {
            return MultimodalRouteOutcome.skipped();
        }
        if (settings.getFusion().getMode() == FusionMode.WEIGHTED) {
            log.info("multimodal route skipped under weighted fusion, kbId={}", target.kbId());
            return MultimodalRouteOutcome.degraded(DegradedReason.MM_ROUTE_SKIPPED.code());
        }
        List<float[]> queryVectors;
        if (CollectionUtils.isNotEmpty(imageQueryVectors)) {
            // F6: the caller attached images and this base can search them, so the route embeds the pictures
            // rather than the text - image to image and image to rendered page, the M14 contract section 7.
            queryVectors = imageQueryVectors;
        } else {
            try {
                queryVectors = List.of(multimodalEmbeddingProvider.embedTexts(List.of(query)).get(0));
            } catch (Exception e) {
                log.error("multimodal query embedding failed, errorCode={}, kbId={}",
                        ErrorCode.UPSTREAM_MODEL_ERROR, target.kbId(), e);
                return MultimodalRouteOutcome.degraded(DegradedReason.MM_ROUTE_UNAVAILABLE.code());
            }
        }
        List<ScoredChunk> candidates = searchMultimodal(alias, queryVectors, filter, settings.getRecallTopK());
        if (candidates.isEmpty()) {
            return MultimodalRouteOutcome.skipped();
        }
        log.info("multimodal route finished, kbId={}, queries={}, candidates={}",
                target.kbId(), queryVectors.size(), candidates.size());
        return MultimodalRouteOutcome.of(candidates);
    }

    /**
     * Searches the multimodal alias with every query vector and folds the hits into one ranking.
     *
     * <p>A text query issues one vector; an image query issues one per attached image. Overlapping hits are
     * collapsed on the chunk id keeping the strongest similarity, so a chunk matched by two images is ranked
     * once on its best score rather than counted twice, and the reciprocal rank fusion downstream sees a
     * single ordered list exactly like the other routes.
     *
     * @param alias        multimodal alias to search
     * @param queryVectors one or more query vectors
     * @param filter       the very predicate the engine side routes were filtered by
     * @param recallTopK   candidates the route contributes at most
     * @return merged candidates ordered by descending similarity, tagged as the multimodal route
     */
    private List<ScoredChunk> searchMultimodal(String alias, List<float[]> queryVectors,
                                               RetrievalFilter filter, int recallTopK) {
        Map<String, Double> scoreByChunk = new LinkedHashMap<>();
        for (float[] queryVector : queryVectors) {
            List<ScoredChunk> hits = vectorStore.search(alias, VectorQuery.builder()
                    .queryVector(queryVector).topK(recallTopK).filter(filter).build());
            if (CollectionUtils.isEmpty(hits)) {
                continue;
            }
            for (ScoredChunk hit : hits) {
                scoreByChunk.merge(hit.getChunkId(), hit.getScore(), Math::max);
            }
        }
        if (scoreByChunk.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> candidates = new ArrayList<>(scoreByChunk.size());
        for (Map.Entry<String, Double> entry : scoreByChunk.entrySet()) {
            candidates.add(new ScoredChunk(entry.getKey(), entry.getValue(), RetrievalSource.MM));
        }
        candidates.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed()
                .thenComparing(ScoredChunk::getChunkId));
        return candidates.size() > recallTopK
                ? new ArrayList<>(candidates.subList(0, recallTopK)) : candidates;
    }

    /**
     * Tells whether the attached images can steer the multimodal route rather than the vision fallback.
     *
     * @param command call parameters, read for the query and the snapshot binding
     * @param images  attached images
     * @param targets knowledge bases the call will search
     * @return {@code true} when the images are to be embedded into the multimodal space
     */
    private boolean usableForImages(RetrievalCommand command, List<String> images, List<Target> targets) {
        if (CollectionUtils.isEmpty(images) || !multimodalEmbeddingProvider.isConfigured()) {
            return false;
        }
        if (command.getQuery() == null || command.getQuery().isBlank()) {
            return false;
        }
        if (MapUtils.isNotEmpty(command.getIndexOverride())) {
            return false;
        }
        return targets.stream().anyMatch(target ->
                multimodalIndexManager.multimodalAlias(target.kbId(), target.indexConfig()) != null);
    }

    /**
     * Embeds the attached images into the multimodal space, degrading rather than failing when the provider
     * call itself breaks.
     *
     * <p>The count and size validation runs first and outside the try: an over sized or over counted image
     * set is a caller error the pipeline rejects, not a degradation. A provider timeout or error, by
     * contrast, leaves the written query and the other routes to answer, so it is reported through
     * {@code mm_route_unavailable} and returns no vectors.
     *
     * @param images   attached images, already known to be present
     * @param degraded markers of this dispatch, appended in place
     * @return one vector per image, or empty when the provider call failed
     */
    private List<float[]> embedQueryImages(List<String> images, List<String> degraded) {
        List<ImageInput> inputs = imageQueryService.decodeForEmbedding(images);
        try {
            return multimodalEmbeddingProvider.embedImages(inputs);
        } catch (Exception e) {
            log.error("query image embedding failed, errorCode={}, images={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, inputs.size(), e);
            degraded.add(DegradedReason.MM_ROUTE_UNAVAILABLE.code());
            return List.of();
        }
    }
}
