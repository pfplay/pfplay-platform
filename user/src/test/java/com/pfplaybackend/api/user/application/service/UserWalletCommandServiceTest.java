package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.dto.command.UpdateWalletCommand;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserWalletCommandServiceTest {

    @Mock MemberRepository memberRepository;

    @InjectMocks UserWalletCommandService userWalletCommandService;

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
    @DisplayName("updateMyWalletAddress — 지갑 주소가 정상 업데이트된다")
    void updateMyWalletAddressSuccess() {
        // given
        MemberData member = mock(MemberData.class);
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));
        UpdateWalletCommand command = new UpdateWalletCommand("0xABC123");

        // when
        userWalletCommandService.updateMyWalletAddress(command);

        // then
        verify(member).updateWalletAddress(any());
        verify(memberRepository).save(member);
    }

    @Test
    @DisplayName("updateMyWalletAddress — 회원이 없으면 NoSuchElementException이 발생한다")
    void updateMyWalletAddressMemberNotFound() {
        // given
        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.empty());
        UpdateWalletCommand command = new UpdateWalletCommand("0xABC123");

        // when & then
        assertThatThrownBy(() -> userWalletCommandService.updateMyWalletAddress(command))
                .isInstanceOf(NoSuchElementException.class);
    }
}
