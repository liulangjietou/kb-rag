package io.kbrag.domain.service;

import io.kbrag.domain.model.ProxiedContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tells which images each chunk derives from.
 *
 * <p><b>How the correspondence is recovered.</b> The splitter emits chunks that are contiguous
 * substrings of the text it was given, in order, possibly overlapping. Locating each chunk with a
 * forward only search therefore yields its exact offset range, and a chunk owns an image whenever its
 * range intersects the range the proxy was spliced into. Matching on the proxy text instead would be
 * approximate, because a chunk boundary can fall in the middle of a description.
 *
 * <p>A chunk that cannot be located, which the trimming of an all whitespace chunk can cause, simply
 * owns no image. Reporting a wrong image is worse than reporting none: the console would show a
 * thumbnail that has nothing to do with the passage.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ImageChunkLinker {

    /**
     * Links chunks to the images their text contains.
     *
     * @param markdown      text the chunks were cut from
     * @param placements    image proxy positions inside that text
     * @param chunkContents chunk texts in emission order
     * @return object storage keys per chunk index, only for the chunks that own at least one image
     */
    public Map<Integer, List<String>> link(String markdown, List<ProxiedContent.ImagePlacement> placements,
                                           List<String> chunkContents) {
        Map<Integer, List<String>> byChunk = new java.util.LinkedHashMap<>();
        if (CollectionUtils.isEmpty(placements) || CollectionUtils.isEmpty(chunkContents)
                || markdown == null || markdown.isEmpty()) {
            return byChunk;
        }
        int cursor = 0;
        int unlocated = 0;
        for (int index = 0; index < chunkContents.size(); index++) {
            String content = chunkContents.get(index);
            if (content == null || content.isEmpty()) {
                continue;
            }
            int start = markdown.indexOf(content, cursor);
            if (start < 0) {
                unlocated++;
                continue;
            }
            // The next chunk never starts before this one, but it may start inside it because of the
            // replayed overlap, so the cursor advances to this chunk's start and not to its end.
            cursor = start;
            int end = start + content.length();
            Set<String> keys = new LinkedHashSet<>();
            for (ProxiedContent.ImagePlacement placement : placements) {
                if (placement.overlaps(start, end)) {
                    keys.add(placement.getObjectKey());
                }
            }
            if (!keys.isEmpty()) {
                byChunk.put(index, new ArrayList<>(keys));
            }
        }
        if (unlocated > 0) {
            log.info("chunks not located in the source text, count={}, chunks={}",
                    unlocated, chunkContents.size());
        }
        return byChunk;
    }
}
