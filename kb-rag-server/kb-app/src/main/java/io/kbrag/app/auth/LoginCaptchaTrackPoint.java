package io.kbrag.app.auth;

/**
 * 一次滑块操作中的归一化轨迹点。
 *
 * @param x         横坐标，范围由 {@link LoginCaptchaChallenge#trackScale()} 定义
 * @param y         相对起点的纵向偏移
 * @param elapsedMs 从按下滑块开始经过的毫秒数
 *
 * @author owlzhangfq@gmail.com
 */
public record LoginCaptchaTrackPoint(int x, int y, long elapsedMs) {
}
