package io.kbrag.parser.parser;

import io.kbrag.parser.model.ParseData;

/**
 * Strategy interface: one implementation per supported file format.
 *
 * <p>Implementations are synchronous and CPU-bound only - the outbound-request ban of requirement doc
 * §4.2 means no parser ever opens a socket. Running the call off the request thread and enforcing the
 * overall timeout is the API layer's job (see {@code web/ParseController}); parsers just do the
 * extraction.
 *
 * @author owlzhangfq@gmail.com
 */
public interface DocumentParser {

    /**
     * Parses raw file bytes into the unified result structure.
     *
     * @param content  raw bytes of the uploaded file
     * @param filename original filename, for diagnostics only
     * @return markdown, per-page content and images
     * @throws io.kbrag.parser.error.ParseException on any recoverable parse failure; anything else
     *                                              bubbles up and is normalized to PARSE_FAILED by the
     *                                              API layer
     */
    ParseData parse(byte[] content, String filename);
}
