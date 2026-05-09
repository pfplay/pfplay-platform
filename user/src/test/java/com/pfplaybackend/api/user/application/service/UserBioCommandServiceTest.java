package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.application.dto.command.UpdateBioCommand;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBioCommandServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock UserProfileRepository userProfileRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks UserBioCommandService userBioService;

    private UserId userId;

    @BeforeEach
    void setUp() {
        userId = new UserId(1L);
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("updateMyBio — 바이오 업데이트 후 프로필 변경 이벤트를 발행한다")
    void updateMyBioSuccess() {
        // given
        MemberData member = mock(MemberData.class);
        when(userProfileRepository.existsByNicknameAndUserIdNot(new Nickname("NewNick"), userId)).thenReturn(false);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));
        UpdateBioCommand command = new UpdateBioCommand("NewNick", "Hello World");

        // when
        userBioService.updateMyBio(command);

        // then
        verify(member).updateProfileBio(command.nickName(), command.introduction());
        verify(memberRepository).save(member);
        verify(eventPublisher).publishEvent(any(UserProfileChangedEvent.class));
    }

    @Test
    @DisplayName("updateMyBio — 다른 사용자가 같은 닉네임을 쓰고 있으면 CONFLICT(409)을 던진다")
    void updateMyBioRejectsDuplicateNickname() {
        // given
        when(userProfileRepository.existsByNicknameAndUserIdNot(new Nickname("Taken"), userId)).thenReturn(true);
        UpdateBioCommand command = new UpdateBioCommand("Taken", "Bio");

        // when & then
        assertThatThrownBy(() -> userBioService.updateMyBio(command))
                .isInstanceOf(ConflictException.class);
        verify(memberRepository, never()).findByUserAccountId(anyLong());
        verify(memberRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("updateMyBio — 회원을 찾을 수 없으면 NoSuchElementException이 발생한다")
    void updateMyBioMemberNotFound() {
        // given
        when(userProfileRepository.existsByNicknameAndUserIdNot(new Nickname("Nick"), userId)).thenReturn(false);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.empty());
        UpdateBioCommand command = new UpdateBioCommand("Nick", "Bio");

        // when & then
        assertThatThrownBy(() -> userBioService.updateMyBio(command))
                .isInstanceOf(NoSuchElementException.class);
    }
}
