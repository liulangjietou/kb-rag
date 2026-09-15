package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.appcenter.AppVersionComparisonService;
import io.kbrag.domain.model.AppCorpusDocument;

import java.util.List;

/** 只输出授权范围内的资料名称、版本和差异，不输出正文及存储位置。 */
public record AppCorpusComparisonResponse(Corpus baseline, Corpus candidate, boolean comparable, Long total,
        Long added, Long removed, Long updated, int page, @JsonProperty("page_size") int pageSize, List<Item> items) {
    /** 将领域比较投影为稳定的接口字段。 */
    public static AppCorpusComparisonResponse from(AppVersionComparisonService.Comparison comparison) {
        return new AppCorpusComparisonResponse(Corpus.from(comparison.baseline()), Corpus.from(comparison.candidate()),
                comparison.comparable(), comparison.total(), comparison.added(), comparison.removed(), comparison.updated(),
                comparison.page(), comparison.pageSize(), comparison.items().stream().map(Item::from).toList());
    }

    public record Corpus(AppVersionComparisonService.Source source, boolean complete,
                         @JsonProperty("document_count") Integer documentCount) {
        static Corpus from(AppVersionComparisonService.CorpusSummary corpus) {
            return new Corpus(corpus.source(), corpus.complete(), corpus.documentCount());
        }
    }

    public record Version(@JsonProperty("version_id") String versionId, String version) {
        static Version from(AppCorpusDocument document) {
            return document == null ? null : new Version(document.versionId(), document.version());
        }
    }

    public record Item(@JsonProperty("kb_id") String kbId, @JsonProperty("doc_id") String docId,
                       @JsonProperty("file_name") String fileName, AppVersionComparisonService.Change change,
                       Version baseline, Version candidate) {
        static Item from(AppVersionComparisonService.DocumentChange change) {
            return new Item(change.kbId(), change.docId(), change.fileName(), change.change(),
                    Version.from(change.baseline()), Version.from(change.candidate()));
        }
    }
}
