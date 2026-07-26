package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ParsedChatFile;
import io.kbrag.domain.model.ParsedDocument;

/**
 * Outbound port of the Python parser service.
 *
 * <p>The parser never calls a model provider: it returns structured text and image object keys,
 * every model invocation stays on the Java side.
 *
 * @author owlzhangfq@gmail.com
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
     * Parses a chat export into conversations.
     *
     * @param fileName       original file name, forwarded for diagnostics
     * @param fileExt        lower case extension without the dot, {@code csv} or {@code xlsx}
     * @param content        raw file bytes
     * @param mappingProfile column mapping profile name, {@code null} selects the parser default
     * @return conversations and the counters of the messages that were dropped
     */
    ParsedChatFile parseChat(String fileName, String fileExt, byte[] content, String mappingProfile);

    /**
     * Probes parser connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
