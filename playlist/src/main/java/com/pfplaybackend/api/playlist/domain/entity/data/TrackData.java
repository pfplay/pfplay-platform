package com.pfplaybackend.api.playlist.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.DurationConverter;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;


@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "TRACK",
        indexes = {
            @Index(name = "track_playlist_id_IDX", columnList = "playlist_id")
        }
)
@Entity
public class TrackData extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "playlist_id", nullable = false))
    private PlaylistId playlistId;

    @Comment("플레이리스트의 곡 순서")
    @Column(columnDefinition = "integer unsigned")
    private Integer orderNumber;

    @Comment("곡 이름")
    private String name;

    @Comment("링크 식별자")
    private String linkId;

    @Comment("곡 총 재생 시간")
    @Convert(converter = DurationConverter.class)
    private Duration duration;

    @Comment("썸네일 이미지 url")
    private String thumbnailImage;

    protected TrackData() { }

    @Builder
    public TrackData(PlaylistId playlistId, Integer orderNumber, String name, Duration duration, String linkId, String thumbnailImage) {
        this.playlistId = playlistId;
        this.orderNumber = orderNumber;
        this.name = name;
        this.duration = duration;
        this.linkId = linkId;
        this.thumbnailImage = thumbnailImage;
    }

    // ── Business Methods ──

    public void reorder(int newOrderNumber) {
        this.orderNumber = newOrderNumber;
    }

    public void moveToPlaylist(PlaylistId targetPlaylistId, int newOrderNumber) {
        this.playlistId = targetPlaylistId;
        this.orderNumber = newOrderNumber;
    }
}
