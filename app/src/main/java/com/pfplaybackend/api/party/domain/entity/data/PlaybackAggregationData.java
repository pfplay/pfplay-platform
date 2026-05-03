package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@AggregateRoot
@Getter
@DynamicInsert
@DynamicUpdate
@Table(name = "PLAYBACK_AGGREGATION")
@Entity
public class PlaybackAggregationData extends BaseEntity {

    @EmbeddedId
    @AttributeOverride(name = "id", column = @Column(name = "playback_id"))
    private PlaybackId playbackId;

    private int likeCount;
    private int dislikeCount;
    private int grabCount;

    protected PlaybackAggregationData() {}

    private PlaybackAggregationData(PlaybackId playbackId) {
        this.playbackId = playbackId;
        this.likeCount = 0;
        this.dislikeCount = 0;
        this.grabCount = 0;
    }

    // ── Factory Method ──

    public static PlaybackAggregationData createFor(PlaybackId playbackId) {
        return new PlaybackAggregationData(playbackId);
    }


}
