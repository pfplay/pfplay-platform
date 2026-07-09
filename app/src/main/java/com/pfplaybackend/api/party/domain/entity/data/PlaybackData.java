package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.DurationConverter;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "PLAYBACK",
        indexes = {
                @Index(name = "idx_playback_partyroom_time", columnList = "partyroom_id, created_at")
        }
)
@Entity
public class PlaybackData extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "partyroom_id"))
    private PartyroomId partyroomId;

    // 프로필 정보를 역추적 하기 위한 '사용자 식별자'
    @Embedded
    @AttributeOverride(name = "uid", column = @Column(name = "user_id"))
    private UserId userId;

    // 음악 이름 정보
    private String name;
    // 링크 식별자
    private String linkId;
    // 음악 재생 시간
    @Convert(converter = DurationConverter.class)
    private Duration duration;
    // 썸네일
    private String thumbnailImage;

    // 재생 종료 시각
    private Long endTime;

    protected PlaybackData() {}

    @Builder
    public PlaybackData(Long id, PartyroomId partyroomId,
                        UserId userId, String name, String linkId, Duration duration, String thumbnailImage, Long endTime) {
        this.id = id;
        this.partyroomId = partyroomId;
        this.userId = userId;
        this.name = name;
        this.linkId = linkId;
        this.duration = duration;
        this.thumbnailImage = thumbnailImage;
        this.endTime = endTime;
    }

    // ── Factory Method ──

    public static PlaybackData create(PartyroomId partyroomId, UserId userId,
                                       String name, Duration duration, String linkId, String thumbnailImage, Instant now) {
        return PlaybackData.builder()
                .partyroomId(partyroomId)
                .userId(userId)
                .name(name)
                .duration(duration)
                .linkId(linkId)
                .thumbnailImage(thumbnailImage)
                .endTime(duration.calculateEndTimeEpochMilli(now))
                .build();
    }

    public static PlaybackData create(PartyroomId partyroomId, UserId userId,
                                       String name, Duration duration, String linkId, String thumbnailImage) {
        return create(partyroomId, userId, name, duration, linkId, thumbnailImage, Instant.now());
    }
}
