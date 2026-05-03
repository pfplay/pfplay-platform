package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberWithdrawResponse;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-4 POST /admin/members/{memberId}/withdraw — Member 비식별화 탈퇴 + UserAccountWithdrawnEvent publish.
 *
 * <p>Service-layer pre-check `isWithdrawn()`로 idempotent 응답 분기 (alreadyWithdrawn flag).
 * 1차 호출만 도메인 mutation + event publish — 재호출은 state 그대로 + event 0건.
 *
 * <p>도메인 메서드 {@link UserAccountData#withdraw(Long)}는 PR 12b1 G2에서
 * 3-arg form으로 evolve됨 — idempotency guard, email PII 비식별화, lastLoginAt 미변경 모두 보유.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md §3.2
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMemberWithdrawCommandService {

    private final MemberRepository memberRepository;
    private final UserAccountRepository userAccountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminContext adminContext;

    @Transactional
    public AdminMemberWithdrawResponse withdraw(Long memberId) {
        MemberData member = memberRepository.findById(memberId)
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        Long userAccountId = member.getUserAccountId();
        UserAccountData userAccount = userAccountRepository.findById(new UserId(userAccountId))
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        if (userAccount.isWithdrawn()) {
            return new AdminMemberWithdrawResponse(memberId, userAccountId,
                    userAccount.getWithdrawnAt(), true);
        }

        Long byAdministratorId = adminContext.currentAdministratorId();
        userAccount.withdraw(byAdministratorId);
        userAccountRepository.save(userAccount);

        // ADR-004 hybrid: explicit publish from poll
        userAccount.pollDomainEvents().forEach(eventPublisher::publishEvent);

        log.info("[AdminMemberWithdraw.withdraw] memberId={} userAccountId={} by adminId={}",
                memberId, userAccountId, byAdministratorId);

        return new AdminMemberWithdrawResponse(memberId, userAccountId,
                userAccount.getWithdrawnAt(), false);
    }
}
