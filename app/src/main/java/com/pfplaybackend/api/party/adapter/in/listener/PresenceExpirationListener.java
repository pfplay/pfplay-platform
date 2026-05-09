package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.party.application.service.PartyroomPresenceService;
import com.pfplaybackend.api.party.application.service.PartyroomPresenceService.ParsedPresenceKey;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * Subscribes to Redis {@code __keyevent@*__:expired} pattern and dispatches
 * {@code PRESENCE:PENDING:<roomId>:<crewId>} expirations to
 * {@link PartyroomPresenceService#forceOffline}.
 *
 * <p>Co-exists with {@link PlaybackDurationWaitTopicListener} on the same pattern;
 * each filters by its own key prefix. Spring Redis delivers every expired event
 * to all subscribers, so both run for every expiration but only act on matching
 * keys.
 *
 * <p>If this listener is not subscribed at the moment a key expires (e.g., app
 * restart spans the TTL), the event is permanently lost — Redis pub/sub does not
 * buffer (Issue #195). Mitigated by the reconcile cron that periodically scans
 * stale {@code crew.pending_exit_at} rows and calls {@code forceOffline}
 * idempotently.
 */
@Slf4j
@AllArgsConstructor
public class PresenceExpirationListener implements MessageListener {

    private static final String KEY_PREFIX = "PRESENCE:PENDING:";

    private final PartyroomPresenceService presenceService;
    private final DistributedLockExecutor distributedLockExecutor;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        if (!expiredKey.startsWith(KEY_PREFIX)) {
            return;
        }
        ParsedPresenceKey parsed = PartyroomPresenceService.parseKey(expiredKey);
        if (parsed == null) {
            log.warn("[presence] malformed expired key: {}", expiredKey);
            return;
        }
        // Lock per crew so the reconcile cron and this listener cannot race on
        // the same row. forceOffline is itself idempotent, but the lock keeps the
        // event-publishing path single-shot.
        String lockKey = "presence:" + parsed.crewId().getId();
        try {
            distributedLockExecutor.performTaskWithLock(lockKey, () -> {
                presenceService.forceOffline(parsed.partyroomId(), parsed.crewId());
                return null;
            });
        } catch (Exception e) {
            log.warn("[presence] failed to process expired key={} : {}", expiredKey, e.getMessage());
        }
    }
}
