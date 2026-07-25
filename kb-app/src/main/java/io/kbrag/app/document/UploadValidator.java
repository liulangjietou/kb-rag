package io.kbrag.app.document;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * The single fast-fail gate of the upload path.
 *
 * <p>Extension, declared size and file header are checked here and nowhere else, so no downstream
 * stage repeats the checks. Comparing the magic number against the extension is what stops a
 * renamed executable from reaching the parser.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadValidator {

    private static final int BYTES_PER_MB = 1024 * 1024;

    /** Leading bytes of a PDF file. */
    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46};

    /** Leading bytes of a ZIP container, which is what docx and xlsx really are. */
    private static final byte[] MAGIC_ZIP = {0x50, 0x4B, 0x03, 0x04};

    /**
     * Extensions whose content has a recognisable header. Text formats are absent on purpose: they
     * have no magic number, so only extension and size can be verified for them.
     */
    private static final Map<String, byte[]> MAGIC_BY_EXTENSION = Map.of(
            "pdf", MAGIC_PDF,
            "docx", MAGIC_ZIP,
            "xlsx", MAGIC_ZIP);

    private final KbProperties properties;

    /**
     * Validates one upload.
     *
     * @param fileName original file name
     * @param content  raw bytes
     * @return normalised lower case extension
     */
    public String validate(String fileName, byte[] content) {
        if (fileName == null || fileName.isBlank()) {
            throw BizException.invalidParam("file name is required");
        }
        if (content == null || content.length == 0) {
            throw BizException.invalidParam("file is empty");
        }
        long maxBytes = (long) properties.getUpload().getMaxFileSizeMb() * BYTES_PER_MB;
        if (content.length > maxBytes) {
            throw BizException.invalidParam("file exceeds the limit of "
                    + properties.getUpload().getMaxFileSizeMb() + " MB");
        }
        String extension = extensionOf(fileName);
        if (!properties.getUpload().getAllowedExtensions().contains(extension)) {
            throw BizException.invalidParam("unsupported file extension: " + extension);
        }
        byte[] expected = MAGIC_BY_EXTENSION.get(extension);
        if (expected != null && !startsWith(content, expected)) {
            log.info("magic number mismatch, fileName={}, extension={}", fileName, extension);
            throw BizException.invalidParam("file content does not match the extension " + extension);
        }
        return extension;
    }

    /**
     * Extracts the lower case extension of a file name.
     *
     * @param fileName original file name
     * @return extension without the dot
     */
    public String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw BizException.invalidParam("file name has no extension");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
