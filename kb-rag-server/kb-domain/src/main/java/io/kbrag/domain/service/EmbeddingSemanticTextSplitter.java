package io.kbrag.domain.service;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Embedding semantic splitting: embeds sentences and cuts where adjacent similarity drops.
 *
 * <p>Heading lines and blank paragraph boundaries also start new segments so structure participates in
 * the cut, requirement section 4.3 strategy 1.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingSemanticTextSplitter implements TextSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "embedding_semantic";

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？.!?])\\s+");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+");

    private final EmbeddingProvider embeddingProvider;
    private final FixedLengthTextSplitter fixedLengthTextSplitter;

    @Override
    public String strategy() {
        return STRATEGY_CODE;
    }

    @Override
    public List<SplitChunk> split(String text, SplitParams params) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (!embeddingProvider.isConfigured()) {
            throw BizException.invalidParam("the embedding semantic split strategy requires a configured "
                    + "embedding model");
        }
        List<Sentence> sentences = toSentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }
        if (sentences.size() == 1) {
            return fixedLengthTextSplitter.split(text, params);
        }
        List<float[]> vectors = embedInBatches(sentences);
        double threshold = params.semanticDropThresholdOrDefault();
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder(sentences.get(0).text());
        for (int index = 1; index < sentences.size(); index++) {
            double similarity = cosine(vectors.get(index - 1), vectors.get(index));
            Sentence sentence = sentences.get(index);
            boolean boundary = sentence.heading() || sentence.paragraphStart();
            if (boundary || similarity < threshold) {
                blocks.add(current.toString());
                current = new StringBuilder(sentence.text());
            } else {
                current.append(sentence.text());
            }
        }
        blocks.add(current.toString());
        return fixedLengthTextSplitter.packBlocks(blocks, params);
    }

    private List<float[]> embedInBatches(List<Sentence> sentences) {
        int batchSize = Math.max(1, embeddingProvider.maxBatchSize());
        List<float[]> vectors = new ArrayList<>(sentences.size());
        for (int start = 0; start < sentences.size(); start += batchSize) {
            int end = Math.min(sentences.size(), start + batchSize);
            List<String> payload = sentences.subList(start, end).stream().map(Sentence::text).toList();
            try {
                vectors.addAll(embeddingProvider.embed(payload));
            } catch (RuntimeException failure) {
                log.error("embedding semantic split failed, errorCode={}, batchStart={}",
                        ErrorCode.UPSTREAM_MODEL_ERROR, start, failure);
                throw BizException.invalidParam("embedding semantic split failed: " + failure.getMessage());
            }
        }
        if (vectors.size() != sentences.size()) {
            throw BizException.invalidParam("embedding semantic split returned mismatched vector count");
        }
        return vectors;
    }

    private List<Sentence> toSentences(String text) {
        List<Sentence> sentences = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isBlank()) {
                continue;
            }
            boolean heading = HEADING.matcher(line.trim()).matches();
            boolean paragraphStart = lineIndex == 0 || lines[lineIndex - 1].isBlank();
            if (heading) {
                sentences.add(new Sentence(line, true, paragraphStart));
                continue;
            }
            String[] parts = SENTENCE_END.split(line);
            for (int partIndex = 0; partIndex < parts.length; partIndex++) {
                String part = parts[partIndex].trim();
                if (part.isEmpty()) {
                    continue;
                }
                boolean startsParagraph = paragraphStart && partIndex == 0;
                sentences.add(new Sentence(part, false, startsParagraph));
            }
        }
        return sentences;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record Sentence(String text, boolean heading, boolean paragraphStart) {
    }
}