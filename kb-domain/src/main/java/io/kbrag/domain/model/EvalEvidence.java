package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One relevance anchor of an evaluation case, stored inside {@code t_kb_eval_case.evidences}.
 *
 * <p>Anchored to {@code doc_id} rather than to a chunk id on purpose (requirement section 4.5): chunk
 * boundaries move whenever the split strategy or its parameters change, while the document identity
 * does not, which is what lets one evaluation case keep measuring the same knowledge across a change
 * of split strategy. {@code annotatedVersionId} is kept only as provenance, the version the excerpt
 * was copied from; hit judgment always runs against the currently active version's chunks.
 *
 * <p>{@link #span} is {@code null} for a {@link io.kbrag.domain.enums.AnchorType#DOCUMENT} case: an
 * image derived chunk carries no text worth quoting, so the anchor is the whole document instead of an
 * excerpt of it.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class EvalEvidence {

    /** Document business id the evidence belongs to, stable across versions. */
    @JsonProperty("doc_id")
    private String docId;

    /** Exact text excerpt of the document, {@code null} for a document level anchor. */
    private String span;

    /** Document version the excerpt was copied from, provenance only. */
    @JsonProperty("annotated_version_id")
    private String annotatedVersionId;
}
