package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Decides what a repeated upload of the same logical document becomes.
 *
 * <p>Three questions are answered together because they all read the same version history and would
 * otherwise be answered from three different snapshots of it: is this upload a duplicate, which
 * version number does it get, and which earlier build can it reuse artifacts from.
 *
 * <p><b>Comparison basis.</b> Both the duplicate verdict and the major/minor choice are made against
 * the <em>active</em> version, falling back to the newest row when the document has none. Comparing
 * against the newest row alone would misfire in the one case that matters most: after a failed build
 * the newest row describes content nobody is serving, and re-uploading the very same file would then
 * be dismissed as a duplicate, leaving the document permanently unindexed.
 *
 * <p><b>Reuse.</b> Reuse looks at the whole history rather than only at the basis, because a
 * configuration that was changed and then reverted makes an <em>older</em> version the matching one.
 * That is also the only situation in which chunk level reuse can fire at all: a full four element
 * match against the active version is a duplicate and never reaches this stage.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class DocumentVersionPlanner {

    /** Separator between the major and the minor part of a version number. */
    private static final String SEPARATOR = ".";

    /** Regular expression form of {@link #SEPARATOR}. */
    private static final String SEPARATOR_PATTERN = "\\.";

    /** Number of parts a well formed version number has. */
    private static final int PARTS = 2;

    /** Index of the major part. */
    private static final int MAJOR = 0;

    /** Index of the minor part. */
    private static final int MINOR = 1;

    /** Major part of the very first version. */
    private static final int FIRST_MAJOR = 1;

    /** Minor part a major increment resets to. */
    private static final int MINOR_RESET = 0;

    /** Flag value of the single active version of a document. */
    private static final int ACTIVE_FLAG = 1;

    /**
     * Plans the upload of one file into an existing document.
     *
     * @param history     every version row of the document, order irrelevant
     * @param fingerprint the four reuse elements of the upload being planned
     * @return decision the intake has to carry out
     */
    public VersionPlan plan(List<DocumentVersion> history, VersionFingerprint fingerprint) {
        if (CollectionUtils.isEmpty(history)) {
            return VersionPlan.build(KbConstants.INITIAL_VERSION, Reuse.none());
        }
        DocumentVersion active = activeOf(history);
        DocumentVersion basis = active == null ? newestOf(history) : active;
        if (active != null && fingerprint.matchesAll(active)) {
            log.info("upload matches the active version, docId={}, versionId={}, version={}",
                    active.getDocId(), active.getVersionId(), active.getVersion());
            return VersionPlan.duplicate(active);
        }
        String number = fingerprint.sameContent(basis) ? nextMinor(history) : nextMajor(history);
        return VersionPlan.build(number, resolveReuse(history, fingerprint));
    }

    /**
     * Next version number of a revision: the highest number with its minor part incremented.
     *
     * @param history every version row of the document
     * @return version number in {@code major.minor} form
     */
    public String nextMinor(List<DocumentVersion> history) {
        if (CollectionUtils.isEmpty(history)) {
            return KbConstants.INITIAL_VERSION;
        }
        int[] highest = highestOf(history);
        return highest[MAJOR] + SEPARATOR + (highest[MINOR] + 1);
    }

    /**
     * Next version number of a content change: the highest major part incremented, minor reset.
     *
     * @param history every version row of the document
     * @return version number in {@code major.minor} form
     */
    public String nextMajor(List<DocumentVersion> history) {
        if (CollectionUtils.isEmpty(history)) {
            return KbConstants.INITIAL_VERSION;
        }
        return (highestOf(history)[MAJOR] + 1) + SEPARATOR + MINOR_RESET;
    }

    /**
     * Picks the earlier build whose artifacts the new version can take over.
     *
     * <p>Chunk level reuse additionally requires the source to be READY, which is the only state in
     * which a non active version still owns its chunk rows; an archived version keeps its parse
     * artifact but nothing to copy.
     *
     * @param history     every version row of the document
     * @param fingerprint the four reuse elements of the upload being planned
     * @return reuse decision, {@link Reuse#none()} when no version matches
     */
    private Reuse resolveReuse(List<DocumentVersion> history, VersionFingerprint fingerprint) {
        Reuse best = Reuse.none();
        for (DocumentVersion candidate : history) {
            if (!fingerprint.sameParse(candidate) || isBlank(candidate.getParsedObject())) {
                continue;
            }
            boolean chunksReusable = fingerprint.sameSplit(candidate)
                    && candidate.getStatus() == DocumentVersionStatus.READY;
            Reuse resolved = chunksReusable
                    ? Reuse.chunks(candidate.getVersionId(), candidate.getParsedObject())
                    : Reuse.parsed(candidate.getVersionId(), candidate.getParsedObject());
            // Any version matching a given level carries byte identical artifacts, so the first match
            // of the highest reachable level is as good as any other and the scan never revisits it.
            if (resolved.level().ordinal() > best.level().ordinal()) {
                best = resolved;
            }
        }
        return best;
    }

    /**
     * Highest version number of a history.
     *
     * @param history every version row of the document
     * @return two element array holding the major and the minor part
     */
    private int[] highestOf(List<DocumentVersion> history) {
        int major = FIRST_MAJOR;
        int minor = MINOR_RESET;
        for (DocumentVersion version : history) {
            String[] parts = version.getVersion() == null
                    ? new String[0] : version.getVersion().split(SEPARATOR_PATTERN);
            if (parts.length != PARTS) {
                continue;
            }
            try {
                int currentMajor = Integer.parseInt(parts[MAJOR]);
                int currentMinor = Integer.parseInt(parts[MINOR]);
                if (currentMajor > major || (currentMajor == major && currentMinor > minor)) {
                    major = currentMajor;
                    minor = currentMinor;
                }
            } catch (NumberFormatException e) {
                log.info("skip unparsable version number, docId={}, version={}",
                        version.getDocId(), version.getVersion());
            }
        }
        return new int[]{major, minor};
    }

    private DocumentVersion activeOf(List<DocumentVersion> history) {
        for (DocumentVersion version : history) {
            boolean flagged = version.getActiveFlag() != null && version.getActiveFlag() == ACTIVE_FLAG;
            if (flagged || version.getStatus() == DocumentVersionStatus.ACTIVE) {
                return version;
            }
        }
        return null;
    }

    private DocumentVersion newestOf(List<DocumentVersion> history) {
        DocumentVersion newest = history.get(0);
        for (DocumentVersion version : history) {
            if (version.getId() != null && newest.getId() != null && version.getId() > newest.getId()) {
                newest = version;
            }
        }
        return newest;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The four elements that decide whether an earlier build can be reused.
     *
     * <p>Named after the six element tuple of the requirement: the parse and split fingerprints each
     * fold three of those elements into one digest, which is why four values are enough here.
     *
     * @param contentHash      SHA-256 of the original byte stream
     * @param parseFingerprint digest of the parse stage inputs
     * @param chunkFingerprint digest of the split stage inputs
     * @param embeddingVersion embedding model identifier
     */
    public record VersionFingerprint(String contentHash, String parseFingerprint,
                                     String chunkFingerprint, String embeddingVersion) {

        /**
         * Tells whether a version was built from the same bytes.
         *
         * @param version version row to compare with
         * @return {@code true} when the content hashes match
         */
        public boolean sameContent(DocumentVersion version) {
            return Objects.equals(contentHash, version.getContentHash());
        }

        /**
         * Tells whether a version yields the same text.
         *
         * @param version version row to compare with
         * @return {@code true} when the content hash and the parse fingerprint both match
         */
        public boolean sameParse(DocumentVersion version) {
            return sameContent(version) && Objects.equals(parseFingerprint, version.getParseFingerprint());
        }

        /**
         * Tells whether a version yields the same chunks embedded by the same model.
         *
         * @param version version row to compare with
         * @return {@code true} when the split fingerprint and the embedding version both match
         */
        public boolean sameSplit(DocumentVersion version) {
            return Objects.equals(chunkFingerprint, version.getChunkFingerprint())
                    && Objects.equals(embeddingVersion, version.getEmbeddingVersion());
        }

        /**
         * Tells whether a version is indistinguishable from this upload.
         *
         * @param version version row to compare with
         * @return {@code true} when all four elements match
         */
        public boolean matchesAll(DocumentVersion version) {
            return sameParse(version) && sameSplit(version);
        }
    }

    /**
     * How much of an earlier build the new version takes over.
     */
    public enum ReuseLevel {

        /** Nothing matches, every stage runs. */
        NONE,

        /** Text is identical, the stored parse artifact replaces the parser call. */
        PARSED,

        /** Chunks are identical too, the rows are copied instead of being split and embedded again. */
        CHUNKS
    }

    /**
     * Reuse decision of one planned version.
     *
     * @param level           how much can be taken over
     * @param sourceVersionId version the artifacts come from, {@code null} for {@link ReuseLevel#NONE}
     * @param parsedObject    object storage key of the reusable parse artifact, {@code null} when none
     */
    public record Reuse(ReuseLevel level, String sourceVersionId, String parsedObject) {

        /**
         * Nothing to reuse.
         *
         * @return empty decision
         */
        public static Reuse none() {
            return new Reuse(ReuseLevel.NONE, null, null);
        }

        /**
         * Parse artifact only.
         *
         * @param sourceVersionId version the artifact belongs to
         * @param parsedObject    object storage key of the artifact
         * @return decision
         */
        public static Reuse parsed(String sourceVersionId, String parsedObject) {
            return new Reuse(ReuseLevel.PARSED, sourceVersionId, parsedObject);
        }

        /**
         * Parse artifact and chunk rows.
         *
         * @param sourceVersionId version the artifacts belong to
         * @param parsedObject    object storage key of the artifact
         * @return decision
         */
        public static Reuse chunks(String sourceVersionId, String parsedObject) {
            return new Reuse(ReuseLevel.CHUNKS, sourceVersionId, parsedObject);
        }

        /**
         * Tells whether the parse stage can be skipped.
         *
         * @return {@code true} for {@link ReuseLevel#PARSED} and {@link ReuseLevel#CHUNKS}
         */
        public boolean reusesParse() {
            return level != ReuseLevel.NONE;
        }

        /**
         * Tells whether the split and embed stages can be skipped.
         *
         * @return {@code true} for {@link ReuseLevel#CHUNKS}
         */
        public boolean reusesChunks() {
            return level == ReuseLevel.CHUNKS;
        }
    }

    /**
     * Outcome of planning one upload.
     *
     * @param duplicate            {@code true} when no new version is created
     * @param versionNumber        number of the version to create, {@code null} for a duplicate
     * @param duplicateOfVersionId version the upload duplicates, {@code null} otherwise
     * @param reuse                artifacts the new version takes over
     */
    public record VersionPlan(boolean duplicate, String versionNumber, String duplicateOfVersionId,
                              Reuse reuse) {

        /**
         * Plan of a version that has to be built.
         *
         * @param versionNumber number of the version to create
         * @param reuse         artifacts it takes over
         * @return plan
         */
        public static VersionPlan build(String versionNumber, Reuse reuse) {
            return new VersionPlan(false, versionNumber, null, reuse);
        }

        /**
         * Plan of an upload that changes nothing.
         *
         * @param existing version the upload duplicates
         * @return plan
         */
        public static VersionPlan duplicate(DocumentVersion existing) {
            return new VersionPlan(true, existing.getVersion(), existing.getVersionId(), Reuse.none());
        }
    }
}
