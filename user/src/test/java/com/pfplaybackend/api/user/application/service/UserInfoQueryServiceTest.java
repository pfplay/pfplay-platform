package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.UnauthorizedException;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.result.MyInfoResult;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserInfoQueryServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock MemberRepository memberRepository;
    @Mock GuestRepository guestRepository;

    @InjectMocks UserInfoQueryService userInfoQueryService;

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

    @Test
    @DisplayName("getMyInfo — 사용자 정보를 정상 반환한다")
    void getMyInfoSuccess() {
        // given — UserAccount carries identity fields; tier / profileUpdated
        // live on Member post-V4.
        UserAccountData user = mock(UserAccountData.class);
        when(user.getUserId()).thenReturn(userId);
        when(user.getEmail()).thenReturn("test@gmail.com");
        when(user.getCreatedAt()).thenReturn(LocalDateTime.of(2024, 1, 1, 0, 0));
        when(userAccountRepository.findByUserId(userId)).thenReturn(Optional.of(user));

        MemberData member = mock(MemberData.class);
        when(member.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        when(member.isProfileUpdated()).thenReturn(true);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));

        // when
        MyInfoResult result = userInfoQueryService.getMyInfo();

        // then
        assertThat(result.uid()).isEqualTo("1");
        assertThat(result.email()).isEqualTo("test@gmail.com");
        assertThat(result.authorityTier()).isEqualTo(AuthorityTier.FM);
        assertThat(result.profileUpdated()).isTrue();
        assertThat(result.registrationDate()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0).toLocalDate());
    }

    @Test
    @DisplayName("getMyInfo — 사용자가 없으면 UnauthorizedException이 발생한다")
    void getMyInfoUserNotFound() {
        // given
        when(userAccountRepository.findByUserId(userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userInfoQueryService.getMyInfo())
                .isInstanceOf(UnauthorizedException.class);
    }
}
