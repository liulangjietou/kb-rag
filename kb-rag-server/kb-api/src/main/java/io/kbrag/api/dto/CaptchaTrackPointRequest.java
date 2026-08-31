package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 浏览器采集的单个滑块轨迹点。
 *
 * @param x         归一化横坐标
 * @param y         相对起点的纵向偏移
 * @param elapsedMs 从按下滑块开始经过的毫秒数
 *
 * @author owlzhangfq@gmail.com
 */
public record CaptchaTrackPointRequest(
        Integer x,
        Integer y,
        @JsonProperty("elapsed_ms") Long elapsedMs) {
}
