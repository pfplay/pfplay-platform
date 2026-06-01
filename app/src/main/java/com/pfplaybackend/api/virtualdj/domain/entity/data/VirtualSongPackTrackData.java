package com.pfplaybackend.api.virtualdj.domain.entity.data;

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
        name = "virtual_song_pack_track",
        indexes = {
                @Index(name = "idx_vspt_song_pack_id", columnList = "song_pack_id")
        }
)
@Entity
public class VirtualSongPackTrackData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Comment("소속 송 팩 id")
    @Column(name = "song_pack_id", nullable = false, columnDefinition = "bigint unsigned")
    private Long songPackId;

    @Comment("송 팩 내 재생 순서")
    @Column(name = "order_number", nullable = false, columnDefinition = "int unsigned")
    private Integer orderNumber;

    @Comment("YouTube videoId")
    @Column(name = "link_id", nullable = false, length = 255)
    private String linkId;

    @Comment("곡 이름")
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Comment("MM:SS 형식 재생 시간, playbackTimeLimit 사전 검증됨")
    @Column(name = "duration", nullable = false, length = 255)
    private String duration;

    @Comment("썸네일 이미지 URL")
    @Column(name = "thumbnail_image", length = 255)
    private String thumbnailImage;

    protected VirtualSongPackTrackData() {
    }

    @Builder
    public VirtualSongPackTrackData(Long songPackId, Integer orderNumber, String linkId,
                                    String name, String duration, String thumbnailImage) {
        this.songPackId = songPackId;
        this.orderNumber = orderNumber;
        this.linkId = linkId;
        this.name = name;
        this.duration = duration;
        this.thumbnailImage = thumbnailImage;
    }

    public static VirtualSongPackTrackData create(Long songPackId, int orderNumber,
                                                   String linkId, String name,
                                                   String duration, String thumbnailImage) {
        return VirtualSongPackTrackData.builder()
                .songPackId(songPackId)
                .orderNumber(orderNumber)
                .linkId(linkId)
                .name(name)
                .duration(duration)
                .thumbnailImage(thumbnailImage)
                .build();
    }
}
