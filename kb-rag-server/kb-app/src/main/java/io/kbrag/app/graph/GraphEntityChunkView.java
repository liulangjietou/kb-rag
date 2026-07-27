package io.kbrag.app.graph;

/**
 * One source passage of an entity, as the console's drill down shows it, requirement section 4.9.
 *
 * <p><b>Not a {@code RetrievalNode}.</b> A retrieval node carries scores, a score type and a recall
 * source, none of which exists here: this list is "where does this entity come from", not "what did a
 * query recall". Reusing the node shape would force every field of it to be filled with a placeholder,
 * and a console reading a score of zero cannot tell a placeholder from a bad result.
 *
 * <p>The document name and the version label are joined in on the server side: the drill down is a list,
 * and letting the console resolve them would be one request per row.
 *
 * @param chunkId              chunk business id
 * @param docId                owning document
 * @param docFileName          display name of the owning document, {@code null} when it was deleted
 * @param documentVersionId    owning document version
 * @param documentVersionLabel display label of that version, {@code null} when it was deleted
 * @param content              chunk text, read from the MySQL fact source
 * @param enabled              retrieval switch of the chunk, so a drill down explains a missing recall
 *
 * @author owlzhangfq@gmail.com
 */
public record GraphEntityChunkView(String chunkId, String docId, String docFileName,
                                   String documentVersionId, String documentVersionLabel,
                                   String content, boolean enabled) {
}
