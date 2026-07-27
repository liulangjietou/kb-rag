package io.kbrag.app.graph;

import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.model.GraphSummary;

/**
 * What the console's knowledge graph tab shows above the entity list, requirement section 4.9.
 *
 * <p>The switch travels with the counts on purpose: a base showing three zeros because nobody enabled the
 * graph and one showing three zeros because the extraction has not run yet need completely different
 * actions from the operator, and the counts alone cannot tell them apart.
 *
 * @param graphEnabled knowledge base level graph switch
 * @param counts       size of the graph, zeros when no graph is reachable
 * @param latestTask   most recent extraction task, {@code null} when none ever ran
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphSummaryView(boolean graphEnabled, GraphSummary counts, KbTask latestTask) {
}
