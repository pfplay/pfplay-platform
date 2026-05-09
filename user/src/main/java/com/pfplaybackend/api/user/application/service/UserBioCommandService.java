package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.application.dto.command.UpdateBioCommand;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.enums.ProfileChangeType;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import com.pfplaybackend.api.user.domain.exception.UserException;
import com.pfplaybackend.api.user.domain.value.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserBioCommandService {

    private final MemberRepository memberRepository;
    private final UserProfileRepository userProfileRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void updateMyBio(UpdateBioCommand updateBioCommand) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        Nickname requested = new Nickname(updateBioCommand.nickName());
        if (userProfileRepository.existsByNicknameAndUserIdNot(requested, userId)) {
            throw ExceptionCreator.create(UserException.NICKNAME_ALREADY_EXISTS);
        }
        MemberData memberData = memberRepository.findByUserAccountId(userId.getUid()).orElseThrow();
        memberData.updateProfileBio(updateBioCommand.nickName(), updateBioCommand.introduction());
        memberRepository.save(memberData);
        eventPublisher.publishEvent(new UserProfileChangedEvent(userId, ProfileChangeType.BIO));
    }
}
