package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeResponse;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent;
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
 * Unit test for {@link AdminMemberTierCommandService} (PR 12b2 G2).
 *
 * <p>Uses real {@link MemberData} entities (factory-built) so {@code member.changeTier()}
 * + {@code pollDomainEvents()} flow exercises true domain mechanics. Repository / event
 * publisher / admin context are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AdminMemberTierCommandServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminContext adminContext;

    @InjectMocks AdminMemberTierCommandService service;

    @Test
    @DisplayName("changeTier: AM → GT 변경 + response oldTier/newTier + event 1건 publish")
    void changeTier_returnsResponse_andPublishesEvent() {
        Long memberId = 1L;
        Long byAdminId = 99L;
        MemberData member = MemberData.createForUserAccount(7777L);  // initial tier = AM
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(adminContext.currentAdministratorId()).willReturn(byAdminId);

        AdminMemberTierChangeResponse res = service.changeTier(
                memberId, new AdminMemberTierChangeRequest(AuthorityTier.GT));

        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.GT);
        verify(memberRepository).save(member);
        verify(eventPublisher, times(1)).publishEvent(any(MemberTierChangedEvent.class));
        assertThat(res.memberId()).isEqualTo(memberId);
        assertThat(res.oldTier()).isEqualTo(AuthorityTier.AM);
        assertThat(res.newTier()).isEqualTo(AuthorityTier.GT);
    }

    @Test
    @DisplayName("changeTier: 같은 tier → TIER_UNCHANGED MBR-003 + save/publish 호출 안 함")
    void changeTier_sameTier_throwsTierUnchanged() {
        Long memberId = 1L;
        MemberData member = MemberData.createForUserAccount(7777L);  // initial = AM
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> service.changeTier(
                memberId, new AdminMemberTierChangeRequest(AuthorityTier.AM)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MBR-003");

        verify(memberRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("changeTier: memberId 부재 → MEMBER_NOT_FOUND MBR-001")
    void changeTier_memberNotFound_throws() {
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeTier(
                999L, new AdminMemberTierChangeRequest(AuthorityTier.AM)))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MBR-001");

        verify(memberRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
