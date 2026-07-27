package io.kbrag.app.chat;

/**
 * What importing one conversation would do to the knowledge base.
 *
 * <p>The decision is derived from the logical document identity rather than from the display name: a
 * conversation renamed between two exports is still the same conversation, and the requirement makes a
 * re-import a new version instead of a second document.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ChatImportAction {

    /** No document carries this conversation yet. */
    CREATE,

    /** A document already carries it; the import produces a new version replacing the whole content. */
    NEW_VERSION
}
