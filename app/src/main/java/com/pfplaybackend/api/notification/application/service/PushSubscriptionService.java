package com.pfplaybackend.api.notification.application.service;

import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final Clock clock;

    @Transactional
    public Long subscribe(Long userId, String endpoint, String p256dh, String auth, String lang) {
        return repository.findByEndpoint(endpoint)
                .map(existing -> {
                    // IDOR 방어: endpoint 는 브라우저당 고유한 비밀 자격증명이다. 같은 row 를 재사용(revive)할 수 있는 건
                    // (1) 같은 회원의 재구독(키 갱신)이거나 (2) 이전 소유자가 로그아웃하며 해지(revoked)한 기기를
                    // 새 회원이 재점유하는 정당한 hand-off 뿐이다. 활성(active) + 타 회원 소유 endpoint 를 가로채는 것은
                    // 차단한다(unique 제약상 새 row 도 못 만드므로 거부).
                    boolean reclaimable = !existing.isActive() || existing.getUserId().equals(userId);
                    if (!reclaimable) {
                        throw new AccessDeniedException("push subscription endpoint owned by another active user");
                    }
                    existing.revive(userId, p256dh, auth, lang);
                    return existing.getId();
                })
                .orElseGet(() -> repository.save(
                        PushSubscriptionData.create(userId, endpoint, p256dh, auth, lang)).getId());
    }

    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        repository.findByEndpoint(endpoint)
                .filter(s -> s.getUserId().equals(userId))
                .ifPresent(s -> s.revoke(LocalDateTime.now(clock)));
    }
}
