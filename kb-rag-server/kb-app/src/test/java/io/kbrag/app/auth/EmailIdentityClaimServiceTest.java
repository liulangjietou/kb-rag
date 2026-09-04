package io.kbrag.app.auth;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.mapper.EmailIdentityClaimMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailIdentityClaimServiceTest {

    private static final String USER_ID = "usr_owner";
    private final EmailIdentityClaimMapper mapper = mock(EmailIdentityClaimMapper.class);
    private final EmailIdentityClaimService service = new EmailIdentityClaimService(mapper);

    @BeforeEach
    void ownReservedIdentitiesByDefault() {
        when(mapper.selectOwner("person@example.com")).thenReturn(USER_ID);
        when(mapper.selectOwner("other@example.com")).thenReturn(USER_ID);
    }

    @Test
    void shouldReserveEqualUsernameAndContactEmailOnlyOnce() {
        String normalized = service.claimForNewUser(
                USER_ID, "person@example.com", " Person@EXAMPLE.COM ");

        assertEquals("person@example.com", normalized);
        verify(mapper).reserve("person@example.com", USER_ID);
        verify(mapper).selectOwner("person@example.com");
    }

    @Test
    void shouldRejectAnIdentityHeldByAnotherUser() {
        when(mapper.selectOwner("person@example.com")).thenReturn("usr_other");

        assertThrows(BizException.class, () -> service.claimForNewUser(
                USER_ID, "alice", "person@example.com"));
    }

    @Test
    void shouldReleaseReplacedContactWhenUsernameDoesNotNeedIt() {
        service.releaseReplacedContact(
                USER_ID, "alice", "person@example.com", "other@example.com");

        verify(mapper).releaseOwned("person@example.com", USER_ID);
    }

    @Test
    void shouldRetainOldContactWhenItIsAlsoTheUsername() {
        service.releaseReplacedContact(
                USER_ID, "person@example.com", "person@example.com", "other@example.com");

        verify(mapper, never()).releaseOwned("person@example.com", USER_ID);
    }

    @Test
    void shouldTreatClaimsFromDeletedUsersAsOccupied() {
        when(mapper.selectOwner("person@example.com")).thenReturn("usr_deleted");

        assertTrue(service.claimed(" Person@EXAMPLE.COM "));
    }

    @Test
    void shouldIgnoreAnInvalidOptionalExternalContactClaim() {
        String normalized = service.claimForExternalUser(USER_ID, "alice", "not-an-email");

        assertEquals(null, normalized);
        verify(mapper, never()).reserve("not-an-email", USER_ID);
    }
}
