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
     * <p><b>The mapping profile travels as content, not as a reference.</b> The profiles are maintained in
     * the console and held in MySQL, so sending only their name would ask the parser to resolve a file it
     * does not have. The name is still sent alongside: it identifies the profile in the parser's log lines,
     * and it is what the parser falls back to resolving locally when this side has no body for it - which is
     * how an import naming a profile that was never seeded keeps working.
     *
     * @param fileName       original file name, forwarded for diagnostics
     * @param fileExt        lower case extension without the dot, {@code csv}, {@code xlsx}, {@code txt} or
     *                       {@code html}
     * @param mappingProfile mapping profile name, {@code null} selects the parser default
     * @param profileYaml    full YAML body of the profile, {@code null} letting the parser resolve the name
     *                       against its own copies
     * @param content        raw file bytes
     * @return conversations and the counters of the messages that were dropped
     */
    ParsedChatFile parseChat(String fileName, String fileExt, byte[] content, String mappingProfile,
                             String profileYaml);

    /**
     * Probes parser connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
