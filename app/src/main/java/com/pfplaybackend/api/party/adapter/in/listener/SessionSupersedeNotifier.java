package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.party.adapter.in.listener.message.SessionSupersededMessage;
import com.pfplaybackend.api.party.domain.event.SessionSupersededEvent;
import com.pfplaybackend.realtime.sender.SimpMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 멀티 디바이스 세션 승계(#369) — 밀려난 유저에게 SESSION_SUPERSEDED 개인 큐 알림을 발송한다.
 *
 * <p>{@code AFTER_COMMIT} 에서만 발송한다 — 밀어냄(crew EXIT)이 커밋되어 서버 상태가 확정된 뒤에만
 * 알리기 위함이다. WS 발송뿐(DB 쓰기 없음)이라 새 트랜잭션(REQUIRES_NEW)은 불필요하다.
 * 알림 미도달은 정합성에 무해하다 — 밀려남은 서버가 이미 완결했고, 재연결 스냅샷 resync(web#477)가
 * 정합성 백스톱이다.
 *
 * <p>단일 인스턴스 전제(SimpUserRegistry in-process). 다중 인스턴스로 확장 시 밀려난 세션이 다른
 * 인스턴스에 있으면 이 direct send 는 도달하지 못하므로 그룹 브로드캐스트처럼 Redis 릴레이가 필요하다
 * — 그때도 정합성 백스톱(resync)은 유지된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionSupersedeNotifier {

    private final SimpMessageSender simpMessageSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SessionSupersededEvent event) {
        log.info("[session] SESSION_SUPERSEDED notify - userId={}, supersededRoomId={}, newRoomId={}",
                event.getUserId(), event.getSupersededPartyroomId().getId(), event.getNewPartyroomId().getId());
        simpMessageSender.sendToOneSession(
                event.getUserId().toString(),
                SessionSupersededMessage.of(
                        event.getNewPartyroomId().getId(),
                        event.getSupersededPartyroomId().getId(),
                        event.getOccurredAtEpochMilli()));
    }
}
