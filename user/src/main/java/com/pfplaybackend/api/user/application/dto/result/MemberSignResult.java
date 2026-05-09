package com.pfplaybackend.api.user.application.dto.result;

import com.pfplaybackend.api.user.domain.entity.data.MemberData;

/**
 * Result of {@link com.pfplaybackend.api.user.application.service.MemberSignService#getMemberOrCreateWithStatus}.
 *
 * <p>{@code isNewUser} reflects whether the {@code UserAccount} row was newly
 * INSERTed during this call. It is the authoritative signal used by the OAuth
 * callback response (Amplitude L4) to distinguish a true sign-up from a
 * returning login — replacing the client's brittle localStorage UID heuristic.
 *
 * <p>"New user" specifically means a new {@code UserAccount} (the
 * provider-identity row), not a recovery of a missing {@code Member} for an
 * existing account. The latter is a returning user from the analytics
 * perspective.
 */
public record MemberSignResult(MemberData member, boolean isNewUser) {
}
