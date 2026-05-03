package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeResponse;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-3 PATCH /admin/members/{memberId}/tier — Member tier 변경 + MemberTierChangedEvent publish.
 *
 * <p>도메인 evt publish는 ADR-004 hybrid 전략 — service가
 * {@code member.pollDomainEvents().forEach(eventPublisher::publishEvent)}로 명시 dispatch.
 * Listener {@code UserActivityLogListener.on(MemberTierChangedEvent)}이 AFTER_COMMIT phase
 * + @Async로 row 2건(TIER_CHANGED + ADMIN_ACTED_ON) INSERT (PR 12b1 G2 wired).
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md §3.1, §5
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMemberTierCommandService {

    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminContext adminContext;

    @Transactional
    public AdminMemberTierChangeResponse changeTier(Long memberId, AdminMemberTierChangeRequest request) {
        MemberData member = memberRepository.findById(memberId)
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        if (member.getAuthorityTier() == request.targetTier()) {
            throw ExceptionCreator.create(AdminMemberException.TIER_UNCHANGED);
        }

        Long byAdministratorId = adminContext.currentAdministratorId();
        AuthorityTier oldTier = member.getAuthorityTier();
        member.changeTier(request.targetTier(), byAdministratorId);
        memberRepository.save(member);

        // ADR-004 hybrid: explicit publish from poll
        member.pollDomainEvents().forEach(eventPublisher::publishEvent);

        log.info("[AdminMemberTier.changeTier] memberId={} {}→{} by adminId={}",
                memberId, oldTier, request.targetTier(), byAdministratorId);

        return new AdminMemberTierChangeResponse(memberId, oldTier, request.targetTier());
    }
}
