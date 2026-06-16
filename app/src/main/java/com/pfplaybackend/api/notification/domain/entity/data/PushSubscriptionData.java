package com.pfplaybackend.api.notification.domain.entity.data;

import com.pfplaybackend.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "push_subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushSubscriptionData extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 512) private String endpoint;
    @Column(nullable = false, length = 255) private String p256dh;
    @Column(nullable = false, length = 255) private String auth;
    @Column(nullable = false, length = 8) private String lang;
    @Column(name = "revoked_at") private LocalDateTime revokedAt;

    public static PushSubscriptionData create(Long userId, String endpoint, String p256dh, String auth, String lang) {
        PushSubscriptionData e = new PushSubscriptionData();
        e.userId = userId;
        e.endpoint = endpoint;
        e.p256dh = p256dh;
        e.auth = auth;
        e.lang = lang;
        return e;
    }

    public void revive(Long userId, String p256dh, String auth, String lang) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.lang = lang;
        this.revokedAt = null;
    }

    public void revoke(LocalDateTime now) {
        this.revokedAt = now;
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}
