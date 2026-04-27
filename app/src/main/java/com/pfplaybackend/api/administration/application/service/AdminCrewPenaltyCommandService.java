package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 어드민 크루 페널티 부과/해제 — orchestration 담당.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §5.3
 *
 * PR 8 AdminPartyroomCommandService와 동일 패턴 (load → validate → mutate → save → publish):
 * - port impl(PartyroomAggregateAdapter)은 thin CRUD pass-through 유지
 * - 본 service가 collaborator 직접 사용
 *
 * Cross-BC: administration → party (PR 8 ArchUnit 가드 단방향, 합법).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCrewPenaltyCommandService {

    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminContext adminContext;
    private final Clock clock;

    @Transactional
    public Long apply(Long partyroomId, AdminApplyPenaltyCommand cmd) {
        Long administratorId = adminContext.currentAdministratorId();
        PartyroomId pid = new PartyroomId(partyroomId);

        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        if (partyroom.isTerminated()) {
            throw ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED);
        }

        CrewData crew = aggregatePort.findCrewById(cmd.crewId())
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ROOM));
        if (crew.getPartyroomId().getId() != partyroomId) {
            throw ExceptionCreator.create(CrewException.NOT_FOUND_ROOM);
        }

        PenaltyType partyEnum = cmd.penaltyType().toPartyEnum();
        boolean isPermanent = partyEnum == PenaltyType.PERMANENT_EXPULSION;

        // 기존 expel 재사용 — atomic toggle (PR 8 76d7b2c1) + isPermanent 시 enforceBan + saveCrew
        partyroomAccessCommandService.expel(partyroom, crew, isPermanent);

        // PERMANENT_EXPULSION만 history row 저장 — 기존 CrewPenaltyCommandService 동작과 대칭.
        // 이미 ban된 crew에 다시 PERMANENT를 부과해도 멱등 + 새 history row 생성 (audit 완전성, spec §4.1 #6).
        Long historyId = null;
        if (isPermanent) {
            CrewPenaltyHistoryData saved = crewPenaltyHistoryRepository.save(
                    CrewPenaltyHistoryData.builder()
                            .partyroomId(pid)
                            .punishedCrewId(new CrewId(crew.getId()))
                            .punisherCrewId(null)                       // admin은 crew 아님 (V1 nullable)
                            .punisherType(PunisherType.ADMIN)
                            .penaltyReason(cmd.reason())
                            .penaltyDate(LocalDateTime.now(clock))
                            .penaltyType(partyEnum)
                            .released(false)
                            .build());
            historyId = saved.getId();
        }

        eventPublisher.publishEvent(new AdminCrewPenalizedEvent(
                pid, administratorId, new CrewId(crew.getId()),
                partyEnum, historyId, cmd.reason()));

        log.info("[AdminCrewPenalty.apply] partyroomId={} crewId={} type={} historyId={} by adminId={}",
                partyroomId, crew.getId(), partyEnum, historyId, administratorId);
        return historyId;
    }

    @Transactional
    public void release(Long partyroomId, Long penaltyId) {
        Long administratorId = adminContext.currentAdministratorId();
        PartyroomId pid = new PartyroomId(partyroomId);

        // partyroom 존재 검증만 (status TERMINATED여도 release 허용 — cleanup이 종료 후 발생할 수 있음).
        aggregatePort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        CrewPenaltyHistoryData history = crewPenaltyHistoryRepository
                .findByIdAndPartyroomIdAndReleasedIsFalse(penaltyId, pid)
                .orElseThrow(() -> ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND));

        if (history.getPunisherType() != PunisherType.ADMIN) {
            throw ExceptionCreator.create(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE);
        }

        // 1. crew의 ban 해제 — 자동 재입장 없음 (release는 ban 플래그만 클리어).
        CrewData crew = aggregatePort.findCrewById(history.getPunishedCrewId().getId())
                .orElseThrow();
        crew.releaseBan();
        aggregatePort.saveCrew(crew);

        // 2. history row release 마킹 (releasedByCrewId=null, Q8.9(i))
        history.releaseByAdmin(LocalDateTime.now(clock));
        crewPenaltyHistoryRepository.save(history);

        eventPublisher.publishEvent(new AdminCrewPenaltyReleasedEvent(
                pid, administratorId, new CrewId(crew.getId()), penaltyId));

        log.info("[AdminCrewPenalty.release] partyroomId={} crewId={} penaltyId={} by adminId={}",
                partyroomId, crew.getId(), penaltyId, administratorId);
    }
}
