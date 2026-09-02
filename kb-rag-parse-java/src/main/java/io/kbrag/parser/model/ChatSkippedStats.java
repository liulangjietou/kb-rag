package io.kbrag.parser.model;

import lombok.Data;

/**
 * Counts of messages excluded from the {@code sessions[]} output, by reason.
 *
 * <p>Mutable and incremented in place while parsing: a skip is a running tally over rows/nodes, and
 * threading an immutable counter through every adapter would add ceremony without adding safety - one
 * instance belongs to one parse call and never leaves the thread that created it.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
public class ChatSkippedStats {

    private int voice;

    private int video;

    /** e.g. a row whose send_time could not be parsed in any known format. */
    private int other;

    public void incrementVoice() {
        voice++;
    }

    public void incrementVideo() {
        video++;
    }

    public void incrementOther() {
        other++;
    }
}
