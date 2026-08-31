package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 登录滑块轨迹校验请求。
 *
 * <p>轨迹的点数、坐标和时序由应用服务在原子消费 challenge 后统一校验；不能提前在
 * Bean Validation 拒绝，否则无效轨迹仍可重放同一个 challenge。
 *
 * @param challengeId challenge 标识
 * @param track       归一化轨迹
 *
 * @author owlzhangfq@gmail.com
 */
public record CaptchaVerifyRequest(
        @NotBlank(message = "must not be blank")
        @JsonProperty("challenge_id") String challengeId,
        List<CaptchaTrackPointRequest> track) {
}
