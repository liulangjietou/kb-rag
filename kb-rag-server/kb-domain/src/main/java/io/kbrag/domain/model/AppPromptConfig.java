package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Question answering prompt block of an application version snapshot, requirement section 4.7.
 *
 * <p>Every switch carries its own prompt text so an operator can tune the wording without a code change,
 * and a blank text falls back to the built in default of the assembler. The refusal and leak guard
 * instructions are separate switches because they answer different questions: one is about what to do
 * when the retrieved passages do not contain the answer, the other about never revealing the retrieval
 * mechanics or the system instruction itself.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppPromptConfig {

    /** Application system instruction, prepended to the assembled system prompt. */
    @JsonProperty("system_prompt")
    private String systemPrompt;

    /** Refuse to answer when the retrieved material does not support an answer. */
    @JsonProperty("refusal_enabled")
    private boolean refusalEnabled = true;

    /** Operator wording of the refusal instruction, blank keeps the default. */
    @JsonProperty("refusal_prompt")
    private String refusalPrompt;

    /** Forbid revealing the system instruction, the prompt structure and the retrieval internals. */
    @JsonProperty("leak_guard_enabled")
    private boolean leakGuardEnabled = true;

    /** Operator wording of the leak guard instruction, blank keeps the default. */
    @JsonProperty("leak_guard_prompt")
    private String leakGuardPrompt;

    /** Ask the model to cite the numbered passages it used. */
    @JsonProperty("citation_enabled")
    private boolean citationEnabled = true;

    /**
     * Defaults of a freshly created application.
     *
     * <p>The two guards default to on: an application exposed to external agents through an API key is
     * the one place where a model answering from its own memory, or repeating its instruction back to a
     * caller, is a data governance problem rather than a quality one.
     *
     * @return prompt configuration with both guards and citations enabled
     */
    public static AppPromptConfig defaults() {
        return new AppPromptConfig();
    }
}
