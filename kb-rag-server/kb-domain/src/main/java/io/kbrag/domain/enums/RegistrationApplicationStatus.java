package io.kbrag.domain.enums;

/**
 * 注册申请审核状态。
 *
 * @author owlzhangfq@gmail.com
 */
public enum RegistrationApplicationStatus {

    /** 等待管理员分配租户和角色。 */
    PENDING,

    /** 审核通过且已创建登录账号。 */
    APPROVED,

    /** 审核拒绝。 */
    REJECTED
}
