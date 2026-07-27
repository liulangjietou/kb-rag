package io.kbrag.domain.service;

import io.kbrag.common.util.HashUtil;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * Produces {@code chunk_text_hash}, the normalised text digest of a chunk.
 *
 * <p>The hash makes two chunks with cosmetically different but semantically identical text compare
 * equal, which is what lets a disable annotation be inherited across document versions and what
 * lets a rebuild recognise unchanged chunks.
 *
 * <p>Normalisation steps, applied in this order:
 * <ol>
 *   <li>Unicode NFKC, which folds fullwidth characters onto their halfwidth form;</li>
 *   <li>removal of every whitespace character, which absorbs differences in line wrapping and
 *       indentation introduced by the parser.</li>
 * </ol>
 * Case is intentionally preserved: two chunks that differ only in case are different text, and the
 * hash is also used for exact deduplication.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ChunkTextHasher {

    /**
     * Computes the normalised digest of a chunk text.
     *
     * @param text chunk text, {@code null} is treated as empty
     * @return 64 character hexadecimal SHA-256 digest
     */
    public String hash(String text) {
        return HashUtil.sha256Hex(normalize(text));
    }

    /**
     * Applies the normalisation used before hashing, exposed so tests and future annotation
     * migration share one definition.
     *
     * @param text chunk text, {@code null} is treated as empty
     * @return normalised text
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
