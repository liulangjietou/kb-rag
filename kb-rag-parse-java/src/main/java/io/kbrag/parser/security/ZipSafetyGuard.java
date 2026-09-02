package io.kbrag.parser.security;

import io.kbrag.parser.config.ParserConstants;
import io.kbrag.parser.error.ErrorCode;
import io.kbrag.parser.error.ZipSafetyException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.IOException;
import java.util.Enumeration;

/**
 * Zip safety precheck for the zip-based office formats (docx/xlsx), requirement doc §4.2.
 *
 * <p>Guards against zip-slip path traversal and zip-bomb (decompressed size / entry count) attacks
 * before the bytes are ever handed to POI, and without extracting anything to disk. The archive is
 * read through an in-memory channel so the central directory - which is where the declared entry names
 * and uncompressed sizes live - is available without a temporary file.
 *
 * <p>Sizes are read from the archive's own metadata, which an attacker controls. That is deliberate
 * and sufficient here: this check is the cheap first gate, and a declared size that lies about a
 * bomb still has to get past POI's own {@code ZipSecureFile} inflation-ratio limits downstream. What
 * this gate does buy is that the obvious bomb never reaches the parser at all.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class ZipSafetyGuard {

    private static final String PATH_TRAVERSAL_SEGMENT = "..";

    private ZipSafetyGuard() {
    }

    /**
     * Prechecks a zip-based office document with the contract's default limits.
     *
     * @param content raw docx/xlsx bytes
     */
    public static void ensureZipIsSafe(byte[] content) {
        ensureZipIsSafe(content, ParserConstants.MAX_ZIP_UNCOMPRESSED_TOTAL_BYTES,
                ParserConstants.MAX_ZIP_ENTRY_COUNT);
    }

    /**
     * Prechecks a zip-based office document before parsing it.
     *
     * @param content                    raw docx/xlsx bytes
     * @param maxTotalUncompressedBytes  total uncompressed size limit across all entries
     * @param maxEntryCount              entry count limit
     */
    public static void ensureZipIsSafe(byte[] content, long maxTotalUncompressedBytes, int maxEntryCount) {
        try (ZipFile archive = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(content))
                .get()) {
            int entryCount = 0;
            long totalUncompressed = 0;
            Enumeration<ZipArchiveEntry> entries = archive.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > maxEntryCount) {
                    log.error("zip rejected, errorCode={}, reason=entry_count_bomb, entryCount={}, limit={}",
                            ErrorCode.PARSE_FAILED, entryCount, maxEntryCount);
                    throw new ZipSafetyException(
                            "zip entry count " + entryCount + " exceeds limit " + maxEntryCount);
                }
                if (isPathEscaping(entry.getName())) {
                    log.error("zip rejected, errorCode={}, reason=zip_slip, entryName={}",
                            ErrorCode.PARSE_FAILED, entry.getName());
                    throw new ZipSafetyException(
                            "zip entry '" + entry.getName() + "' escapes the archive root");
                }
                totalUncompressed += Math.max(entry.getSize(), 0);
                if (totalUncompressed > maxTotalUncompressedBytes) {
                    log.error("zip rejected, errorCode={}, reason=size_bomb, uncompressedBytes={}, limitBytes={}",
                            ErrorCode.PARSE_FAILED, totalUncompressed, maxTotalUncompressedBytes);
                    throw new ZipSafetyException("zip uncompressed size " + totalUncompressed
                            + " bytes exceeds limit " + maxTotalUncompressedBytes + " bytes");
                }
            }
        } catch (IOException ex) {
            log.error("zip rejected, errorCode={}, reason=bad_zip_file", ErrorCode.PARSE_FAILED);
            throw new ZipSafetyException("not a valid zip package: " + ex.getMessage(), ex);
        }
    }

    /**
     * Detects zip-slip: an absolute path, a Windows drive-letter path, or an entry that normalizes
     * outside the archive root.
     *
     * <p>Normalization is done by hand rather than through {@code Paths.get} because the entry name is
     * an archive-internal path with its own (always forward-slash) grammar, not a path on this host's
     * filesystem - and on Windows the platform resolver would additionally reinterpret characters that
     * are perfectly legal inside a zip.
     */
    private static boolean isPathEscaping(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return false;
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            return true;
        }
        if (entryName.length() >= 2 && entryName.charAt(1) == ':') {
            return true;
        }
        int depth = 0;
        for (String segment : entryName.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if (PATH_TRAVERSAL_SEGMENT.equals(segment)) {
                depth--;
                if (depth < 0) {
                    return true;
                }
            } else {
                depth++;
            }
        }
        return false;
    }
}
