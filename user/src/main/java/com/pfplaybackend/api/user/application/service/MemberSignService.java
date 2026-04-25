package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.command.SignMemberCommand;
import com.pfplaybackend.api.user.application.port.out.OAuth2RedirectPort;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberSignService {
    private final OAuth2RedirectPort oauth2RedirectPort;
    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final PlaylistSetupPort playlistSetupPort;
    private final ApplicationEventPublisher applicationEventPublisher;

    public String getOAuth2RedirectUri(SignMemberCommand command, String redirectLocation) {
        return oauth2RedirectPort.getRedirectUri(command.oauth2Provider(), redirectLocation);
    }

    /**
     * Two-stage lookup-or-create for OAuth login.
     *
     * <ol>
     *   <li>Look up {@link UserAccountData} by {@code (email, providerType)};
     *       create one (with a fresh {@link UserId}) if none exists.</li>
     *   <li>Mark the account's {@code last_login_at} via {@link UserAccountData#recordLogin()}.
     *       The mutation flushes at transaction end (this method is {@code @Transactional}).</li>
     *   <li>Look up {@link MemberData} by {@code userAccountId}; create one if none
     *       exists (recovery-safe — handles the rare case of a UserAccount without a Member).</li>
     * </ol>
     *
     * <p>When a new Member is created, profile / default-playlist / event
     * side-effects fire (matches pre-V4 behavior). Activity-row initialization
     * is deferred to Task 11 (see {@link #initializeNewMember} TODO).
     */
    @Transactional
    public MemberData getMemberOrCreate(String email, ProviderType providerType) {
        UserAccountData userAccount = userAccountRepository
                .findByEmailAndProviderType(email, providerType)
                .orElseGet(() -> userAccountRepository.save(
                        UserAccountData.createForSocial(new UserId(), email, providerType)));

        userAccount.recordLogin();

        return memberRepository.findByUserAccountId(userAccount.getUserId().getUid())
                .orElseGet(() -> initializeNewMember(userAccount));
    }

    /**
     * Persists a new {@link MemberData} for the given {@link UserAccountData}
     * along with its onboarding side-effects: profile initialization, default
     * playlist creation, and a {@link MemberRegisteredEvent} publication.
     *
     * <p>Activity-row initialization (formerly {@code member.initializeActivityMap(...)})
     * is intentionally omitted here — see TODO below.
     */
    private MemberData initializeNewMember(UserAccountData userAccount) {
        Long userAccountId = userAccount.getUserId().getUid();

        // 1. Build the Member shell.
        MemberData member = MemberData.createForUserAccount(userAccountId);

        // 2. Initialize profile (matches pre-V4 behavior).
        ProfileData profile = userProfileCommandService.createProfileDataForMember(userAccount.getUserId());
        member.initializeProfile(profile);

        // 3. Persist Member (cascades the new ProfileData).
        MemberData savedMember = memberRepository.save(member);

        // 4. Initialize default playlist (Party-context onboarding).
        playlistSetupPort.createDefaultPlaylist(userAccount.getUserId());

        // 5. Publish event for downstream listeners.
        applicationEventPublisher.publishEvent(
                new MemberRegisteredEvent(
                        userAccount.getUserId(),
                        userAccount.getEmail(),
                        userAccount.getProviderType()));

        // TODO(Task 11): Activity init was here in the pre-V4 flow:
        //   var activityMap = userActivityCommandService.createUserActivities(member.getUserId());
        //   member.initializeActivityMap(activityMap);
        // MemberData.initializeActivityMap was removed in Task 5 because Member no
        // longer owns the activity collection (sharper aggregate boundary; see
        // MemberData class comment). When ActivityRepository lands in Task 11,
        // re-add activity bootstrapping here by persisting ActivityData rows
        // directly (e.g. activityRepository.saveAll(...)) keyed by userAccountId.
        return savedMember;
    }
}
