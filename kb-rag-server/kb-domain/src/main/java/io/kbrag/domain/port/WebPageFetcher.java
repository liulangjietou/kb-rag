package io.kbrag.domain.port;

import io.kbrag.domain.model.FetchCredential;

/**
 * Outbound port of the web page fetcher used by URL import, the M12 contract section 3.3.
 *
 * <p>Kept as a port so the sync semantics - unchanged detection, trash skip, rebinding after a
 * purge - can be unit tested against a canned page instead of a HTTP server, and so the transport
 * details (redirect policy, size cap, content type whitelist) stay in one infrastructure class.
 *
 * <p>The request travels as one value object rather than a growing parameter list: M17 added the
 * render switch, M18 the credential, and every implementation takes the same shape. The credential
 * arrives resolved - the caller looked it up and derived the header - so no fetcher ever queries
 * the database or knows an authentication scheme.
 *
 * @author owlzhangfq@gmail.com
 */
public interface WebPageFetcher {

    /**
     * Fetches one page, following a bounded number of redirects with SSRF re-validation per hop.
     *
     * @param request what to fetch and how
     * @return page body and the file extension its content type maps to
     */
    FetchedPage fetch(FetchRequest request);

    /**
     * One fetch order.
     *
     * @param url        address to fetch, already validated once by the caller
     * @param renderJs   {@code true} renders the page in a headless browser and returns the
     *                   rendered DOM; {@code false} fetches the server HTML as is (the M12 behaviour)
     * @param credential resolved site credential, {@code null} for an anonymous fetch; the fetcher
     *                   sends its header only to requests whose host the credential names
     */
    record FetchRequest(String url, boolean renderJs, FetchCredential credential) {
    }

    /**
     * One fetched page.
     *
     * @param body      raw response body
     * @param extension file extension the content type maps to: html, txt or md
     * @param finalUrl  address the fetch actually ended on, redirects resolved; what the login wall
     *                  detection judges, because a wall usually lives on a different URL than the
     *                  one registered
     */
    record FetchedPage(byte[] body, String extension, String finalUrl) {
    }
}
