package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.UnauthorizedException;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.result.MyInfoResult;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserInfoQueryService {

    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final GuestRepository guestRepository;

    /**
     * Resolve the caller's user-info view. Tier and {@code isProfileUpdated}
     * moved from {@code UserAccount} to {@code Member}/{@code Guest} in the
     * V4 IAM refactor — query both and merge.
     */
    @Transactional(readOnly = true)
    public MyInfoResult getMyInfo() {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserAccountData user = userAccountRepository.findByUserId(authContext.getUserId())
                .orElseThrow(() -> new UnauthorizedException("USER_NOT_FOUND", "User not found"));
        Long userAccountId = user.getUserId().getUid();

        return memberRepository.findByUserAccountId(userAccountId)
                .map(member -> MyInfoResult.from(user, member.getAuthorityTier(), member.isProfileUpdated()))
                .or(() -> guestRepository.findByUserAccountId(userAccountId)
                        .map(guest -> MyInfoResult.from(user, guest.getAuthorityTier(), guest.isProfileUpdated())))
                .orElseThrow(() -> new UnauthorizedException("USER_NOT_FOUND",
                        "Neither Member nor Guest record found for user_account_id=" + userAccountId));
    }
}
