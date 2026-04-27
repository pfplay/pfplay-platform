package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackDeactivatedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Partyroom denormalized 카운터/lastActivity 갱신 listener.
 *
 * 모든 listener 메서드는 {@code AFTER_COMMIT} phase에서 새 트랜잭션을 열어
 * native atomic UPDATE를 실행한다 — Race A(multi-instance counter race)는
 * DB row lock이 직렬화하므로 분산락 불필요. spec §7.1 / §7.4 참조.
 *
 * Affected==0 케이스 (룸 missing or TERMINATED)는 WARN/DEBUG 로그.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartyroomCounterListener {

    private final PartyroomRepository partyroomRepository;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CrewAccessedEvent event) {
        Long partyroomId = event.getPartyroomId().getId();
        LocalDateTime now = LocalDateTime.now(clock);
        int affected = switch (event.getAccessType()) {
            case ENTER -> partyroomRepository.incrementCrewCount(partyroomId, now);
            case EXIT  -> partyroomRepository.decrementCrewCount(partyroomId, now);
        };
        if (affected == 0) {
            log.warn("[PartyroomCounterListener] crew_count update skipped (room missing or TERMINATED) " +
                     "partyroomId={}, accessType={}", partyroomId, event.getAccessType());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PlaybackStartedEvent event) {
        touch(event.getPartyroomId().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PlaybackDeactivatedEvent event) {
        touch(event.getPartyroomId().getId());
    }

    private void touch(Long partyroomId) {
        int affected = partyroomRepository.touchLastActivity(partyroomId, LocalDateTime.now(clock));
        if (affected == 0) {
            // DEBUG (not WARN) intentionally: SUSPENDED rooms can still receive playback events
            // (admin-suspended room with running playback). Skipping the touch is by design,
            // not an anomaly. Counter listeners log WARN for genuine misses (TERMINATED rooms).
            log.debug("[PartyroomCounterListener] touchLastActivity skipped (room not ACTIVE) partyroomId={}",
                      partyroomId);
        }
    }
}
