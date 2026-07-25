package io.kbrag.app.dict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.IkDict;
import io.kbrag.domain.enums.DictStatus;
import io.kbrag.domain.enums.DictType;
import io.kbrag.domain.mapper.IkDictMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Domain dictionary served to the ik tokenizer.
 *
 * <p>Why this exists. A Chinese tokenizer cuts on a general purpose vocabulary, so a product code or
 * an internal term is split into fragments and BM25 then matches those fragments in unrelated
 * documents. Teaching the tokenizer the term is the only fix that works at index time, which is where
 * it has to be fixed.
 *
 * <p>The plugin polls a plain text URL and compares the {@code Last-Modified} and {@code ETag} headers
 * it received last time, so the rendered document has to be stable for an unchanged dictionary. The
 * words are therefore rendered in a fixed order and the validator hashes that exact rendering, which
 * keeps a reordered query result from looking like a change and triggering a needless reload on every
 * Elasticsearch node.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IkDictService {

    private static final String LINE_SEPARATOR = "\n";

    private final IkDictMapper ikDictMapper;

    /**
     * Lists dictionary entries.
     *
     * @param dictType optional dictionary kind filter
     * @param keyword  optional term prefix filter
     * @param page     one based page number
     * @param size     page size
     * @return page of entries
     */
    public IPage<IkDict> list(DictType dictType, String keyword, long page, long size) {
        LambdaQueryWrapper<IkDict> wrapper = new LambdaQueryWrapper<IkDict>().orderByDesc(IkDict::getId);
        if (dictType != null) {
            wrapper.eq(IkDict::getDictType, dictType);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.likeRight(IkDict::getWord, keyword.trim());
        }
        return ikDictMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * Adds a term.
     *
     * @param word     dictionary term
     * @param dictType dictionary kind
     * @param remark   free text note
     * @return created entry
     */
    public IkDict create(String word, DictType dictType, String remark) {
        String normalized = word.trim();
        // Uniqueness is on the word alone, matching the table key and the management API, which
        // addresses an entry by its word. A term that is both a domain word and a stop word is a
        // contradiction rather than two entries, so it is rejected here instead of producing a pair of
        // rows the update and delete endpoints could only pick between arbitrarily.
        if (ikDictMapper.exists(new LambdaQueryWrapper<IkDict>().eq(IkDict::getWord, normalized))) {
            throw BizException.invalidParam("dictionary word already exists");
        }
        IkDict entry = new IkDict();
        entry.setWord(normalized);
        entry.setDictType(dictType);
        entry.setStatus(DictStatus.ENABLED);
        entry.setRemark(remark);
        ikDictMapper.insert(entry);
        log.info("ik dictionary word added, word={}, dictType={}", normalized, dictType);
        return entry;
    }

    /**
     * Switches a term on or off without losing the row.
     *
     * @param word   dictionary term, unique across kinds
     * @param status new availability
     * @return updated entry
     */
    public IkDict updateStatus(String word, DictStatus status) {
        IkDict entry = require(word);
        entry.setStatus(status);
        ikDictMapper.updateById(entry);
        log.info("ik dictionary word status changed, word={}, status={}", word, status);
        return entry;
    }

    /**
     * Removes a term.
     *
     * @param word dictionary term
     */
    public void delete(String word) {
        IkDict entry = require(word);
        ikDictMapper.deleteById(entry.getId());
        log.info("ik dictionary word removed, word={}, dictType={}", word, entry.getDictType());
    }

    /**
     * Renders the dictionary the plugin downloads.
     *
     * @param dictType dictionary kind
     * @return rendered document together with its validators
     */
    public DictDocument render(DictType dictType) {
        List<IkDict> entries = ikDictMapper.selectList(new LambdaQueryWrapper<IkDict>()
                .eq(IkDict::getDictType, dictType)
                .eq(IkDict::getStatus, DictStatus.ENABLED)
                .orderByAsc(IkDict::getWord));
        String body = CollectionUtils.isEmpty(entries)
                ? ""
                : entries.stream().map(IkDict::getWord).reduce((left, right) -> left + LINE_SEPARATOR + right)
                        .orElse("") + LINE_SEPARATOR;
        long lastModified = entries.stream()
                .map(entry -> entry.getUpdatedAt() == null ? 0L
                        : entry.getUpdatedAt().atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli())
                .max(Long::compare)
                .orElse(0L);
        return new DictDocument(body, HashUtil.sha256Hex(body), lastModified, entries.size());
    }

    private IkDict require(String word) {
        IkDict entry = ikDictMapper.selectOne(new LambdaQueryWrapper<IkDict>()
                .eq(IkDict::getWord, word)
                .last("limit 1"));
        if (entry == null) {
            throw BizException.notFound("dictionary word not found");
        }
        return entry;
    }

    /**
     * Rendered dictionary together with the validators the plugin caches on.
     */
    @Getter
    public static final class DictDocument {

        /** One term per line, trailing newline included. */
        private final String body;

        /** Digest of {@link #body}, served as the entity tag. */
        private final String etag;

        /** Newest entry timestamp in epoch milliseconds, 0 for an empty dictionary. */
        private final long lastModified;

        /** Number of served terms, logged rather than exposed. */
        private final int wordCount;

        DictDocument(String body, String etag, long lastModified, int wordCount) {
            this.body = body;
            this.etag = etag;
            this.lastModified = lastModified;
            this.wordCount = wordCount;
        }
    }
}
