package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.UserPrincipal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 调试目录只暴露租户内且知识库范围完整匹配的版本，避免逐应用查询。 */
class AppPreviewCatalogServiceTest {

    private final AppService appService = mock(AppService.class);
    private final AppVersionService versionService = mock(AppVersionService.class);
    private final AppVersionMapper versionMapper = mock(AppVersionMapper.class);
    private final AppPreviewCatalogService service =
            new AppPreviewCatalogService(appService, versionService, versionMapper);

    @BeforeEach
    void bindUser() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AppVersion.class);
        UserContextHolder.set(new UserPrincipal("usr_1", "tnt_1", "tester", "Tester", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of("search:debug"), false, Set.of("kb_allowed")));
    }

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void shouldFilterWholeVersionsAndBatchOnlyTenantVisibleApplications() {
        App app = app("app_allowed");
        App denied = app("app_denied");
        AppVersion newestDenied = version("v_new", app, "kb_allowed", "kb_denied");
        AppVersion allowed = version("v_old", app, "kb_allowed");
        AppVersion deniedOnly = version("v_denied", denied, "kb_denied");
        when(appService.list()).thenReturn(List.of(app, denied));
        when(versionMapper.selectList(any())).thenReturn(List.of(newestDenied, allowed, deniedOnly));

        List<AppPreviewCatalogService.PreviewApplication> items = service.list();

        assertEquals(1, items.size());
        assertEquals("app_allowed", items.get(0).app().getAppId());
        assertEquals(List.of(allowed), items.get(0).versions());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AppVersion>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(versionMapper).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("app_id IN"));
        assertTrue(query.getValue().getSqlSegment().contains("ORDER BY id DESC"));
    }

    @Test
    void shouldKeepUnconfiguredDraftVisibleForActionablePreviewFeedback() {
        App app = app("app_empty");
        AppVersion draft = version("v_draft", app);
        when(appService.list()).thenReturn(List.of(app));
        when(versionMapper.selectList(any())).thenReturn(List.of(draft));

        assertEquals(List.of(draft), service.list().get(0).versions());
    }

    @Test
    void shouldNotQuerySubordinateTableWhenNoApplicationIsVisible() {
        when(appService.list()).thenReturn(List.of());
        assertEquals(List.of(), service.list());
        verifyNoInteractions(versionMapper, versionService);
    }

    @Test
    void shouldRequireAuthenticatedUserBeforeLookingUpResources() {
        UserContextHolder.clear();
        assertThrows(BizException.class, service::list);
        verifyNoInteractions(appService, versionMapper);
    }

    private App app(String id) {
        App app = new App();
        app.setAppId(id);
        return app;
    }

    private AppVersion version(String id, App app, String... kbIds) {
        AppVersion version = new AppVersion();
        version.setAppVersionId(id);
        version.setAppId(app.getAppId());
        AppConfigSnapshot config = new AppConfigSnapshot();
        config.setKbRefs(java.util.Arrays.stream(kbIds).map(KbRef::of).toList());
        when(versionService.parseConfig(version)).thenReturn(config);
        return version;
    }
}
