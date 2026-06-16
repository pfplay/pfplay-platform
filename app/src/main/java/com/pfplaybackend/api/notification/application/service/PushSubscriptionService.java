package com.pfplaybackend.api.notification.application.service;

import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import lombok.RequiredArgsConstructor;
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
