package io.kbrag.parser.parser;

import io.kbrag.parser.model.PageContent;
import io.kbrag.parser.model.ParseData;
import io.kbrag.parser.support.TextDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plain-text parser for {@code .txt}, {@code .md} and {@code .sql}.
 *
 * <p>No XML, no zip and no external resources are involved, so none of the security guardrails apply
 * beyond the upload size cap the API layer already enforced. Markdown files pass through as-is since
 * they already are the target representation; plain text (including {@code .sql} scripts) is wrapped
 * verbatim rather than being decorated with invented markdown syntax.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class TextParser implements DocumentParser {

    @Override
    public ParseData parse(byte[] content, String filename) {
        String text = TextDecoder.decode(content);
        return ParseData.builder()
                .markdown(text)
                .pages(List.of(PageContent.builder().pageNo(1).text(text).markdown(text).build()))
                .build();
    }
}
