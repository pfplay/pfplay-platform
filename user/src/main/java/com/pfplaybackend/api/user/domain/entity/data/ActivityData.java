package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.user.domain.value.Score;
import com.pfplaybackend.api.user.domain.value.ScoreConverter;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@DynamicInsert
@DynamicUpdate
@Table(name = "USER_ACTIVITY",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_activity_user_type", columnNames = {"user_id", "activity_type"})
        },
        indexes = {
                @Index(name = "user_activity_user_id_IDX", columnList = "user_id")
        })
@Entity
public class ActivityData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Embedded
    @AttributeOverride(name = "uid", column = @Column(name = "user_id"))
    private UserId userId;

    @Column(length = 50)
    @Enumerated(value = EnumType.STRING)
    private ActivityType activityType;

    @Convert(converter = ScoreConverter.class)
    @Column(columnDefinition = "integer unsigned")
    private Score score;

    protected ActivityData() {}

    @Builder
    public ActivityData(Long id, UserId userId, ActivityType activityType, Score score) {
        this.id = id;
        this.userId = userId;
        this.activityType = activityType;
        this.score = score;
    }

    public static ActivityData create(UserId userId, ActivityType activityType, int score) {
        return ActivityData.builder()
                .userId(userId)
                .activityType(activityType)
                .score(new Score(score))
                .build();
    }

    public void addScore(int delta) {
        this.score = this.score.add(delta);
    }
}
