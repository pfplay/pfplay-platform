package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.command.SignMemberCommand;
import com.pfplaybackend.api.user.application.port.out.OAuth2RedirectPort;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberSignService {
    private final OAuth2RedirectPort oauth2RedirectPort;
    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;

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
     * <p>Profile/activity/playlist initialization for new members is handled by
     * downstream flows (Task 9+ init services and an upcoming MemberRegisteredEvent
     * listener); this method now owns only identity-tier persistence.
     */
    @Transactional
    public MemberData getMemberOrCreate(String email, ProviderType providerType) {
        UserAccountData userAccount = userAccountRepository
                .findByEmailAndProviderType(email, providerType)
                .orElseGet(() -> userAccountRepository.save(
                        UserAccountData.createForSocial(new UserId(), email, providerType)));

        userAccount.recordLogin();

        return memberRepository.findByUserAccountId(userAccount.getUserId().getUid())
                .orElseGet(() -> memberRepository.save(
                        MemberData.createForUserAccount(userAccount.getUserId().getUid())));
    }
}
