package io.kbrag.app.retrieval;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Result of one retrieval call: the ordered nodes, the degradation markers that describe how the
 * pipeline actually ran, and the applied parameter summary the debug console displays.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class SearchOutcome {

    /** Ordered result list. */
    private final List<RetrievalNodeView> nodes;

    /** Degradation markers, empty when the full pipeline ran. */
    private final List<String> degraded;

    /** Effective pipeline parameters. */
    private final AppliedInfo applied;

    /** 内部重排测量，由控制台预览显式投影；普通检索响应不暴露此字段。 */
    private final RerankTiming rerankTiming;

    /** 保留未采集调用的构造方式，缺失测量保持为空。 */
    public SearchOutcome(List<RetrievalNodeView> nodes, List<String> degraded, AppliedInfo applied) {
        this(nodes, degraded, applied, null);
    }

    /** 将本次检索的重排测量与同一批节点一起返回。 */
    public SearchOutcome(List<RetrievalNodeView> nodes, List<String> degraded, AppliedInfo applied,
                         RerankTiming rerankTiming) {
        this.nodes = nodes;
        this.degraded = degraded;
        this.applied = applied;
        this.rerankTiming = rerankTiming;
    }
}
