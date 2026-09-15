package io.kbrag.app.workspace;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;

/** 对员工返回稳定安全的文案，不把提供商异常或内部配置存进会话。 */
record EmployeeRunFailure(ErrorCode code, String message) {

    static EmployeeRunFailure from(Throwable error) {
        ErrorCode code = error instanceof BizException business ? business.getErrorCode() : ErrorCode.INTERNAL_ERROR;
        String message = switch (code) {
            case FORBIDDEN, UNAUTHORIZED, NOT_FOUND, APP_NOT_FOUND -> "当前权限或资料状态已变化，本次生成已停止";
            case KNOWLEDGE_SNAPSHOT_UNAVAILABLE -> "已发布的知识快照不可用，请联系管理员重新发布";
            case VERSION_NOT_FOUND, VERSION_NOT_PUBLISHED -> "应用版本当前不可用，请联系管理员";
            case MODEL_QUOTA_EXCEEDED -> "模型用量已达限额，请联系管理员";
            case RATE_LIMITED -> "当前问答繁忙，请稍后重试";
            case UPSTREAM_MODEL_ERROR -> "生成服务暂不可用，请稍后重试";
            default -> "回答未能完整生成或保存，请稍后重试";
        };
        return new EmployeeRunFailure(code, message);
    }
}
