package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.application.dto.command.PunishPenaltyCommand;
import com.pfplaybackend.api.party.application.port.out.ChatPenaltyCachePort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CrewPenaltyCommandService {

    private final ApplicationEventPublisher eventPublisher;
    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;
    private final ChatPenaltyCachePort chatPenaltyCachePort;
    private final PartyroomQueryService partyroomQueryService;
    private final Clock clock;

    @Transactional
    public Long addPenalty(PartyroomId partyroomId, PunishPenaltyCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        CrewId punishedCrewId = new CrewId(command.crewId());
        CrewData punisherCrew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        CrewData punishedCrew = aggregatePort.findCrewById(punishedCrewId.getId())
                .orElseThrow();
        GradeType punishedGradeType = punishedCrew.getGradeType();
        PenaltyType penaltyType = command.penaltyType();

        // 권한 검증
        if (punisherCrew.isBelowGrade(GradeType.MODERATOR)) throw ExceptionCreator.create(GradeException.MANAGER_GRADE_REQUIRED);
        if (punisherCrew.getGradeType().isLowerThan(punishedCrew.getGradeType())) throw ExceptionCreator.create(GradeException.GRADE_INSUFFICIENT_FOR_OPERATION);
        if (punishedGradeType.isEqualOrHigherThan(punisherCrew.getGradeType())) throw ExceptionCreator.create(GradeException.GRADE_EXCEEDS_ALLOWED_THRESHOLD);

        // 페널티 부과
        switch (penaltyType) {
            case CHAT_MESSAGE_REMOVAL -> { /* 이벤트로만 처리 */ }
            case CHAT_BAN_30_SECONDS -> recordInShortTime(punishedCrewId.getId());
            case ONE_TIME_EXPULSION -> partyroomAccessCommandService.expel(partyroom, punishedCrew, false);
            case PERMANENT_EXPULSION -> partyroomAccessCommandService.expel(partyroom, punishedCrew, true);
        }

        eventPublisher.publishEvent(new CrewPenalizedEvent(
                partyroomId, new CrewId(punisherCrew.getId()), punishedCrewId, command.detail(), command.penaltyType()));

        if(PenaltyType.PERMANENT_EXPULSION.equals(penaltyType)) {
            CrewPenaltyHistoryData crewPenaltyHistoryData = CrewPenaltyHistoryData.builder()
                    .partyroomId(partyroomId)
                    .punishedCrewId(punishedCrewId)
                    .punisherCrewId(new CrewId(punisherCrew.getId()))
                    .punisherType(PunisherType.CREW)            // [PR 9] V8 컬럼 명시화
                    .penaltyReason(command.detail())
                    .penaltyDate(LocalDateTime.now(clock))
                    .penaltyType(command.penaltyType())
                    .released(false)
                    .build();
            CrewPenaltyHistoryData saved = crewPenaltyHistoryRepository.save(crewPenaltyHistoryData);
            return saved.getId();
        }
        return null;
    }

    private void recordInShortTime(Long crewIdValue) {
        chatPenaltyCachePort.recordChatBan(crewIdValue, 30);
    }

    public void releaseCrewPenalty(PartyroomId partyroomId, Long penaltyId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        partyroomQueryService.getPartyroomById(partyroomId);

        CrewData releaserCrewForValidation = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        if (releaserCrewForValidation.isBelowGrade(GradeType.MODERATOR)) throw ExceptionCreator.create(GradeException.MANAGER_GRADE_REQUIRED);

        CrewPenaltyHistoryData historyData = crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(penaltyId, partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND));

        // [PR 9] admin-applied 페널티는 admin endpoint를 통해서만 release 가능 (spec §4.3)
        if (historyData.getPunisherType() == PunisherType.ADMIN) {
            throw ExceptionCreator.create(PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE);
        }

        // 1. Release ban on crew
        CrewData crew = aggregatePort.findCrewById(historyData.getPunishedCrewId().getId())
                .orElseThrow();
        crew.releaseBan();
        aggregatePort.saveCrew(crew);

        // 2. Update history
        historyData.release(new CrewId(releaserCrewForValidation.getId()), LocalDateTime.now(clock));
        crewPenaltyHistoryRepository.save(historyData);
    }
}
