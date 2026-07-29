package io.kbrag.domain.constant;

/**
 * Codes of the roles the V16 migration seeds.
 *
 * <p>Referenced by code only where behaviour depends on the role itself rather than on a permission:
 * the bootstrap grant and the default role of a first time directory login. Everywhere else the question
 * asked is "does the caller hold this permission code", never "is the caller an administrator" - the
 * second form is what turns a permission model back into hard coded roles.
 *
 * <p>Operators may edit the permission set of these roles, and are expected to. They cannot be deleted,
 * because the two references above have to resolve to something.
 *
 * @author owlzhangfq@gmail.com
 */
public final class BuiltinRoles {

    /** Every permission and every knowledge base; the role the bootstrap account is granted. */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** Everything except user and role administration. */
    public static final String KB_ADMIN = "KB_ADMIN";

    /** Uploads and maintains documents. */
    public static final String EDITOR = "EDITOR";

    /** Approves content and triages feedback. */
    public static final String REVIEWER = "REVIEWER";

    /** Read only browsing and retrieval debugging; the default for a new directory account. */
    public static final String VIEWER = "VIEWER";

    private BuiltinRoles() {
    }
}
