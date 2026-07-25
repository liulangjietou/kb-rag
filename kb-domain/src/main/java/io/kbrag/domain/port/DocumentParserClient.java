package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ParsedDocument;

/**
 * Outbound port of the Python parser service.
 *
 * <p>The parser never calls a model provider: it returns structured text and image object keys,
 * every model invocation stays on the Java side.
 */
public interface DocumentParserClient {

    /**
     * Parses one file.
     *
     * @param fileName original file name, forwarded for diagnostics
     * @param fileExt  lower case extension without the dot
     * @param content  raw file bytes
     * @return parsed markdown, per page text and image object keys
     */
    ParsedDocument parse(String fileName, String fileExt, byte[] content);

    /**
     * Probes parser connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
