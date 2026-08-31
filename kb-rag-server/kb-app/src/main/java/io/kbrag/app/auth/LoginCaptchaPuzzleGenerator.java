package io.kbrag.app.auth;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;

/**
 * 生成固定尺寸、纯内存的登录拼图图片。
 *
 * <p>响应只包含可展示的背景、拼图片和纵向位置；横向答案只作为内部结果交给
 * {@link LoginCaptchaService} 缓存。使用 {@link MemoryCacheImageOutputStream} 明确禁止
 * ImageIO 回退到临时文件，固定像素尺寸和输出上限共同约束单次匿名请求的内存消耗。
 * 该拼图仍只是低成本自动化门槛，不是强真人证明。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
final class LoginCaptchaPuzzleGenerator {

    static final int IMAGE_WIDTH = 320;
    static final int IMAGE_HEIGHT = 160;
    static final int PIECE_WIDTH = 48;
    static final int PIECE_HEIGHT = 48;

    private static final int HORIZONTAL_MARGIN = 16;
    private static final int MIN_GAP_X = 64;
    private static final int MAX_GAP_X = IMAGE_WIDTH - PIECE_WIDTH - HORIZONTAL_MARGIN;
    private static final int MIN_GAP_Y = 16;
    private static final int MAX_GAP_Y = IMAGE_HEIGHT - PIECE_HEIGHT - HORIZONTAL_MARGIN;
    private static final int TRACK_SCALE = 1_000;
    private static final int PIECE_INSET = 2;
    private static final int PIECE_ARC = 12;
    private static final int MAX_PNG_BYTES = 512 * 1_024;
    private static final int PNG_BUFFER_BYTES = 64 * 1_024;
    private static final int DECORATION_COUNT = 14;
    private static final String PNG_FORMAT = "png";
    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";

    private final SecureRandom secureRandom;

    LoginCaptchaPuzzleGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * 生成一组背景、拼图片及仅供服务端校验的横向目标。
     *
     * @param challengeId challenge 标识，用于派生无状态背景纹理
     * @return 固定尺寸拼图
     */
    Puzzle generate(String challengeId) {
        int gapX = randomBetween(MIN_GAP_X, MAX_GAP_X);
        int gapY = randomBetween(MIN_GAP_Y, MAX_GAP_Y);
        BufferedImage background = renderBackground(challengeId);
        BufferedImage piece = cutPiece(background, gapX, gapY);
        drawGap(background, gapX, gapY);
        int targetX = Math.round((float) gapX * TRACK_SCALE / (IMAGE_WIDTH - PIECE_WIDTH));
        return new Puzzle(toPngDataUrl(background), toPngDataUrl(piece), targetX, gapY);
    }

    private BufferedImage renderBackground(String challengeId) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            long seed = seed(challengeId);
            Color start = color(seed, 0);
            Color end = color(seed, 24);
            graphics.setPaint(new GradientPaint(0, 0, start, IMAGE_WIDTH, IMAGE_HEIGHT, end));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            long state = seed;
            for (int i = 0; i < DECORATION_COUNT; i++) {
                state = mix(state + i);
                int diameter = 18 + (int) Math.floorMod(state >>> 8, 42L);
                int x = (int) Math.floorMod(state, IMAGE_WIDTH + diameter) - diameter / 2;
                int y = (int) Math.floorMod(state >>> 16, IMAGE_HEIGHT + diameter) - diameter / 2;
                int alpha = 24 + (int) Math.floorMod(state >>> 32, 42L);
                graphics.setColor(new Color(255, 255, 255, alpha));
                graphics.fill(new Ellipse2D.Float(x, y, diameter, diameter));
            }

            graphics.setColor(new Color(255, 255, 255, 34));
            graphics.setStroke(new BasicStroke(1.2F));
            for (int x = 0; x < IMAGE_WIDTH; x += 32) {
                graphics.drawLine(x, 0, IMAGE_WIDTH - x / 2, IMAGE_HEIGHT);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private BufferedImage cutPiece(BufferedImage background, int gapX, int gapY) {
        BufferedImage piece = new BufferedImage(PIECE_WIDTH, PIECE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = piece.createGraphics();
        try {
            configure(graphics);
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(0, 0, PIECE_WIDTH, PIECE_HEIGHT);
            graphics.setComposite(AlphaComposite.SrcOver);
            Shape shape = pieceShape(0, 0);
            graphics.setClip(shape);
            graphics.drawImage(background, -gapX, -gapY, null);
            graphics.setClip(null);
            graphics.setColor(new Color(255, 255, 255, 220));
            graphics.setStroke(new BasicStroke(2F));
            graphics.draw(shape);
        } finally {
            graphics.dispose();
        }
        return piece;
    }

    private void drawGap(BufferedImage background, int gapX, int gapY) {
        Graphics2D graphics = background.createGraphics();
        try {
            configure(graphics);
            Shape gap = pieceShape(gapX, gapY);
            graphics.setColor(new Color(15, 23, 42, 150));
            graphics.fill(gap);
            graphics.setColor(new Color(255, 255, 255, 210));
            graphics.setStroke(new BasicStroke(2F));
            graphics.draw(gap);
        } finally {
            graphics.dispose();
        }
    }

    private Shape pieceShape(int x, int y) {
        return new RoundRectangle2D.Float(x + PIECE_INSET, y + PIECE_INSET,
                PIECE_WIDTH - PIECE_INSET * 2F, PIECE_HEIGHT - PIECE_INSET * 2F,
                PIECE_ARC, PIECE_ARC);
    }

    private String toPngDataUrl(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(PNG_FORMAT);
        if (!writers.hasNext()) {
            throw imageFailure(null);
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(PNG_BUFFER_BYTES);
             MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
            imageOutput.flush();
            if (output.size() > MAX_PNG_BYTES) {
                throw imageFailure(null);
            }
            return PNG_DATA_URL_PREFIX + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException | RuntimeException e) {
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw imageFailure(e);
        } finally {
            writer.dispose();
        }
    }

    private BizException imageFailure(Throwable cause) {
        log.error("login captcha image generation failed, errorCode={}", ErrorCode.INTERNAL_ERROR, cause);
        return cause == null
                ? new BizException(ErrorCode.INTERNAL_ERROR, "验证码图片生成失败")
                : new BizException(ErrorCode.INTERNAL_ERROR, "验证码图片生成失败", cause);
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private int randomBetween(int min, int max) {
        return min + secureRandom.nextInt(max - min + 1);
    }

    private long seed(String challengeId) {
        String digest = HashUtil.sha256Hex(challengeId);
        long seed = 0L;
        for (int i = 0; i < 16; i++) {
            seed = (seed << 4) | Character.digit(digest.charAt(i), 16);
        }
        return seed;
    }

    private Color color(long seed, int shift) {
        int red = 48 + (int) ((seed >>> shift) & 0x7F);
        int green = 64 + (int) ((seed >>> (shift + 7)) & 0x7F);
        int blue = 96 + (int) ((seed >>> (shift + 14)) & 0x7F);
        return new Color(red, green, blue);
    }

    private long mix(long value) {
        long mixed = value;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        return mixed ^ mixed >>> 33;
    }

    /** 横向答案只在应用服务内部流转，不进入 API DTO。 */
    record Puzzle(String backgroundImage, String pieceImage, int targetX, int pieceY) {
    }
}
