package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Where one parsed page landed inside the cleaned markdown, the M14 contract section 4 page strategy.
 *
 * <p><b>Why the boundary is carried instead of being recovered.</b> Cleaning deletes header lines and
 * rewrites matches, so an offset taken on the parse artifact no longer addresses the same text
 * afterwards. The boundaries are therefore produced by the cleaning stage itself, which cleans page by
 * page and knows exactly where each cleaned page was appended; the splitter then cuts on them without
 * searching for anything.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageRange {

    /** One based page number, reported as {@code page_no} chunk metadata. */
    @JsonProperty("page_no")
    private int pageNo;

    /** Inclusive start offset in the cleaned markdown. */
    @JsonProperty("start")
    private int start;

    /** Exclusive end offset in the cleaned markdown. */
    @JsonProperty("end")
    private int end;
}
