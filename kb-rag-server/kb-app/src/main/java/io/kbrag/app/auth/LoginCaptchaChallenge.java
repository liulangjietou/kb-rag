package io.kbrag.app.auth;

/**
 * 浏览器执行一次滑块验证所需的短期挑战。
 *
 * @param challengeId 挑战明文，仅返回给调用方，服务端只保存摘要
 * @param trackScale  轨迹横坐标的归一化上限
 * @param expiresInSeconds 挑战有效期
 * @param backgroundImage 背景 PNG Data URL
 * @param pieceImage      透明拼图片 PNG Data URL
 * @param imageWidth      背景宽度
 * @param imageHeight     背景高度
 * @param pieceWidth      拼图片宽度
 * @param pieceHeight     拼图片高度
 * @param pieceY          拼图片纵向位置；横向答案不进入响应
 *
 * @author owlzhangfq@gmail.com
 */
public record LoginCaptchaChallenge(
        String challengeId,
        int trackScale,
        long expiresInSeconds,
        String backgroundImage,
        String pieceImage,
        int imageWidth,
        int imageHeight,
        int pieceWidth,
        int pieceHeight,
        int pieceY) {
}
