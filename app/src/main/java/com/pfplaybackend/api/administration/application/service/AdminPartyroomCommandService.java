package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.event.PartyroomDisplayFlagChangedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomMetaUpdatedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomRestoredEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomSuspendedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin이 partyroom 상태/메타/displayFlag를 변경하는 5 use case.
 * Q1 결정 — Administration이 PartyroomAggregatePort 직접 호출 (use-case port 미도입).
 *
 * 모든 메서드는 @Transactional. 도메인 메서드 호출 → save → 이벤트 publish.
 * 같은 TX 안에서 PartyroomAdminActionListener가 audit row INSERT (Q2 atomic).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPartyroomCommandService {

    private final PartyroomAggregatePort aggregatePort;
    private final CrewRepository crewRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public void terminate(PartyroomId partyroomId, String reason, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        LocalDateTime now = LocalDateTime.now(clock);

        int crewsDeactivated = crewRepository.bulkDeactivateByPartyroomId(partyroomId, now);
        log.info("[AdminPartyroom.terminate] partyroomId={}, deactivatedCrews={}, by adminId={}",
                partyroomId.getId(), crewsDeactivated, administratorId);

        partyroom.terminate();   // PR 7 strict guard — TERMINATED 재진입 시 ILLEGAL_STATE_TRANSITION
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomTerminatedEvent(partyroomId, administratorId, reason)
        );
    }

    @Transactional
    public void suspend(PartyroomId partyroomId, String reason, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        partyroom.suspend();
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomSuspendedEvent(partyroomId, administratorId, reason)
        );
        log.info("[AdminPartyroom.suspend] partyroomId={}, by adminId={}", partyroomId.getId(), administratorId);
    }

    @Transactional
    public void restore(PartyroomId partyroomId, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        partyroom.restore();
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomRestoredEvent(partyroomId, administratorId)
        );
        log.info("[AdminPartyroom.restore] partyroomId={}, by adminId={}", partyroomId.getId(), administratorId);
    }

    @Transactional
    public void setDisplayFlag(PartyroomId partyroomId, DisplayFlag newFlag, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        DisplayFlag oldFlag = partyroom.getDisplayFlag();

        switch (newFlag) {
            case FEATURED -> partyroom.setDisplayFlagFeatured();
            case HIDDEN   -> partyroom.setDisplayFlagHidden();
            case NORMAL   -> partyroom.setDisplayFlagNormal();
        }
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomDisplayFlagChangedEvent(partyroomId, administratorId, oldFlag, newFlag)
        );
        log.info("[AdminPartyroom.setDisplayFlag] partyroomId={}, {} -> {}, by adminId={}",
                partyroomId.getId(), oldFlag, newFlag, administratorId);
    }

    /**
     * Meta 부분 수정. null 인자는 "변경 안 함". 최소 1개는 non-null 가정 (controller에서 검증).
     * diff는 mutation 전 캡쳐 (spec §4.5 step 2).
     */
    @Transactional
    public void updateMeta(PartyroomId partyroomId, String newTitle, String newIntroduction,
                           Integer newPlaybackTimeLimitMinutes, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        if (partyroom.isTerminated()) {
            throw ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED);
        }

        Map<String, Map<String, Object>> diff = new HashMap<>();

        if (newTitle != null && !newTitle.equals(partyroom.getTitle())) {
            diff.put("title", oldNew(partyroom.getTitle(), newTitle));
        }
        if (newIntroduction != null && !newIntroduction.equals(partyroom.getIntroduction())) {
            diff.put("introduction", oldNew(partyroom.getIntroduction(), newIntroduction));
        }
        Integer oldMinutes = partyroom.getPlaybackTimeLimit() == null
                ? null : partyroom.getPlaybackTimeLimit().getMinutes();
        if (newPlaybackTimeLimitMinutes != null && !newPlaybackTimeLimitMinutes.equals(oldMinutes)) {
            diff.put("playbackTimeLimit",
                    oldNew(oldMinutes == null ? "null" : oldMinutes.toString(),
                           newPlaybackTimeLimitMinutes.toString()));
        }

        if (diff.isEmpty()) {
            log.info("[AdminPartyroom.updateMeta] partyroomId={} - no actual changes, no event published",
                    partyroomId.getId());
            return;
        }

        partyroom.updateBaseInfo(
                newTitle != null ? newTitle : partyroom.getTitle(),
                newIntroduction != null ? newIntroduction : partyroom.getIntroduction(),
                partyroom.getLinkDomain(),
                newPlaybackTimeLimitMinutes != null
                        ? PlaybackTimeLimit.ofMinutes(newPlaybackTimeLimitMinutes)
                        : partyroom.getPlaybackTimeLimit()
        );
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomMetaUpdatedEvent(partyroomId, administratorId, diff)
        );
        log.info("[AdminPartyroom.updateMeta] partyroomId={}, changedFields={}, by adminId={}",
                partyroomId.getId(), diff.keySet(), administratorId);
    }

    private PartyroomData loadPartyroom(PartyroomId partyroomId) {
        return aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
    }

    /**
     * Null-safe diff entry builder. {@link Map#of} rejects null values, but
     * nullable columns (e.g. PartyroomData.introduction) can legitimately be null
     * before an admin sets them — preserve the null in the audit diff rather than
     * stringifying or throwing NPE.
     */
    private static Map<String, Object> oldNew(Object oldVal, Object newVal) {
        Map<String, Object> m = new HashMap<>();
        m.put("old", oldVal);
        m.put("new", newVal);
        return m;
    }
}
