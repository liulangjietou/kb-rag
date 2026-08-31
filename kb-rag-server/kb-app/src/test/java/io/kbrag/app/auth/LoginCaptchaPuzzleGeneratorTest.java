package io.kbrag.app.auth;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化拼图图片的随机目标、尺寸、透明通道和无答案元数据约束。
 *
 * @author owlzhangfq@gmail.com
 */
class LoginCaptchaPuzzleGeneratorTest {

    private static final String DATA_URL_PREFIX = "data:image/png;base64,";
    private static final String FIRST_CHALLENGE = "a".repeat(43);
    private static final String SECOND_CHALLENGE = "b".repeat(43);

    @Test
    void shouldGenerateDifferentTargetsAndBoundedInMemoryPngImages() throws Exception {
        LoginCaptchaPuzzleGenerator generator = new LoginCaptchaPuzzleGenerator(
                new SequenceSecureRandom(0, 0, 100, 60));

        LoginCaptchaPuzzleGenerator.Puzzle first = generator.generate(FIRST_CHALLENGE);
        LoginCaptchaPuzzleGenerator.Puzzle second = generator.generate(SECOND_CHALLENGE);

        // gapX=64 映射到可移动宽度 320-48，而不是整张背景宽度。
        assertEquals(235, first.targetX());
        assertEquals(16, first.pieceY());
        assertEquals(603, second.targetX());
        assertEquals(76, second.pieceY());
        assertNotEquals(first.targetX(), second.targetX());
        assertNotEquals(first.pieceY(), second.pieceY());
        assertTrue(first.targetX() > 0 && first.targetX() < 1_000);
        assertTrue(first.pieceY() > 0
                && first.pieceY() + LoginCaptchaPuzzleGenerator.PIECE_HEIGHT
                < LoginCaptchaPuzzleGenerator.IMAGE_HEIGHT);
        assertTrue(first.backgroundImage().startsWith(DATA_URL_PREFIX));
        assertTrue(first.pieceImage().startsWith(DATA_URL_PREFIX));

        byte[] backgroundPng = pngBytes(first.backgroundImage());
        byte[] piecePng = pngBytes(first.pieceImage());
        BufferedImage background = ImageIO.read(new ByteArrayInputStream(backgroundPng));
        BufferedImage piece = ImageIO.read(new ByteArrayInputStream(piecePng));
        assertNotNull(background);
        assertNotNull(piece);
        assertEquals(LoginCaptchaPuzzleGenerator.IMAGE_WIDTH, background.getWidth());
        assertEquals(LoginCaptchaPuzzleGenerator.IMAGE_HEIGHT, background.getHeight());
        assertEquals(LoginCaptchaPuzzleGenerator.PIECE_WIDTH, piece.getWidth());
        assertEquals(LoginCaptchaPuzzleGenerator.PIECE_HEIGHT, piece.getHeight());
        assertEquals(0, piece.getRGB(0, 0) >>> 24);
        assertTrue(piece.getRGB(piece.getWidth() / 2, piece.getHeight() / 2) >>> 24 > 0);

        assertContainsNoTextMetadata(backgroundPng);
        assertContainsNoTextMetadata(piecePng);
    }

    private byte[] pngBytes(String dataUrl) {
        return Base64.getDecoder().decode(dataUrl.substring(DATA_URL_PREFIX.length()));
    }

    private List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = (png[offset] & 0xFF) << 24
                    | (png[offset + 1] & 0xFF) << 16
                    | (png[offset + 2] & 0xFF) << 8
                    | png[offset + 3] & 0xFF;
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            types.add(type);
            offset += 12 + length;
        }
        return types;
    }

    private void assertContainsNoTextMetadata(byte[] png) {
        List<String> chunkTypes = pngChunkTypes(png);
        assertFalse(chunkTypes.contains("tEXt"));
        assertFalse(chunkTypes.contains("zTXt"));
        assertFalse(chunkTypes.contains("iTXt"));
    }

    /** 只控制 gap 随机序列；背景纹理由 challenge 摘要确定。 */
    private static final class SequenceSecureRandom extends SecureRandom {

        private static final long serialVersionUID = 1L;

        private final int[] values;
        private int index;

        private SequenceSecureRandom(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            return Math.floorMod(values[index++], bound);
        }
    }
}
