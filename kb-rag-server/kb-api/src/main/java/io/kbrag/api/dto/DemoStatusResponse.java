package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.system.DemoImportService;

/**
 * State of the bundled demo document set.
 *
 * <p>{@code available} and {@code imported} are separate flags because the console reacts differently to
 * each: material that was never mounted greys the button out, material that was already imported turns it
 * into a link to the knowledge base.
 *
 * @param available {@code true} when the demo material is mounted and readable
 * @param imported  {@code true} when the demo knowledge base already exists
 * @param kbId      knowledge base business id, {@code null} before the first import
 * @param docCount  documents currently in the demo knowledge base
 *
 * @author owlzhangfq@gmail.com
 */
public record DemoStatusResponse(
        boolean available,
        boolean imported,
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("doc_count") long docCount) {

    /**
     * Maps an application view onto the transport shape.
     *
     * @param status application view
     * @return transport response
     */
    public static DemoStatusResponse from(DemoImportService.DemoStatus status) {
        return new DemoStatusResponse(status.available(), status.imported(), status.kbId(),
                status.docCount());
    }
}
