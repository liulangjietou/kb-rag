package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Knowledge base routing block of an application version snapshot, requirement section 4.9 "routing".
 *
 * <p>Off by default: routing costs a model call on every request and its benefit only exists once an
 * application is linked to bases that hold clearly different material. An operator turning it on is
 * therefore an explicit statement that the extra call is worth it.
 *
 * <p>A blank prompt keeps the built in routing instruction. The operator wording replaces that
 * instruction only - the candidate list and the question are always assembled by the service, so a
 * custom prompt can never drop the candidate white list the answer is checked against.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppRoutingConfig {

    /** {@code true} when a model decides which linked bases a query is searched in. */
    @JsonProperty("enabled")
    private boolean enabled;

    /** Operator wording of the routing instruction, blank keeps the built in one. */
    @JsonProperty("prompt")
    private String prompt;

    /**
     * Block of an application that never configured routing.
     *
     * @return disabled routing with the built in prompt
     */
    public static AppRoutingConfig defaults() {
        return new AppRoutingConfig();
    }
}
