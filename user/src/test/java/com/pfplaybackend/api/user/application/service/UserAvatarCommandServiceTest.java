package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.dto.command.SetAvatarCommand;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAvatarCommandServiceTest {

    private static final String BODY_01 = "body_01";
    private static final String ICON_URI_01 = "icon_uri_01";
    private static final String ICON_01 = "icon_01";
    private static final String PREMIUM_BODY = "premium_body";
    private static final String BODY1 = "Body1";

    @Mock MemberRepository memberRepository;
    @Mock ActivityRepository activityRepository;
    @Mock AvatarResourceQueryService avatarResourceQueryService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks UserAvatarCommandService userAvatarCommandService;

    private final UserId userId = new UserId(1L);

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    // ── setUserAvatar ──

    @Test
    @DisplayName("setUserAvatar — BASIC 아바타 + SINGLE_BODY 조합이면 정상 저장된다")
    void setUserAvatarBasicSingleBodySaves() {
        // given
        SetAvatarCommand command = new SetAvatarCommand(
                AvatarCompositionType.SINGLE_BODY,
                new SetAvatarCommand.AvatarBodySpec(BODY_01),
                null);

        MemberData member = mock(MemberData.class);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));

        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(1L).name(BODY1).resourceUri(BODY_01)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(false).defaultSetting(true).available(true)
                .combinePositionX(10).combinePositionY(20).build();
        when(avatarResourceQueryService.findAvatarBodyByUri(any(AvatarBodyUri.class)))
                .thenReturn(bodyDto);

        AvatarIconDto iconDto = new AvatarIconDto(1L, ICON_01, ICON_URI_01, true);
        when(avatarResourceQueryService.findPairAvatarIconByBodyUri(any(AvatarBodyUri.class)))
                .thenReturn(iconDto);

        // when
        userAvatarCommandService.setUserAvatar(command);

        // then
        verify(member).updateAvatarBody(any(AvatarBodyUri.class), eq(10), eq(20));
        verify(member).updateAvatarFace(any(AvatarFaceUri.class));
        verify(member).updateAvatarIcon(any(AvatarIconUri.class));
        verify(memberRepository).save(member);
        verify(eventPublisher).publishEvent(any(UserProfileChangedEvent.class));
    }

    @Test
    @DisplayName("setUserAvatar — BODY_WITH_FACE + INTERNAL_IMAGE이면 face와 icon이 설정된다")
    void setUserAvatarBodyWithFaceInternalImageSaves() {
        // given
        SetAvatarCommand command = new SetAvatarCommand(
                AvatarCompositionType.BODY_WITH_FACE,
                new SetAvatarCommand.AvatarBodySpec(BODY_01),
                new SetAvatarCommand.AvatarFaceSpec("face_01", FaceSourceType.INTERNAL_IMAGE,
                        new SetAvatarCommand.AvatarTransformSpec(1.0, 2.0, 0.5)));

        MemberData member = mock(MemberData.class);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));

        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(1L).name(BODY1).resourceUri(BODY_01)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(true).defaultSetting(false).available(true)
                .combinePositionX(10).combinePositionY(20).build();
        when(avatarResourceQueryService.findAvatarBodyByUri(any(AvatarBodyUri.class)))
                .thenReturn(bodyDto);

        AvatarIconDto iconDto = new AvatarIconDto(1L, ICON_01, ICON_URI_01, true);
        when(avatarResourceQueryService.findPairAvatarIconByFaceUri(any(AvatarFaceUri.class)))
                .thenReturn(iconDto);

        // when
        userAvatarCommandService.setUserAvatar(command);

        // then
        verify(member).updateAvatarBody(any(AvatarBodyUri.class), eq(10), eq(20));
        verify(member).updateAvatarFace(any(AvatarFaceUri.class), eq(FaceSourceType.INTERNAL_IMAGE),
                eq(1.0), eq(2.0), eq(0.5));
        verify(member).updateAvatarIcon(any(AvatarIconUri.class));
        verify(memberRepository).save(member);
    }

    @Test
    @DisplayName("setUserAvatar — 점수 기반 아바타에 점수가 충분하면 정상 저장된다")
    void setUserAvatarScoreBasedAvatarSufficientScoreSaves() {
        // given
        SetAvatarCommand command = new SetAvatarCommand(
                AvatarCompositionType.SINGLE_BODY,
                new SetAvatarCommand.AvatarBodySpec(PREMIUM_BODY),
                null);

        ActivityData djActivity = ActivityData.create(userId, ActivityType.DJ_PNT, 100);
        MemberData member = mock(MemberData.class);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));
        when(activityRepository.findByUserIdAndActivityType(userId, ActivityType.DJ_PNT))
                .thenReturn(Optional.of(djActivity));

        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(2L).name("Premium").resourceUri(PREMIUM_BODY)
                .obtainableType(ObtainmentType.DJ_PNT).obtainableScore(50)
                .combinable(false).defaultSetting(false).available(false)
                .combinePositionX(0).combinePositionY(0).build();
        when(avatarResourceQueryService.findAvatarBodyByUri(any(AvatarBodyUri.class)))
                .thenReturn(bodyDto);

        AvatarIconDto iconDto = new AvatarIconDto(2L, "icon_02", "icon_uri_02", true);
        when(avatarResourceQueryService.findPairAvatarIconByBodyUri(any(AvatarBodyUri.class)))
                .thenReturn(iconDto);

        // when
        userAvatarCommandService.setUserAvatar(command);

        // then
        verify(memberRepository).save(member);
    }

    @Test
    @DisplayName("setUserAvatar — 점수 기반 아바타에 점수가 부족하면 예외가 발생한다")
    void setUserAvatarScoreBasedAvatarInsufficientScoreThrows() {
        // given
        SetAvatarCommand command = new SetAvatarCommand(
                AvatarCompositionType.SINGLE_BODY,
                new SetAvatarCommand.AvatarBodySpec(PREMIUM_BODY),
                null);

        ActivityData djActivity = ActivityData.create(userId, ActivityType.DJ_PNT, 10);
        MemberData member = mock(MemberData.class);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));
        when(activityRepository.findByUserIdAndActivityType(userId, ActivityType.DJ_PNT))
                .thenReturn(Optional.of(djActivity));

        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(2L).name("Premium").resourceUri(PREMIUM_BODY)
                .obtainableType(ObtainmentType.DJ_PNT).obtainableScore(50)
                .combinable(false).defaultSetting(false).available(false)
                .combinePositionX(0).combinePositionY(0).build();
        when(avatarResourceQueryService.findAvatarBodyByUri(any(AvatarBodyUri.class)))
                .thenReturn(bodyDto);

        // when & then
        assertThatThrownBy(() -> userAvatarCommandService.setUserAvatar(command))
                .isInstanceOf(ForbiddenException.class);
        verify(memberRepository, never()).save(any());
    }

    // ── findAvatarIconPairWithSingleBody ──

    @Test
    @DisplayName("findAvatarIconPairWithSingleBody — body URI로 pair 아이콘을 반환한다")
    void findAvatarIconPairWithSingleBodyReturnsPairIcon() {
        // given
        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(1L).name(BODY1).resourceUri(BODY_01)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(false).defaultSetting(true).available(true)
                .combinePositionX(0).combinePositionY(0).build();
        AvatarIconDto iconDto = new AvatarIconDto(1L, ICON_01, ICON_URI_01, true);
        when(avatarResourceQueryService.findPairAvatarIconByBodyUri(any(AvatarBodyUri.class)))
                .thenReturn(iconDto);

        // when
        AvatarIconUri result = userAvatarCommandService.findAvatarIconPairWithSingleBody(bodyDto);

        // then
        assertThat(result.getValue()).isEqualTo(ICON_URI_01);
    }

    // ── findAvatarIconByFaceSourceType ──

    @Test
    @DisplayName("findAvatarIconByFaceSourceType — INTERNAL_IMAGE이면 pair 아이콘을 반환한다")
    void findAvatarIconByFaceSourceTypeInternalImageReturnsPairIcon() {
        // given
        AvatarFaceUri faceUri = new AvatarFaceUri("ava_face_01");
        AvatarIconDto iconDto = new AvatarIconDto(1L, "ava_icon_01", ICON_URI_01, true);
        when(avatarResourceQueryService.findPairAvatarIconByFaceUri(any(AvatarFaceUri.class))).thenReturn(iconDto);

        // when
        AvatarIconUri result = userAvatarCommandService.findAvatarIconByFaceSourceType(faceUri, FaceSourceType.INTERNAL_IMAGE);

        // then
        assertThat(result.getValue()).isEqualTo(ICON_URI_01);
    }

    @Test
    @DisplayName("findAvatarIconByFaceSourceType — NFT_URI이면 faceUri를 그대로 아이콘으로 사용한다")
    void findAvatarIconByFaceSourceTypeNftUriUsesFaceUriAsIcon() {
        // given
        AvatarFaceUri faceUri = new AvatarFaceUri("https://nft.example.com/image.png");

        // when
        AvatarIconUri result = userAvatarCommandService.findAvatarIconByFaceSourceType(faceUri, FaceSourceType.NFT_URI);

        // then
        assertThat(result.getValue()).isEqualTo("https://nft.example.com/image.png");
    }
}
