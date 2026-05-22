package com.pfplaybackend.api.playlist.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@AggregateRoot
@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "PLAYLIST",
        indexes = {
                @Index(name = "playlist_owner_id_IDX", columnList = "owner_id")
        }
)
@Entity
public class PlaylistData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Embedded
    @AttributeOverride(name = "uid", column = @Column(name = "owner_id"))
    private UserId ownerId;

    @Comment("플레이리스트 순서")
    @Column(columnDefinition = "integer unsigned")
    private Integer orderNumber;

    @Comment("플레이리스트 이름")
    private String name;

    @Comment("플레이리스트 타입")
    @Column(length = 50)
    @Enumerated(EnumType.STRING)
    private PlaylistType type;

    @Comment("재생 커서 — 마지막 재생 트랙 id")
    @Column(name = "last_played_track_id", columnDefinition = "bigint unsigned")
    private Long lastPlayedTrackId;

    protected PlaylistData() {
    }

    @Builder
    public PlaylistData(Long id, UserId ownerId, Integer orderNumber, String name, PlaylistType type) {
        this.id = id;
        this.ownerId = ownerId;
        this.orderNumber = orderNumber;
        this.name = name;
        this.type = type;
    }

    public static PlaylistData create(Integer orderNumber, String name, PlaylistType type, UserId ownerId) {
        return PlaylistData.builder()
                .orderNumber(orderNumber)
                .name(name)
                .type(type)
                .ownerId(ownerId)
                .build();
    }

    public void rename(String name) {
        this.name = name;
    }
}
