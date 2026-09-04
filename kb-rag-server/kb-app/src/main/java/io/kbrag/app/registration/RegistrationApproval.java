package io.kbrag.app.registration;

import io.kbrag.domain.entity.RegistrationApplication;

import java.util.List;

/**
 * 审批事务内产生的申请事实与实际绑定角色。
 *
 * @param application 已更新为通过状态的申请
 * @param roleIds     已规范化、去重并实际用于创建账号的角色
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationApproval(RegistrationApplication application, List<String> roleIds) {

    public RegistrationApproval {
        roleIds = List.copyOf(roleIds);
    }
}
