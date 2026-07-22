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
 * Partyroom {@code lastActivityAt} 갱신 listener (구 PartyroomCounterListener).
 *
 * <p>#360: {@code crew_count} 캐시 카운터가 제거되며 본 listener 의 책임은 activity touch 만 남았다.
 * 크루 수는 어디서나 crew 라이브 COUNT 가 진실이다 — 이벤트 구동 카운터는 이벤트 없는 상태
 * 변경(V38 collapse·운영 SQL)에서 드리프트해 #358 불일치 버그의 근원이었다. terminate/close
 * reset 핸들러는 대상(카운터) 소멸로 함께 제거.
 *
 * <p>모든 listener 메서드는 {@code AFTER_COMMIT} phase에서 새 트랜잭션을 열어 atomic UPDATE 를
 * 실행한다. {@code touchLastActivity} 는 ACTIVE 룸만 갱신 — ENTER 는 ACTIVE 룸에서만 가능하고,
 * SUSPENDED 룸의 EXIT/재생 이벤트가 "최근 활동" 표시를 움직이지 않는 것은 의도된 동작이다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartyroomActivityListener {

    private final PartyroomRepository partyroomRepository;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CrewAccessedEvent event) {
        touch(event.getPartyroomId().getId());
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
            // DEBUG intentionally: 비-ACTIVE 룸(SUSPENDED/TERMINATED)의 이벤트는 touch 를 건너뛰는 게
            // 설계다 (SUSPENDED 룸에서도 EXIT/재생 이벤트는 발생할 수 있음).
            log.debug("[PartyroomActivityListener] touchLastActivity skipped (room not ACTIVE) partyroomId={}",
                      partyroomId);
        }
    }
}
