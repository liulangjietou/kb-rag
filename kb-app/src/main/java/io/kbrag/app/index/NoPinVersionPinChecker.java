package io.kbrag.app.index;

import io.kbrag.domain.port.VersionPinChecker;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Default answer to the archiving protection question: nothing pins a version.
 *
 * <p>Correct for every deployment that has no application versions, which is all of them until index
 * snapshots exist. Its only purpose is to let the retention cleanup ask the question through the port
 * from day one, so the milestone that introduces snapshots supplies another implementation and changes
 * no cleanup logic.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class NoPinVersionPinChecker implements VersionPinChecker {

    @Override
    public Set<String> pinnedVersionIds(String docId) {
        return Set.of();
    }
}
