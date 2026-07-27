package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.dict.IkDictService;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.DictType;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fingerprints the corpus state an evaluation run measured against, requirement section 4.6.
 *
 * <p>Three ingredients, each one able to silently change what a run actually measured: the active
 * version set (a document update swaps in different chunks under the same knowledge base), the
 * embedding model (a different model reads the same text into a different vector space) and the ik
 * dictionary (a term added or removed changes what BM25 can match). Two runs are only placed side by
 * side by the compare endpoint when both this fingerprint and {@code dataset_revision} agree - proof
 * that neither the case set nor the corpus moved between them.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class CorpusFingerprintFactory {

    private static final String SEPARATOR = "|";

    private final DocumentMapper documentMapper;
    private final EmbeddingProvider embeddingProvider;
    private final IkDictService ikDictService;

    /**
     * Computes the corpus fingerprint of a knowledge base at this instant.
     *
     * @param kbId knowledge base business id
     * @return hexadecimal digest
     */
    public String fingerprint(String kbId) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getCurrentVersionId));
        List<String> activeVersionIds = documents.stream()
                .map(Document::getCurrentVersionId)
                .sorted()
                .toList();
        String embeddingSegment = embeddingProvider.model() + ":" + embeddingProvider.dimension();
        String dictSegment = ikDictService.render(DictType.EXT).getEtag()
                + ":" + ikDictService.render(DictType.STOP).getEtag();
        return HashUtil.sha256Hex(String.join(SEPARATOR,
                "versions=" + String.join(",", activeVersionIds),
                "embedding=" + embeddingSegment,
                "dict=" + dictSegment));
    }
}
