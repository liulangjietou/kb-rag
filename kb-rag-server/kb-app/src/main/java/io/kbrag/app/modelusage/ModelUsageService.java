package io.kbrag.app.modelusage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.entity.ModelPrice;
import io.kbrag.domain.entity.ModelUsage;
import io.kbrag.domain.entity.ModelUsageMonthly;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.mapper.ModelPriceMapper;
import io.kbrag.domain.mapper.ModelUsageMapper;
import io.kbrag.domain.mapper.ModelUsageMonthlyMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelCostTotal;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.model.ModelUsageContext;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Durable implementation of the model call meter and its management queries.
 *
 * <p>Reservation is the quota decision: it atomically adds a conservative upper bound to the one
 * tenant-month counter before network I/O starts. Success exchanges that reservation for provider
 * usage; failure releases it. This is intentionally not a read-SUM-write sequence, whose concurrent
 * callers can all pass against the same stale sum.
 *
 * <p>Price is snapshotted into every ledger row. A later price update only affects later calls, so a
 * historical report never changes underneath an invoice or capacity review. Missing usage and missing
 * price stay visible as separate counters rather than being disguised as exact zeroes.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelUsageService implements ModelCallMeter {

    static final String STATUS_RESERVED = "RESERVED";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";

    private static final int ENABLED = 1;
    private static final int TRUE = 1;
    private static final int FALSE = 0;
    private static final long TOKENS_PER_PRICE_UNIT = 1_000_000L;
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");

    private final ModelUsageMapper usageMapper;
    private final ModelUsageMonthlyMapper monthlyMapper;
    private final ModelPriceMapper priceMapper;
    private final TenantMapper tenantMapper;
    private final BizIdGenerator idGenerator;
    private final KbProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelCallTicket reserve(ModelCallSpec spec) {
        ModelUsageContext context = requireContext();
        String month = currentMonth();
        if (!takeQuota(context.tenantId(), month, spec.reservedTokens())) {
            Tenant tenant = findTenant(context.tenantId());
            if (tenant == null) {
                log.error("model usage tenant not found, errorCode={}, tenantId={}",
                        ErrorCode.INTERNAL_ERROR, context.tenantId());
                throw new BizException(ErrorCode.INTERNAL_ERROR, "模型调用缺少有效租户归属");
            }
            log.info("model call rejected by tenant quota, tenantId={}, month={}, requested={}, quota={}",
                    context.tenantId(), month, spec.reservedTokens(), quotaOf(tenant));
            throw new BizException(ErrorCode.MODEL_QUOTA_EXCEEDED,
                    "租户 " + context.tenantId() + " 本月模型 Token 配额已不足");
        }

        ModelPrice price = activePrice(spec);
        ModelUsage usage = new ModelUsage();
        usage.setUsageId(idGenerator.modelUsageId());
        usage.setTenantId(context.tenantId());
        usage.setRequestId(RequestIdHolder.get());
        usage.setSource(context.source());
        usage.setSourceId(context.sourceId());
        usage.setProvider(spec.provider());
        usage.setCapability(spec.capability());
        usage.setModel(spec.model());
        usage.setStatus(STATUS_RESERVED);
        usage.setReservedTokens(spec.reservedTokens());
        usage.setInputTokens(0L);
        usage.setOutputTokens(0L);
        usage.setTotalTokens(0L);
        usage.setEstimated(FALSE);
        usage.setPriced(price == null ? FALSE : TRUE);
        usage.setCostMicros(0L);
        if (price != null) {
            usage.setCurrency(price.getCurrency());
            usage.setInputPriceMicros(price.getInputPriceMicros());
            usage.setOutputPriceMicros(price.getOutputPriceMicros());
        }
        if (usageMapper.insert(usage) != 1) {
            log.error("model usage ledger reservation not inserted, errorCode={}, usageId={}",
                    ErrorCode.INTERNAL_ERROR, usage.getUsageId());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模型用量预占记录失败");
        }
        return new ModelCallTicket(usage.getUsageId(), spec.reservedTokens(), true);
    }

    /**
     * Takes the tenant month's quota gate, creating the counter row only when it is genuinely absent.
     *
     * <p><b>The order of these three statements is the fix for a production deadlock.</b> The counter row
     * used to be ensured on every call, before the update. INSERT IGNORE against an existing row takes a
     * shared lock on it to resolve the duplicate key, and the update that follows needs an exclusive lock
     * on that same row - so two concurrent reservations for one tenant month each held S and each waited
     * for X, which InnoDB resolves by killing one of them. One document's embedding batches run in
     * parallel and all bill the same tenant month, so a large document hit this reliably.
     *
     * <p>Attempting the update first removes the upgrade entirely on the hot path: the row exists for
     * every call but the very first of a tenant's month, and a single UPDATE takes one exclusive lock and
     * never waits on itself. Concurrent reservations still serialise on that row, which is the point -
     * a quota gate has to be decided one caller at a time. Serialising is not deadlocking.
     *
     * <p><b>Why the existence check rather than just retrying the insert.</b> An update that changes no
     * row means one of two things: the row is missing, or the quota is exhausted. Only the first calls for
     * an insert. Being over quota lasts until the month rolls over, so letting INSERT IGNORE answer the
     * question would put its shared lock back on every rejected call for the rest of the month - the same
     * deadlock, reached along the failure path.
     *
     * @param tenantId   tenant business id
     * @param usageMonth billing month, {@code YYYY-MM}
     * @param tokens     tokens to put in flight
     * @return {@code true} when the reservation was taken
     */
    private boolean takeQuota(String tenantId, String usageMonth, long tokens) {
        if (monthlyMapper.reserve(tenantId, usageMonth, tokens) == 1) {
            return true;
        }
        if (monthlyMapper.countRow(tenantId, usageMonth) > 0) {
            return false;
        }
        // First call of this tenant month. A concurrent caller may win the insert, which is why the
        // reservation is retried rather than assumed: INSERT IGNORE reports 0 for the loser too.
        monthlyMapper.ensure(tenantId, usageMonth);
        return monthlyMapper.reserve(tenantId, usageMonth, tokens) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void succeed(ModelCallTicket ticket, ModelTokenUsage providerUsage) {
        if (!ticket.tracked()) {
            return;
        }
        ModelUsage usage = requireReserved(ticket.usageId());
        if (usage == null) {
            return;
        }
        boolean estimated = providerUsage == null || !providerUsage.known() || providerUsage.totalTokens() <= 0;
        long charged = estimated ? ticket.reservedTokens() : providerUsage.totalTokens();
        long input = estimated ? charged : providerUsage.inputTokens();
        long output = estimated ? 0L : providerUsage.outputTokens();
        long cost = costOf(usage, input, output, charged, estimated);

        usage.setStatus(STATUS_SUCCEEDED);
        usage.setInputTokens(input);
        usage.setOutputTokens(output);
        usage.setTotalTokens(charged);
        usage.setEstimated(estimated ? TRUE : FALSE);
        usage.setCostMicros(cost);
        usage.setCompletedAt(LocalDateTime.now());
        if (usageMapper.updateById(usage) != 1) {
            return;
        }
        requireMonthlyUpdate(monthlyMapper.settle(
                usage.getTenantId(), monthOf(usage), ticket.reservedTokens(), charged), usage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fail(ModelCallTicket ticket, Throwable cause) {
        if (!ticket.tracked()) {
            return;
        }
        ModelUsage usage = requireReserved(ticket.usageId());
        if (usage == null) {
            return;
        }
        usage.setStatus(STATUS_FAILED);
        usage.setErrorType(errorTypeOf(cause));
        usage.setCompletedAt(LocalDateTime.now());
        if (usageMapper.updateById(usage) != 1) {
            return;
        }
        requireMonthlyUpdate(monthlyMapper.release(
                usage.getTenantId(), monthOf(usage), ticket.reservedTokens()), usage);
    }

    /**
     * Conservatively settles reservations left by a process death. The process may have died after the
     * provider accepted the request, so releasing would silently undercount real spend; charging the
     * reservation as estimated keeps quota fail-closed. Ordinary provider failures release synchronously.
     * Each row is claimed through optimistic locking before the shared counter is touched, so two
     * instances cannot settle the same reservation twice.
     */
    @Scheduled(cron = "${kb.model-usage.reconcile-cron:0 5 * * * *}")
    @Transactional(rollbackFor = Exception.class)
    public void reconcileStaleReservations() {
        int timeoutMinutes = Math.max(5, properties.getModelUsage().getReservationTimeoutMinutes());
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<ModelUsage> stale = usageMapper.selectList(new LambdaQueryWrapper<ModelUsage>()
                .eq(ModelUsage::getStatus, STATUS_RESERVED)
                .lt(ModelUsage::getCreatedAt, staleBefore)
                .orderByAsc(ModelUsage::getId)
                .last("limit " + Math.max(1, properties.getModelUsage().getReconcileBatchSize())));
        for (ModelUsage usage : stale) {
            try {
                succeed(new ModelCallTicket(usage.getUsageId(), usage.getReservedTokens(), true),
                        ModelTokenUsage.unknown());
            } catch (Exception e) {
                log.error("stale model reservation not reconciled, errorCode={}, usageId={}",
                        ErrorCode.INTERNAL_ERROR, usage.getUsageId(), e);
            }
        }
        if (CollectionUtils.isNotEmpty(stale)) {
            log.info("stale model reservations conservatively settled, candidates={}", stale.size());
        }
    }

    /** Returns one tenant-month quota and cost summary. */
    public ModelUsageSummary summary(String tenantId, String monthText) {
        Tenant tenant = requireTenant(tenantId);
        YearMonth month = parseMonth(monthText);
        ModelUsageMonthly counter = monthlyMapper.selectOne(new LambdaQueryWrapper<ModelUsageMonthly>()
                .eq(ModelUsageMonthly::getTenantId, tenantId)
                .eq(ModelUsageMonthly::getUsageMonth, month.toString())
                .last("limit 1"));
        long used = counter == null || counter.getUsedTokens() == null ? 0L : counter.getUsedTokens();
        long reserved = counter == null || counter.getReservedTokens() == null ? 0L : counter.getReservedTokens();
        long quota = quotaOf(tenant);
        Long remaining = quota == 0L ? null : Math.max(0L, quota - used - reserved);
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<ModelCostTotal> costs = usageMapper.sumCostByCurrency(tenantId, start, end);
        return new ModelUsageSummary(tenantId, month.toString(), quota, used, reserved, remaining,
                usageMapper.countEstimated(tenantId, start, end),
                usageMapper.countUnpriced(tenantId, start, end),
                costs == null ? List.of() : costs);
    }

    /** Pages safe ledger dimensions for one tenant-month, newest first. */
    public IPage<ModelUsage> records(String tenantId, String monthText, long page, long size) {
        requireTenant(tenantId);
        YearMonth month = parseMonth(monthText);
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        return usageMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ModelUsage>()
                .eq(ModelUsage::getTenantId, tenantId)
                .ge(ModelUsage::getCreatedAt, start)
                .lt(ModelUsage::getCreatedAt, end)
                .orderByDesc(ModelUsage::getId));
    }

    /** Lists active and disabled price rows; prices are global platform configuration. */
    public List<ModelPrice> listPrices() {
        return priceMapper.selectList(new LambdaQueryWrapper<ModelPrice>()
                .orderByAsc(ModelPrice::getProvider)
                .orderByAsc(ModelPrice::getCapability)
                .orderByAsc(ModelPrice::getModel));
    }

    /** Creates or replaces the live price of one provider/capability/model tuple. */
    @Transactional(rollbackFor = Exception.class)
    public ModelPrice savePrice(String provider, String capability, String model, String currency,
                                long inputPriceMicros, long outputPriceMicros, boolean enabled) {
        String normalizedProvider = required(provider, "provider").toLowerCase(Locale.ROOT);
        String normalizedCapability = required(capability, "capability").toUpperCase(Locale.ROOT);
        requireCapability(normalizedCapability);
        String normalizedModel = required(model, "model");
        String normalizedCurrency = required(currency, "currency").toUpperCase(Locale.ROOT);
        if (!normalizedCurrency.matches("^[A-Z]{3}$")) {
            throw BizException.invalidParam("currency 必须是 3 位 ISO 4217 大写代码");
        }
        if (inputPriceMicros < 0 || outputPriceMicros < 0) {
            throw BizException.invalidParam("模型价格不能为负数");
        }
        ModelPrice price = priceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getProvider, normalizedProvider)
                .eq(ModelPrice::getCapability, normalizedCapability)
                .eq(ModelPrice::getModel, normalizedModel)
                .last("limit 1"));
        if (price == null) {
            price = new ModelPrice();
            price.setProvider(normalizedProvider);
            price.setCapability(normalizedCapability);
            price.setModel(normalizedModel);
        }
        price.setCurrency(normalizedCurrency);
        price.setInputPriceMicros(inputPriceMicros);
        price.setOutputPriceMicros(outputPriceMicros);
        price.setEnabled(enabled ? TRUE : FALSE);
        if (price.getId() == null) {
            priceMapper.insert(price);
        } else {
            priceMapper.updateById(price);
        }
        return price;
    }

    private ModelUsage requireReserved(String usageId) {
        ModelUsage usage = usageMapper.selectOne(new LambdaQueryWrapper<ModelUsage>()
                .eq(ModelUsage::getUsageId, usageId)
                .last("limit 1"));
        return usage != null && STATUS_RESERVED.equals(usage.getStatus()) ? usage : null;
    }

    private ModelPrice activePrice(ModelCallSpec spec) {
        return priceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getProvider, spec.provider())
                .eq(ModelPrice::getCapability, spec.capability())
                .eq(ModelPrice::getModel, spec.model())
                .eq(ModelPrice::getEnabled, ENABLED)
                .last("limit 1"));
    }

    private void requireMonthlyUpdate(int updated, ModelUsage usage) {
        if (updated == 1) {
            return;
        }
        log.error("model usage monthly counter not updated, errorCode={}, usageId={}, tenantId={}",
                ErrorCode.INTERNAL_ERROR, usage.getUsageId(), usage.getTenantId());
        throw new BizException(ErrorCode.INTERNAL_ERROR, "模型用量月度计数器更新失败");
    }

    private long costOf(ModelUsage row, long input, long output, long charged, boolean estimated) {
        if (row.getPriced() == null || row.getPriced() != TRUE) {
            return 0L;
        }
        long inputPrice = valueOf(row.getInputPriceMicros());
        long outputPrice = valueOf(row.getOutputPriceMicros());
        if (estimated) {
            return ceilPrice(charged, Math.max(inputPrice, outputPrice));
        }
        return Math.addExact(ceilPrice(input, inputPrice), ceilPrice(output, outputPrice));
    }

    private long ceilPrice(long tokens, long priceMicros) {
        if (tokens == 0L || priceMicros == 0L) {
            return 0L;
        }
        BigInteger product = BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(priceMicros));
        BigInteger divisor = BigInteger.valueOf(TOKENS_PER_PRICE_UNIT);
        return product.add(divisor.subtract(BigInteger.ONE)).divide(divisor).longValueExact();
    }

    private ModelUsageContext requireContext() {
        ModelUsageContext context = ModelUsageContextHolder.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            log.error("model usage context missing, errorCode={}", ErrorCode.INTERNAL_ERROR);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模型调用缺少租户计量上下文");
        }
        return context;
    }

    private Tenant requireTenant(String tenantId) {
        Tenant tenant = findTenant(tenantId);
        if (tenant == null) {
            throw BizException.notFound("tenant not found: " + tenantId);
        }
        return tenant;
    }

    private Tenant findTenant(String tenantId) {
        return tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, tenantId)
                .last("limit 1"));
    }

    private long quotaOf(Tenant tenant) {
        return tenant.getMonthlyTokenQuota() == null ? 0L : Math.max(0L, tenant.getMonthlyTokenQuota());
    }

    private YearMonth parseMonth(String text) {
        try {
            return text == null || text.isBlank() ? YearMonth.now(BILLING_ZONE) : YearMonth.parse(text);
        } catch (DateTimeParseException e) {
            throw BizException.invalidParam("month 必须使用 YYYY-MM 格式");
        }
    }

    private String currentMonth() {
        return YearMonth.now(BILLING_ZONE).toString();
    }

    private String monthOf(ModelUsage usage) {
        LocalDateTime createdAt = usage.getCreatedAt() == null ? LocalDateTime.now() : usage.getCreatedAt();
        return YearMonth.from(createdAt).toString();
    }

    private String errorTypeOf(Throwable cause) {
        if (cause instanceof ProviderException providerException) {
            return providerException.getErrorType().name();
        }
        if (cause instanceof BizException bizException) {
            return bizException.getErrorCode().name();
        }
        return cause == null ? "UNKNOWN" : cause.getClass().getSimpleName();
    }

    private void requireCapability(String capability) {
        if (!List.of(ModelCallSpec.CHAT, ModelCallSpec.EMBEDDING, ModelCallSpec.RERANK,
                ModelCallSpec.VISION, ModelCallSpec.MULTIMODAL_EMBEDDING).contains(capability)) {
            throw BizException.invalidParam("不支持的模型能力: " + capability);
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw BizException.invalidParam(name + " 不能为空");
        }
        return value.trim();
    }

    private long valueOf(Long value) {
        return value == null ? 0L : value;
    }
}
