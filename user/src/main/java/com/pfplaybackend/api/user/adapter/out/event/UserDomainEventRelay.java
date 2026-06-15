package com.pfplaybackend.api.user.adapter.out.event;

import com.pfplaybackend.api.common.config.redis.RedisMessagePublisher;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.dto.event.ProfileChangedEvent;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class UserDomainEventRelay {

    private final RedisMessagePublisher messagePublisher;
    private final MemberRepository memberRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(UserProfileChangedEvent event) {
        // userAccountId equals UserAccount.userId.uid by construction (see Task 8).
        MemberData member = memberRepository.findByUserAccountId(event.getUserId().getUid()).orElseThrow();
        ProfileData profile = member.getProfileData();
        var avatar = profile.getAvatarSetting();
        ProfileChangedEvent message = new ProfileChangedEvent(
                profile.getUserId(),
                profile.getNicknameValue(),
                avatar.getAvatarFaceUriValue(),
                avatar.getAvatarBodyUriValue(),
                avatar.getAvatarIconUriValue(),
                avatar.getAvatarCompositionType(),
                avatar.getCombinePositionX(),
                avatar.getCombinePositionY(),
                avatar.getOffsetX(),
                avatar.getOffsetY(),
                avatar.getScale()
        );
        messagePublisher.publish(MessageTopic.CREW_PROFILE_PRE_CHECK.topic(), message);
    }
}
