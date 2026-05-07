package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberWithdrawResponse;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link AdminMemberWithdrawCommandService} (PR 12b2 G3).
 *
 * <p>Uses real {@link UserAccountData} entities so {@code withdraw()} → registerEvent →
 * pollDomainEvents flow exercises actual domain mechanics.
 */
@ExtendWith(MockitoExtension.class)
class AdminMemberWithdrawCommandServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminContext adminContext;

    @InjectMocks AdminMemberWithdrawCommandService service;

    @Test
    @DisplayName("withdraw: 1차 호출 — alreadyWithdrawn=false + event 1건 publish + email PII erase")
    void withdraw_firstCall_emitsEvent() {
        Long memberId = 1L;
        Long uaId = 7L;
        Long byAdminId = 99L;

        // real MemberData (factory) + real UserAccountData
        MemberData member = MemberData.createForUserAccount(uaId);
        UserAccountData ua = UserAccountData.createForSocial(
                new UserId(uaId), "alice@gmail.com", ProviderType.GOOGLE);

        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(userAccountRepository.findById(new UserId(uaId))).willReturn(Optional.of(ua));
        given(adminContext.currentAdministratorId()).willReturn(byAdminId);

        AdminMemberWithdrawResponse res = service.withdraw(memberId);

        assertThat(res.memberId()).isEqualTo(memberId);
        assertThat(res.userAccountId()).isEqualTo(uaId);
        assertThat(res.alreadyWithdrawn()).isFalse();
        assertThat(res.withdrawnAt()).isNotNull();
        assertThat(ua.isWithdrawn()).isTrue();
        assertThat(ua.getEmail()).startsWith("withdrawn-").endsWith("@withdrawn.local");

        verify(userAccountRepository).save(ua);
        verify(eventPublisher, times(1)).publishEvent(any(UserAccountWithdrawnEvent.class));
    }

    @Test
    @DisplayName("withdraw: 2차 호출 — alreadyWithdrawn=true + save/publish 호출 안 함")
    void withdraw_idempotent_returnsAlreadyWithdrawnTrue() {
        Long memberId = 1L;
        Long uaId = 7L;

        MemberData member = MemberData.createForUserAccount(uaId);
        UserAccountData ua = UserAccountData.createForSocial(
                new UserId(uaId), "alice@gmail.com", ProviderType.GOOGLE);
        ua.withdraw(99L);  // pre-existing withdrawn state
        ua.pollDomainEvents();  // drain prior events

        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(userAccountRepository.findById(new UserId(uaId))).willReturn(Optional.of(ua));

        AdminMemberWithdrawResponse res = service.withdraw(memberId);

        assertThat(res.alreadyWithdrawn()).isTrue();
        assertThat(res.withdrawnAt()).isEqualTo(ua.getWithdrawnAt());

        verify(userAccountRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("withdraw: memberId 부재 → MEMBER_NOT_FOUND MBR-001")
    void withdraw_memberNotFound_throws() {
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MBR-001");

        verify(userAccountRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
