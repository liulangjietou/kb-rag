package io.kbrag.app.document;

import io.kbrag.domain.entity.Document;

/**
 * What an upload actually did.
 *
 * <p>Three answers are possible and the caller has to be able to tell them apart: a document was
 * created, a new version of an existing document was created, or nothing was created because the file
 * is byte for byte what the document already serves under an unchanged configuration. The console
 * reports the third case as a duplicate instead of leaving the operator waiting for an indexing run
 * that will never start.
 *
 * @param document        document the upload landed on
 * @param versionId       version that will be built, or the existing one for a duplicate
 * @param version         version number in {@code major.minor} form
 * @param duplicated      {@code true} when no new version was created
 * @param duplicateOfDocId another document of the same knowledge base holding the same bytes,
 *                        {@code null} when the content is new; a hint only, nothing is shared
 *
 * @author owlzhangfq@gmail.com
 */
public record UploadOutcome(Document document, String versionId, String version, boolean duplicated,
                            String duplicateOfDocId) {
}
