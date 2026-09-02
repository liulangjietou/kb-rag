package io.kbrag.parser.web;

import io.kbrag.parser.chat.ChatLogParser;
import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.model.ApiResponse;
import io.kbrag.parser.model.ChatParseData;
import io.kbrag.parser.model.HealthResponse;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.parser.ParserRegistry;
import io.kbrag.parser.security.UploadGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * The service's three endpoints.
 *
 * <ul>
 *   <li>{@code GET  /health} - liveness probe, {@code {"status":"UP"}}
 *   <li>{@code POST /api/v1/parse} - multipart {@code file} + form {@code file_ext} -> markdown, per-page
 *       content and images (M1-CONTRACTS.md §6, extended by M3 §2.1, M8 §0.4 and M14 §F3)
 *   <li>{@code POST /api/v1/parse/chat} - multipart {@code file} + form {@code file_ext} plus the
 *       optional {@code mapping_profile}/{@code profile_yaml} -> sessions of messages
 *       (M3-CONTRACTS.md §2.2, widened by M8 §0.1/§0.2/§0.7)
 * </ul>
 *
 * <p>Security constraints enforced at this boundary (requirement doc §4.2): the upload size cap before
 * any parsing begins, and a hard timeout around every parse call. The zip-slip/zip-bomb precheck lives
 * inside the zip-based parsers, XXE hardening is applied at startup, and no outbound network call is
 * implemented anywhere in this service.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ParseController {

    private static final String PARSE_PATH = "/api/v1/parse";
    private static final String PARSE_CHAT_PATH = "/api/v1/parse/chat";
    private static final String HEALTH_PATH = "/health";

    private final ParserRegistry parserRegistry;
    private final ChatLogParser chatLogParser;
    private final ParseExecutor parseExecutor;

    /**
     * @return UP once the process is serving traffic
     */
    @GetMapping(value = HEALTH_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return HealthResponse.up();
    }

    /**
     * Parses an uploaded document into markdown, per-page content and images.
     *
     * <p>On any failure - unsupported format, security rejection, oversized file, timeout, or an
     * unexpected error from an underlying library - this answers {@code code=PARSE_FAILED} with a
     * descriptive message rather than raising, per the response contract.
     *
     * @param file    the uploaded document
     * @param fileExt extension declared by the caller, independent of the filename
     * @return the response envelope
     * @throws IOException if the upload cannot be read off the request
     */
    @PostMapping(value = PARSE_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ParseData> parseDocument(@RequestParam("file") MultipartFile file,
                                                @RequestParam("file_ext") String fileExt) throws IOException {
        String requestId = newRequestId();
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();

        return parseExecutor.run(fileExt, requestId, () -> {
            UploadGuard.ensureFileSizeWithinLimit(content, ParserConstants.MAX_FILE_SIZE_BYTES);
            return parserRegistry.getParser(fileExt).parse(content, filename);
        });
    }

    /**
     * Parses a chat-log export (csv/xlsx/txt/html) into sessions of messages.
     *
     * <p>{@code mapping_profile}, when omitted, resolves to a file_ext-appropriate built-in default.
     * {@code profile_yaml}, when given, is the profile's full YAML body and takes priority over the
     * name resolving to a bundled file (M8-CONTRACTS.md §0.7) - which is what kb-rag-server sends once
     * profiles live in {@code t_kb_source_mapping} rather than only as files here.
     *
     * <p>Same failure normalization as the document endpoint: any recoverable error - an unsupported
     * file_ext, a profile that cannot resolve the required {@code content} column / txt template / html
     * selector, an oversized file, a zip-safety rejection, a timeout - answers
     * {@code code=PARSE_FAILED} rather than raising.
     *
     * @param file           the uploaded export
     * @param fileExt        {@code csv|xlsx|txt|html}
     * @param mappingProfile optional profile name
     * @param profileYaml    optional full profile YAML body
     * @return the response envelope
     * @throws IOException if the upload cannot be read off the request
     */
    @PostMapping(value = PARSE_CHAT_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ChatParseData> parseChatLog(
            @RequestParam("file") MultipartFile file,
            @RequestParam("file_ext") String fileExt,
            @RequestParam(value = "mapping_profile", required = false) String mappingProfile,
            @RequestParam(value = "profile_yaml", required = false) String profileYaml) throws IOException {
        String requestId = newRequestId();
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String resolvedProfile = mappingProfile == null || mappingProfile.isBlank()
                ? ChatLogParser.defaultMappingProfileFor(fileExt)
                : mappingProfile;

        return parseExecutor.run(fileExt, requestId, () -> {
            UploadGuard.ensureFileSizeWithinLimit(content, ParserConstants.MAX_FILE_SIZE_BYTES);
            return chatLogParser.parse(content, filename, fileExt, resolvedProfile, profileYaml);
        });
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
