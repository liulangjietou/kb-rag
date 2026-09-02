package io.kbrag.parser.support;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Shared best-effort text decoding used by the txt/md/sql, csv and html parsers.
 *
 * <p>Encodings are tried in the same order as the Python service's {@code app/encoding.py}: UTF-8 with
 * a BOM stripped, then strict UTF-8, then GBK - which covers the common case of a csv/txt exported by
 * a Chinese-locale Excel or editor. The final fallback replaces undecodable bytes, so decoding never
 * throws: a parser must not fail an entire request over a handful of bad bytes.
 *
 * @author owlzhangfq@gmail.com
 */
public final class TextDecoder {

    /** UTF-8 byte order mark, stripped before decoding (the "utf-8-sig" step). */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final Charset GBK = Charset.forName("GBK");

    private TextDecoder() {
    }

    /**
     * Decodes bytes to text, trying common encodings before giving up.
     *
     * @param content raw file bytes
     * @return decoded text; never null, never throws
     */
    public static String decode(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        if (startsWithBom(content)) {
            String stripped = decodeStrictly(content, UTF8_BOM.length, StandardCharsets.UTF_8);
            if (stripped != null) {
                return stripped;
            }
        }
        String utf8 = decodeStrictly(content, 0, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = decodeStrictly(content, 0, GBK);
        if (gbk != null) {
            return gbk;
        }
        // Lossy last resort, mirroring Python's errors="replace".
        return new String(content, StandardCharsets.UTF_8);
    }

    private static boolean startsWithBom(byte[] content) {
        if (content.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (content[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes with malformed/unmappable input reported rather than replaced, so a wrong guess is
     * detected instead of silently producing replacement characters.
     *
     * @return the decoded text, or null when the bytes are not valid in this charset
     */
    private static String decodeStrictly(byte[] content, int offset, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(content, offset, content.length - offset)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }
}
