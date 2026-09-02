package io.kbrag.app.modelusage;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.entity.ModelPrice;
import io.kbrag.domain.entity.ModelUsage;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.mapper.ModelPriceMapper;
import io.kbrag.domain.mapper.ModelUsageMapper;
import io.kbrag.domain.mapper.ModelUsageMonthlyMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.model.ModelUsageContext;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers quota fail-closed reservation and exact/estimated settlement of the durable model ledger.
 *
 * @author owlzhangfq@gmail.com
 */
class ModelUsageServiceTest {

    private static final String TENANT_ID = "tnt_acme";
    private static final String USAGE_ID = "mu_123";

    private ModelUsageMapper usageMapper;
    private ModelUsageMonthlyMapper monthlyMapper;
    private ModelPriceMapper priceMapper;
    private TenantMapper tenantMapper;
    private ModelUsageService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(ModelUsage.class, ModelPrice.class, Tenant.class);
        usageMapper = mock(ModelUsageMapper.class);
        monthlyMapper = mock(ModelUsageMonthlyMapper.class);
        priceMapper = mock(ModelPriceMapper.class);
        tenantMapper = mock(TenantMapper.class);
        BizIdGenerator idGenerator = mock(BizIdGenerator.class);
        when(idGenerator.modelUsageId()).thenReturn(USAGE_ID);
        when(usageMapper.insert(any(ModelUsage.class))).thenReturn(1);
        when(monthlyMapper.settle(anyString(), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(monthlyMapper.release(anyString(), anyString(), anyLong())).thenReturn(1);
        service = new ModelUsageService(usageMapper, monthlyMapper, priceMapper, tenantMapper,
                idGenerator, new KbProperties());
        ModelUsageContextHolder.set(new ModelUsageContext(
                TENANT_ID, ModelUsageContext.SOURCE_CONSOLE, "usr_1"));
    }

    @AfterEach
    void tearDown() {
        ModelUsageContextHolder.clear();
    }

    @Test
    void shouldRejectBeforeInsertingLedgerWhenAtomicQuotaReservationFails() {
        when(monthlyMapper.reserve(anyString(), anyString(), anyLong())).thenReturn(0);
        // The counter row is there - the reservation failed on the quota, not on a missing row.
        when(monthlyMapper.countRow(anyString(), anyString())).thenReturn(1);
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT_ID);
        tenant.setMonthlyTokenQuota(100L);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        BizException failure = assertThrows(BizException.class,
                () -> service.reserve(new ModelCallSpec("dashscope", ModelCallSpec.CHAT, "qwen", 50)));

        assertEquals(ErrorCode.MODEL_QUOTA_EXCEEDED, failure.getErrorCode());
        verify(usageMapper, never()).insert(any(ModelUsage.class));
        // Over quota lasts all month. Inserting here would put INSERT IGNORE's shared lock back on
        // every rejected call, which is the deadlock this ordering exists to avoid.
        verify(monthlyMapper, never()).ensure(anyString(), anyString());
    }

    @Test
    void shouldNotInsertTheCounterRowOnTheHotPath() {
        when(monthlyMapper.reserve(anyString(), anyString(), anyLong())).thenReturn(1);

        service.reserve(new ModelCallSpec("dashscope", ModelCallSpec.EMBEDDING, "text-embedding-v4", 100));

        // The regression under guard: ensuring the row on every call made INSERT IGNORE take a shared
        // lock on it, which the following UPDATE then had to upgrade. One document's parallel embedding
        // batches all bill the same tenant month, so two of them deadlocked instead of queuing.
        verify(monthlyMapper, never()).ensure(anyString(), anyString());
        verify(monthlyMapper).reserve(anyString(), anyString(), anyLong());
    }

    @Test
    void shouldCreateTheCounterRowAndRetryOnTheFirstCallOfATenantMonth() {
        // Missing row first, present after the insert.
        when(monthlyMapper.reserve(anyString(), anyString(), anyLong())).thenReturn(0, 1);
        when(monthlyMapper.countRow(anyString(), anyString())).thenReturn(0);
        when(monthlyMapper.ensure(anyString(), anyString())).thenReturn(1);

        ModelCallTicket ticket = service.reserve(
                new ModelCallSpec("dashscope", ModelCallSpec.EMBEDDING, "text-embedding-v4", 100));

        assertEquals(USAGE_ID, ticket.usageId());
        verify(monthlyMapper).ensure(anyString(), anyString());
        verify(monthlyMapper, times(2)).reserve(anyString(), anyString(), anyLong());
    }

    @Test
    void shouldSnapshotPriceWhenReservationSucceeds() {
        when(monthlyMapper.reserve(anyString(), anyString(), anyLong())).thenReturn(1);
        ModelPrice price = price(2_000_000L, 4_000_000L);
        when(priceMapper.selectOne(any())).thenReturn(price);

        ModelCallTicket ticket = service.reserve(
                new ModelCallSpec("dashscope", ModelCallSpec.CHAT, "qwen", 1000));

        ArgumentCaptor<ModelUsage> inserted = ArgumentCaptor.forClass(ModelUsage.class);
        verify(usageMapper).insert(inserted.capture());
        assertEquals(USAGE_ID, ticket.usageId());
        assertEquals("CNY", inserted.getValue().getCurrency());
        assertEquals(2_000_000L, inserted.getValue().getInputPriceMicros());
        assertEquals(4_000_000L, inserted.getValue().getOutputPriceMicros());
    }

    @Test
    void shouldSettleProviderTokensAndSnapshottedCost() {
        ModelUsage reserved = reservedUsage(1_000_000L, 2_000_000L, 4_000_000L);
        when(usageMapper.selectOne(any())).thenReturn(reserved);
        when(usageMapper.updateById(any(ModelUsage.class))).thenReturn(1);

        service.succeed(new ModelCallTicket(USAGE_ID, 1_000_000L, true),
                new ModelTokenUsage(500_000L, 250_000L, 750_000L, true));

        assertEquals(ModelUsageService.STATUS_SUCCEEDED, reserved.getStatus());
        assertEquals(2_000_000L, reserved.getCostMicros());
        assertEquals(0, reserved.getEstimated());
        verify(monthlyMapper).settle(TENANT_ID, reserved.getCreatedAt().toLocalDate().withDayOfMonth(1)
                .toString().substring(0, 7), 1_000_000L, 750_000L);
    }

    @Test
    void shouldChargeReservationAtTheHigherRateWhenProviderOmitsUsage() {
        ModelUsage reserved = reservedUsage(100L, 2_000_000L, 4_000_000L);
        when(usageMapper.selectOne(any())).thenReturn(reserved);
        when(usageMapper.updateById(any(ModelUsage.class))).thenReturn(1);

        service.succeed(new ModelCallTicket(USAGE_ID, 100L, true), ModelTokenUsage.unknown());

        assertEquals(100L, reserved.getTotalTokens());
        assertEquals(400L, reserved.getCostMicros());
        assertEquals(1, reserved.getEstimated());
    }

    @Test
    void shouldConservativelySettleStaleReservationBecauseProviderAcceptanceIsUnknown() {
        ModelUsage reserved = reservedUsage(100L, 2_000_000L, 4_000_000L);
        when(usageMapper.selectList(any())).thenReturn(List.of(reserved));
        when(usageMapper.selectOne(any())).thenReturn(reserved);
        when(usageMapper.updateById(any(ModelUsage.class))).thenReturn(1);

        service.reconcileStaleReservations();

        assertEquals(ModelUsageService.STATUS_SUCCEEDED, reserved.getStatus());
        assertEquals(1, reserved.getEstimated());
        verify(monthlyMapper).settle(anyString(), anyString(), anyLong(), anyLong());
        verify(monthlyMapper, never()).release(anyString(), anyString(), anyLong());
    }

    @Test
    void shouldFailSettlementWhenTheMonthlyCounterCannotMove() {
        ModelUsage reserved = reservedUsage(100L, 2_000_000L, 4_000_000L);
        when(usageMapper.selectOne(any())).thenReturn(reserved);
        when(usageMapper.updateById(any(ModelUsage.class))).thenReturn(1);
        when(monthlyMapper.settle(anyString(), anyString(), anyLong(), anyLong())).thenReturn(0);

        BizException failure = assertThrows(BizException.class,
                () -> service.succeed(new ModelCallTicket(USAGE_ID, 100L, true),
                        new ModelTokenUsage(50L, 10L, 60L, true)));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
    }

    private ModelPrice price(long input, long output) {
        ModelPrice price = new ModelPrice();
        price.setCurrency("CNY");
        price.setInputPriceMicros(input);
        price.setOutputPriceMicros(output);
        price.setEnabled(1);
        return price;
    }

    private ModelUsage reservedUsage(long reservation, long inputPrice, long outputPrice) {
        ModelUsage usage = new ModelUsage();
        usage.setUsageId(USAGE_ID);
        usage.setTenantId(TENANT_ID);
        usage.setStatus(ModelUsageService.STATUS_RESERVED);
        usage.setReservedTokens(reservation);
        usage.setPriced(1);
        usage.setCurrency("CNY");
        usage.setInputPriceMicros(inputPrice);
        usage.setOutputPriceMicros(outputPrice);
        usage.setCreatedAt(LocalDateTime.of(2026, 8, 14, 12, 0));
        return usage;
    }
}
