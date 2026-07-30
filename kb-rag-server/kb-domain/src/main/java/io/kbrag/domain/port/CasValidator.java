package io.kbrag.domain.port;

import io.kbrag.domain.model.ExternalAuthOutcome;

/**
 * CAS client: builds the login redirect and validates the returned service ticket.
 *
 * <p>The whole protocol is two URLs: the browser is sent to the CAS login page, comes back with a
 * ticket, and the ticket is validated server to server against {@code /p3/serviceValidate}. The
 * server side validation is what makes the ticket worth anything - a ticket is a random string, and
 * only the CAS server can say whom it was issued to.
 *
 * @author owlzhangfq@gmail.com
 */
public interface CasValidator {

    /**
     * Whether the CAS client is configured and usable.
     *
     * @return {@code true} when a login redirect may be built
     */
    boolean available();

    /**
     * Builds the CAS login URL for a redirect.
     *
     * @param serviceUrl absolute callback URL of this deployment, echoed back with the ticket
     * @return login URL the browser is sent to
     */
    String loginRedirectUrl(String serviceUrl);

    /**
     * Validates a service ticket against the CAS server.
     *
     * <p>Never throws for a validation failure, the same discipline as the directory bind.
     *
     * @param ticket     service ticket from the callback
     * @param serviceUrl the exact service URL used on the login redirect
     * @return verdict with the asserted identity on success
     */
    ExternalAuthOutcome validate(String ticket, String serviceUrl);
}
