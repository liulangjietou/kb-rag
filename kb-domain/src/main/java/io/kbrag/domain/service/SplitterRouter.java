package io.kbrag.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches to the {@link TextSplitter} bean a knowledge base's {@code index_config.split_strategy}
 * names, requirement section 4.3 "strategies are plugged in, not hard wired".
 *
 * <p>Mirrors {@link io.kbrag.domain.service.FusionRouter}: strategies are collected from the
 * container instead of being listed here, so a new one only has to declare itself as a
 * {@link TextSplitter} bean. Unlike the fusion router this one falls back to the fixed length
 * strategy rather than failing outright - a document must still index under a stale or unknown code
 * left over from a configuration nobody validates at read time, and the fixed length strategy is the
 * one every knowledge base already tolerates as its own default.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class SplitterRouter {

    private final Map<String, TextSplitter> splitters;
    private final TextSplitter defaultSplitter;

    public SplitterRouter(List<TextSplitter> availableSplitters, FixedLengthTextSplitter fixedLengthTextSplitter) {
        this.splitters = new HashMap<>(availableSplitters.size());
        for (TextSplitter splitter : availableSplitters) {
            splitters.put(splitter.strategy(), splitter);
        }
        this.defaultSplitter = fixedLengthTextSplitter;
    }

    /**
     * Resolves the splitter for a strategy code.
     *
     * @param strategyCode {@code index_config.split_strategy}, blank or unknown falls back to fixed length
     * @return matching splitter, never {@code null}
     */
    public TextSplitter resolve(String strategyCode) {
        if (strategyCode == null || strategyCode.isBlank()) {
            return defaultSplitter;
        }
        TextSplitter splitter = splitters.get(strategyCode);
        if (splitter == null) {
            log.info("no splitter registered for strategy, falling back to fixed length, strategy={}",
                    strategyCode);
            return defaultSplitter;
        }
        return splitter;
    }
}
