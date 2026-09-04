package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 最终注册申请；email 不在此请求中，服务端只信任票据绑定值。
 *
 * @param registrationTicket 邮箱验证票据
 * @param clientSubmissionId 浏览器生成的一次提交幂等标识
 * @param displayName        显示名称
 * @param teamName           团队，可空
 * @param password           注册密码
 * @param applicationNote    申请说明，可空
 *
 * @author owlzhangfq@gmail.com
 */
public record SubmitRegistrationRequest(
        @NotBlank @Size(min = 43, max = 43)
        @JsonProperty("registration_ticket") String registrationTicket,
        @NotBlank
        @Pattern(regexp = "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        @JsonProperty("client_submission_id") String clientSubmissionId,
        @NotBlank @Size(max = 64)
        @JsonProperty("display_name") String displayName,
        @Size(max = 128)
        @JsonProperty("team_name") String teamName,
        @NotBlank String password,
        @Size(max = 1_000)
        @JsonProperty("application_note") String applicationNote) {
}
