package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestSignService {

    private final UserAccountRepository userAccountRepository;
    private final GuestRepository guestRepository;
    private final UserProfileCommandService userProfileCommandService;

    /**
     * Create a fresh guest. Allocates a new {@link UserId}, persists a backing
     * {@link UserAccountData} with a synthetic guest email (the email NOT NULL
     * UNIQUE constraint requires a placeholder), then writes the
     * {@link GuestData} bound by {@code userAccountId}.
     */
    @Transactional
    public GuestData getGuestOrCreate() {
        UserId userId = new UserId();

        // Synthetic placeholder email — guests do not log in via OAuth.
        UserAccountData userAccount = UserAccountData.createForSocial(
                userId,
                "guest-" + userId.getUid() + "@guest.local",
                ProviderType.GOOGLE);
        userAccountRepository.save(userAccount);

        GuestData guest = GuestData.createForUserAccount(userId.getUid(), "N/A");
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(userId);
        guest.initiateProfile(profile);
        return guestRepository.save(guest);
    }
}
