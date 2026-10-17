package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;

import java.util.Map;

/**
 * 结构化决策能力的出站端口（System One 模型，当前实现为 TypeSafe Jev）。
 *
 * <p>与 {@link ChatProvider} 分开建模：决策模型不生成文本，只对同一份状态并行回答多个带类型的问题，
 * 返回可直接参与代码分支的概率。把它塞进聊天端口会迫使调用方再去解析文本，正是接入它要消除的环节。
 *
 * <p>当前只暴露 Noul（是/否概率）一种题型，路由与重排都只需要它；其余题型随真实调用方一起加入。
 *
 * @author owlzhangfq@gmail.com
 */
public interface DecisionProvider {

    /**
     * 提供方名称。
     *
     * @return provider name
     */
    String providerName();

    /**
     * 实际配置的模型版本。
     *
     * @return model name
     */
    String model();

    /**
     * 是否持有可用凭据。
     *
     * @return {@code true} 表示可以发起决策调用
     */
    boolean isConfigured();

    /**
     * 对同一份状态并行评估一组是/否问题。
     *
     * @param state     被评估的内容，字符串或可序列化为 JSON 的对象
     * @param questions 问题 id 到问题内容（字符串或 JSON 对象）的映射，id 仅用于回填答案，不参与推理
     * @return 问题 id 到「是」的概率 {@code [0,1]}，与入参 id 一一对应
     */
    Map<String, Double> evaluateNouls(Object state, Map<String, Object> questions);

    /**
     * 探测连通性。
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
