package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.modelusage.ModelUsageService;
import io.kbrag.domain.entity.ModelUsage;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the public resource boundary of the model usage ledger page.
 *
 * @author owlzhangfq@gmail.com
 */
class ModelUsageControllerTest {

    @Test
    void shouldClampLedgerPaginationBeforeQueryingTheDatabase() {
        ModelUsageService service = mock(ModelUsageService.class);
        when(service.records("tnt_1", "2026-08", 1L, 200L))
                .thenReturn(new Page<ModelUsage>(1L, 200L));
        ModelUsageController controller = new ModelUsageController(service);

        controller.records("tnt_1", "2026-08", 0L, 10_000L);

        verify(service).records("tnt_1", "2026-08", 1L, 200L);
    }
}
