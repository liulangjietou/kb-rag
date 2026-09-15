package io.kbrag.app.quality;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.EmployeeFeedbackMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 列表的入口权限、分页根范围及内容受限投影。 */
class EmployeeFeedbackServiceTest {
    private final EmployeeFeedbackMapper mapper = mock(EmployeeFeedbackMapper.class);
    private final QualityIssueAccess quality = mock(QualityIssueAccess.class);
    private final EmployeeFeedbackAccess access = mock(EmployeeFeedbackAccess.class);
    private final EmployeeFeedbackService service = new EmployeeFeedbackService(mapper, quality, access);

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void appReadIsRequiredBeforeAnyFeedbackQuery() {
        principal(Set.of("feedback:manage"));
        assertThrows(BizException.class, () -> service.list("kb", 1, 20));
        verifyNoInteractions(mapper, quality, access);
    }

    @Test
    void mapperReceivesCurrentTenantAndScopedAppsAndRestrictedRowsKeepTheirRealCount() {
        principal(Set.of("feedback:manage", "app:read"));
        var row = new EmployeeConversationRun(); row.setRunId("opaque"); row.setQuestion("不得泄露");
        var page = new Page<EmployeeConversationRun>(1, 20, 1); page.setRecords(List.of(row));
        when(mapper.pageBad(any(), eq("tenant"), eq("kb"), eq(List.of("only-app")))).thenReturn(page);
        when(access.read("kb", "opaque")).thenThrow(BizException.forbidden("citation revoked"));
        var result = service.list("kb", 1, 20);
        assertEquals(1, result.getTotal()); assertNull(result.getRecords().get(0).readable());
        var order = inOrder(quality, mapper);
        order.verify(quality).requireKb("kb");
        order.verify(mapper).pageBad(any(), eq("tenant"), eq("kb"), eq(List.of("only-app")));
    }

    private void principal(Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("operator", "tenant", "operator", "维护人", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of(), false, Set.of("only-app")));
    }
}
