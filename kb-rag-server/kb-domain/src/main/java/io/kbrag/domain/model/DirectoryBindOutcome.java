package io.kbrag.domain.model;

import io.kbrag.domain.enums.DirectoryBindResult;

import java.util.List;

/**
 * Result of one directory bind attempt together with the group membership read during it.
 *
 * <p>{@link DirectoryBindResult} stays a plain enum because the three verdicts are all most callers
 * need; the group list is per-attempt data an enum cannot carry, so this record pairs the two. The
 * list is only ever non-empty on {@code SUCCESS} with group synchronisation switched on - a failed
 * bind has no authenticated connection to read anything through.
 *
 * @param result   bind verdict
 * @param groupDns distinguished names of the directory groups the account belongs to, never
 *                 {@code null}, empty unless the bind succeeded and group synchronisation is enabled
 *
 * @author owlzhangfq@gmail.com
 */
public record DirectoryBindOutcome(DirectoryBindResult result, List<String> groupDns) {

    public DirectoryBindOutcome {
        groupDns = groupDns == null ? List.of() : List.copyOf(groupDns);
    }

    /**
     * A successful bind carrying whatever groups were read.
     *
     * @param groupDns group distinguished names, may be empty when synchronisation is off or the
     *                 lookup degraded
     * @return outcome
     */
    public static DirectoryBindOutcome success(List<String> groupDns) {
        return new DirectoryBindOutcome(DirectoryBindResult.SUCCESS, groupDns);
    }

    /**
     * A failed bind. Groups are always empty: nothing was authenticated to read them with.
     *
     * @param result rejection or outage verdict
     * @return outcome
     */
    public static DirectoryBindOutcome failure(DirectoryBindResult result) {
        return new DirectoryBindOutcome(result, List.of());
    }
}
