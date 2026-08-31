package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录滑块 challenge 响应。
 *
 * @param challengeId     挑战标识
 * @param trackScale      前端归一化轨迹的横坐标上限
 * @param expiresInSeconds 挑战有效期
 * @param backgroundImage 背景 PNG Data URL
 * @param pieceImage      透明拼图片 PNG Data URL
 * @param imageWidth      背景宽度
 * @param imageHeight     背景高度
 * @param pieceWidth      拼图片宽度
 * @param pieceHeight     拼图片高度
 * @param pieceY          拼图片纵向位置；横向答案不会出现在响应中
 *
 * @author owlzhangfq@gmail.com
 */
public record CaptchaChallengeResponse(
        @JsonProperty("challenge_id") String challengeId,
        @JsonProperty("track_scale") int trackScale,
        @JsonProperty("expires_in_seconds") long expiresInSeconds,
        @JsonProperty("background_image") String backgroundImage,
        @JsonProperty("piece_image") String pieceImage,
        @JsonProperty("image_width") int imageWidth,
        @JsonProperty("image_height") int imageHeight,
        @JsonProperty("piece_width") int pieceWidth,
        @JsonProperty("piece_height") int pieceHeight,
        @JsonProperty("piece_y") int pieceY) {
}
