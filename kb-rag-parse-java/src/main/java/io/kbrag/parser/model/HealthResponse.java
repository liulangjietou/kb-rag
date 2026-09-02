package io.kbrag.parser.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response body for {@code GET /health}.
 *
 * <p>Deliberately not the actuator's health payload: kb-rag-server probes this exact
 * {@code {"status":"UP"}} shape (M1-CONTRACTS.md §0), and it must stay identical to the Python
 * service's.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@AllArgsConstructor
public class HealthResponse {

    private String status;

    public static HealthResponse up() {
        return new HealthResponse("UP");
    }
}
