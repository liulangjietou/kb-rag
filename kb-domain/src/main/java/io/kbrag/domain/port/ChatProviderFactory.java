package io.kbrag.domain.port;

/**
 * Resolves the chat provider of one generation call by model name.
 *
 * <p>Exists because an application version freezes its generation model (requirement section 4.7): a call
 * served by an old version has to reach the model that version was released with, which a single provider
 * bean pinned to the deployment default cannot do. The credential, base URL and timeout stay deployment
 * level - a version snapshot selects a model, never an account.
 *
 * @author owlzhangfq@gmail.com
 */
public interface ChatProviderFactory {

    /**
     * Provider for one model.
     *
     * @param model model name, blank or {@code null} yields the deployment default model
     * @return provider bound to that model; the unconfigured placeholder in zero key mode
     */
    ChatProvider forModel(String model);
}
