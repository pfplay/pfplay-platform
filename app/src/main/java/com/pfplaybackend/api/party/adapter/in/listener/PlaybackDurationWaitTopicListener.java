package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.party.application.dto.playback.PlaybackDurationWaitDto;
import com.pfplaybackend.api.party.application.port.out.ExpirationTaskPort;
import com.pfplaybackend.api.party.application.service.PlaybackCommandService;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

@AllArgsConstructor
public class PlaybackDurationWaitTopicListener implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(PlaybackDurationWaitTopicListener.class);

    private ExpirationTaskPort expirationTaskPort;
    private DistributedLockExecutor distributedLockExecutor;
    private PlaybackCommandService playbackCommandService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        if(expiredKey.startsWith("TASK:WAIT")) {
            String taskId = expiredKey.split(":")[2];
            try {
                PlaybackDurationWaitDto deserialized = expirationTaskPort.getTaskArgs(taskId, PlaybackDurationWaitDto.class);
                if (deserialized == null) {
                    // 예상된 stale key: 만료 알림은 도착했으나 task args가 이미 정리/소실됨
                    // (서버 재시작·의도적 DB 초기화 직후 등). 정상 정리 상황이므로 조용히 skip.
                    logger.debug("Skipping playback expiration for taskId={}: task args already gone (stale key)", taskId);
                    return;
                }
                String suffixId = deserialized.userId().getUid().toString();
                distributedLockExecutor.performTaskWithLock(suffixId, () -> {
                    expirationTaskPort.clearTaskArgs(taskId);
                    playbackCommandService.complete(deserialized.partyroomId(), deserialized.userId());
                    return null;
                });
            } catch (NotFoundException e) {
                // 예상된 stale key: 만료 처리 시점에 대상(파티룸 등)이 이미 없음
                // (재시작·DB 초기화 직후 1회성). 정상 정리 상황이므로 DEBUG로만 남긴다.
                logger.debug("Skipping stale playback expiration for taskId={}: {}", taskId, e.getMessage());
            } catch (Exception e) {
                // 예상치 못한 실패는 stack trace까지 남겨 루틴 정리에 묻히지 않게 한다.
                logger.error("Failed to process playback expiration for taskId={}", taskId, e);
            }
        }
    }
}
