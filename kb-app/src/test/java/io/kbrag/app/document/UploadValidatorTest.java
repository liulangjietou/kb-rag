package io.kbrag.app.document;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the single fast-fail gate of the upload path.
 */
class UploadValidatorTest {

    private static final byte[] PDF_HEADER = {0x25, 0x50, 0x44, 0x46, 0x2D};
    private static final byte[] ZIP_HEADER = {0x50, 0x4B, 0x03, 0x04, 0x14};

    private UploadValidator validator;

    @BeforeEach
    void setUp() {
        KbProperties properties = new KbProperties();
        validator = new UploadValidator(properties);
    }

    @Test
    void shouldAcceptPdfWithMatchingHeader() {
        assertEquals("pdf", validator.validate("guide.pdf", PDF_HEADER));
    }

    @Test
    void shouldAcceptDocxAsZipContainer() {
        assertEquals("docx", validator.validate("report.DOCX", ZIP_HEADER));
    }

    @Test
    void shouldAcceptTextFormatsWithoutMagicNumber() {
        assertEquals("md", validator.validate("notes.md", "# title".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectRenamedFile() {
        BizException e = assertThrows(BizException.class,
                () -> validator.validate("payload.pdf", ZIP_HEADER));
        assertEquals("INVALID_PARAM", e.getErrorCode().name());
    }

    @Test
    void shouldRejectUnsupportedExtension() {
        assertThrows(BizException.class,
                () -> validator.validate("binary.exe", "MZ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectMissingExtension() {
        assertThrows(BizException.class,
                () -> validator.validate("noextension", "text".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectEmptyFile() {
        assertThrows(BizException.class, () -> validator.validate("empty.txt", new byte[0]));
    }

    @Test
    void shouldRejectOversizedFile() {
        KbProperties properties = new KbProperties();
        properties.getUpload().setMaxFileSizeMb(1);
        UploadValidator small = new UploadValidator(properties);
        byte[] oversized = new byte[2 * 1024 * 1024];
        oversized[0] = 0x25;
        assertThrows(BizException.class, () -> small.validate("big.txt", oversized));
    }
}
