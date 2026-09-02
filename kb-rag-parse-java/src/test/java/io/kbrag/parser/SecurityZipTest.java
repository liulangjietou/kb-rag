package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.error.ZipSafetyException;
import io.kbrag.parser.security.ZipSafetyGuard;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative tests for the zip safety precheck (requirement doc §4.2): zip-slip path traversal and
 * zip-bomb rejection, both at the unit level and end to end through the docx path - docx is a zip
 * package, so the endpoint must reject a malicious archive before it ever reaches POI.
 *
 * @author owlzhangfq@gmail.com
 */
class SecurityZipTest extends ParseEndpointTestBase {

    private static byte[] buildZip(Map<String, byte[]> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return buffer.toByteArray();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // --- Unit level ---------------------------------------------------------

    @Test
    void rejectsPathTraversalEntry() {
        byte[] malicious = buildZip(Map.of("../../etc/evil.txt", bytes("pwned")));

        ZipSafetyException ex = assertThrows(ZipSafetyException.class,
                () -> ZipSafetyGuard.ensureZipIsSafe(malicious));
        assertTrue(ex.getMessage().contains("escapes"));
    }

    @Test
    void rejectsAbsolutePathEntry() {
        byte[] malicious = buildZip(Map.of("/etc/evil.txt", bytes("pwned")));

        ZipSafetyException ex = assertThrows(ZipSafetyException.class,
                () -> ZipSafetyGuard.ensureZipIsSafe(malicious));
        assertTrue(ex.getMessage().contains("escapes"));
    }

    @Test
    void allowsAnEntryThatOnlyDipsBackInsideTheRoot() {
        // "word/../word/document.xml" never leaves the archive; rejecting it would break legitimate
        // packages for no security gain.
        byte[] archive = buildZip(Map.of("word/../word/document.xml", bytes("<root/>")));

        assertDoesNotThrow(() -> ZipSafetyGuard.ensureZipIsSafe(archive));
    }

    @Test
    void rejectsTotalSizeBomb() {
        // Highly compressible payload: small on the wire, large once decompressed.
        byte[] bomb = buildZip(Map.of("word/document.xml", bytes("A".repeat(10_000))));

        ZipSafetyException ex = assertThrows(ZipSafetyException.class,
                () -> ZipSafetyGuard.ensureZipIsSafe(bomb, 1_000, ParserConstants.MAX_ZIP_ENTRY_COUNT));
        assertTrue(ex.getMessage().contains("uncompressed size"));
    }

    @Test
    void rejectsEntryCountBomb() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            entries.put("part-" + i + ".xml", bytes("x"));
        }

        ZipSafetyException ex = assertThrows(ZipSafetyException.class,
                () -> ZipSafetyGuard.ensureZipIsSafe(buildZip(entries),
                        ParserConstants.MAX_ZIP_UNCOMPRESSED_TOTAL_BYTES, 5));
        assertTrue(ex.getMessage().contains("entry count"));
    }

    @Test
    void acceptsAWellFormedArchive() {
        byte[] archive = buildZip(Map.of("word/document.xml", bytes("<root/>")));

        assertDoesNotThrow(() -> ZipSafetyGuard.ensureZipIsSafe(archive));
    }

    @Test
    void rejectsSomethingThatIsNotAZipAtAll() {
        ZipSafetyException ex = assertThrows(ZipSafetyException.class,
                () -> ZipSafetyGuard.ensureZipIsSafe(bytes("plain text, not an archive")));
        assertTrue(ex.getMessage().contains("not a valid zip package"));
    }

    // --- End to end through POST /api/v1/parse ------------------------------

    @Test
    void parseRejectsZipSlipDocx() throws Exception {
        JsonNode body = postParse("evil.docx",
                buildZip(Map.of("../../etc/evil.txt", bytes("pwned"))), "docx");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("escapes"));
    }

    @Test
    void parseRejectsZipEntryCountBombDocx() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i <= ParserConstants.MAX_ZIP_ENTRY_COUNT; i++) {
            entries.put("part-" + i + ".xml", bytes("x"));
        }

        JsonNode body = postParse("bomb.docx", buildZip(entries), "docx");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("data").isNull());
        assertTrue(body.get("message").asText().contains("entry count"));
    }

    @Test
    void parseRejectsZipSlipXlsx() throws Exception {
        JsonNode body = postParse("evil.xlsx",
                buildZip(Map.of("/etc/evil.txt", bytes("pwned"))), "xlsx");

        assertEquals("PARSE_FAILED", body.get("code").asText());
        assertTrue(body.get("message").asText().contains("escapes"));
    }
}
