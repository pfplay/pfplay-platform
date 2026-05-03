package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GuestDataTest {

    @Test
    void createForUserAccount_defaultsToGtTierAndCapturesAgent() {
        var guest = GuestData.createForUserAccount(99L, "Firefox/MacOS");

        assertThat(guest.getUserAccountId()).isEqualTo(99L);
        assertThat(guest.getAuthorityTier()).isEqualTo(AuthorityTier.GT);
        assertThat(guest.getAgent()).isEqualTo("Firefox/MacOS");
        assertThat(guest.isProfileUpdated()).isFalse();
        assertThat(guest.getGuestId()).isNull(); // assigned on persist
    }

    @Test
    void createForUserAccount_acceptsNullAgent() {
        var guest = GuestData.createForUserAccount(99L, null);

        assertThat(guest.getAgent()).isNull();
        assertThat(guest.getAuthorityTier()).isEqualTo(AuthorityTier.GT);
    }

    @Test
    void initiateProfile_setsProfileAndMarksUpdated() {
        var guest = GuestData.createForUserAccount(99L, "Firefox");
        var profile = ProfileData.builder().build();

        guest.initiateProfile(profile);

        assertThat(guest.getProfileData()).isSameAs(profile);
        assertThat(guest.isProfileUpdated()).isTrue();
    }
}
