package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.dto.command.SetAvatarCommand;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.enums.ProfileChangeType;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import com.pfplaybackend.api.user.domain.exception.UserAvatarException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAvatarCommandService {

    private final MemberRepository memberRepository;
    private final ActivityRepository activityRepository;
    private final AvatarResourceQueryService avatarResourceQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void setUserAvatar(SetAvatarCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        MemberData member = memberRepository.findByUserAccountId(userId.getUid()).orElseThrow();

        // 0. 리소스 접근 권한 유효성 검증
        AvatarBodyDto avatarBodyDto = avatarResourceQueryService.findAvatarBodyByUri(new AvatarBodyUri(command.body().uri()));
        if (!avatarBodyDto.getObtainableType().equals(ObtainmentType.BASIC)) {
            ActivityType activityType = ActivityType.of(avatarBodyDto.getObtainableType());
            ActivityData activity = activityRepository.findByUserIdAndActivityType(userId, activityType)
                    .orElseThrow(() -> ExceptionCreator.create(UserAvatarException.AVATAR_SELECTION_FORBIDDEN));
            if (!activity.getScore().isAtLeast(avatarBodyDto.getObtainableScore())) {
                throw ExceptionCreator.create(UserAvatarException.AVATAR_SELECTION_FORBIDDEN);
            }
        }

        AvatarFaceUri avatarFaceUri;
        AvatarIconUri avatarIconUri;
        member.updateAvatarBody(
                new AvatarBodyUri(avatarBodyDto.getResourceUri()),
                avatarBodyDto.getCombinePositionX(),
                avatarBodyDto.getCombinePositionY());

        if(command.avatarCompositionType().equals(AvatarCompositionType.SINGLE_BODY)) {
            avatarFaceUri = new AvatarFaceUri();
            avatarIconUri = findAvatarIconPairWithSingleBody(avatarBodyDto);

            member.updateAvatarFace(avatarFaceUri);
            member.updateAvatarIcon(avatarIconUri);
        }else {
            avatarFaceUri = new AvatarFaceUri(command.face().uri());
            avatarIconUri = findAvatarIconByFaceSourceType(avatarFaceUri, command.face().sourceType());

            member.updateAvatarFace(avatarFaceUri, command.face().sourceType(),
                    command.face().transform().offsetX(),
                    command.face().transform().offsetY(),
                    command.face().transform().scale());
            member.updateAvatarIcon(avatarIconUri);
        }

        memberRepository.save(member);
        eventPublisher.publishEvent(new UserProfileChangedEvent(userId, ProfileChangeType.AVATAR));
    }

    public AvatarIconUri findAvatarIconPairWithSingleBody(AvatarBodyDto avatarBodyDto) {
        AvatarIconDto avatarIconDto = avatarResourceQueryService.findPairAvatarIconByBodyUri(new AvatarBodyUri(avatarBodyDto.getResourceUri()));
        return new AvatarIconUri(avatarIconDto.resourceUri());
    }

    public AvatarIconUri findAvatarIconByFaceSourceType(AvatarFaceUri faceUri, FaceSourceType sourceType) {
        if (sourceType.equals(FaceSourceType.INTERNAL_IMAGE)) {
            AvatarIconDto avatarIconDto = avatarResourceQueryService.findPairAvatarIconByFaceUri(faceUri);
            return new AvatarIconUri(avatarIconDto.resourceUri());
        } else {
            return new AvatarIconUri(faceUri.getValue());
        }
    }
}
