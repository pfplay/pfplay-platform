package com.pfplaybackend.api.admin.adapter.out.external;

import com.pfplaybackend.api.admin.application.port.out.AdminMemberPort;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminMemberAdapter implements AdminMemberPort {

    private final MemberRepository memberRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserActivityCommandService userActivityCommandService;
    private final ActivityRepository activityRepository;

    @Override
    public MemberData saveMember(MemberData member) {
        return memberRepository.save(member);
    }

    @Override
    public Optional<MemberData> findMemberById(Long id) {
        return memberRepository.findById(id);
    }

    @Override
    public void deleteMemberById(Long id) {
        memberRepository.deleteById(id);
    }

    @Override
    public Optional<MemberData> findMemberByEmail(String email) {
        // email moved to UserAccount in Task 5/7. Resolve via UserAccount, then
        // the bound Member by user_account_id.
        return userAccountRepository.findByEmail(email)
                .map(UserAccountData::getUserId)
                .map(UserId::getUid)
                .flatMap(memberRepository::findByUserAccountId);
    }

    @Override
    public long countMembersByProviderType(ProviderType providerType) {
        // providerType moved to UserAccount in Task 5/7. Member rows are 1:1 with
        // UserAccount rows for non-withdrawn accounts, so counting accounts by
        // provider matches the legacy semantics for this admin-stats use case.
        return userAccountRepository.countByProviderType(providerType);
    }

    @Override
    public void createUserActivities(UserId userId) {
        userActivityCommandService.createUserActivities(userId);
    }

    @Override
    public void deleteUserActivities(UserId userId) {
        activityRepository.deleteAllByUserId(userId);
    }
}
