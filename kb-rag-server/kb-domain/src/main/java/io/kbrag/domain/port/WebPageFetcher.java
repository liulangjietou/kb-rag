package io.kbrag.domain.port;

/**
 * Outbound port of the web page fetcher used by URL import, the M12 contract section 3.3.
 *
 * <p>Kept as a port so the sync semantics - unchanged detection, trash skip, rebinding after a
 * purge - can be unit tested against a canned page instead of a HTTP server, and so the transport
 * details (redirect policy, size cap, content type whitelist) stay in one infrastructure class.
 *
 * @author owlzhangfq@gmail.com
 */
public interface WebPageFetcher {

    /**
     * Fetches one page, following a bounded number of redirects with SSRF re-validation per hop.
     *
     * @param url address to fetch, already validated once by the caller
     * @return page body and the file extension its content type maps to
     */
    FetchedPage fetch(String url);

    /**
     * One fetched page.
     *
     * @param body      raw response body
     * @param extension file extension the content type maps to: html, txt or md
     */
    record FetchedPage(byte[] body, String extension) {
    }
}
