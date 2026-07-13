package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "DJ",
        indexes = {
                @Index(name = "dj_partyroom_id_IDX", columnList = "partyroom_id")
        })
@Entity
public class DjData extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dj_id")
    private Long id;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "partyroom_id", nullable = false))
    private PartyroomId partyroomId;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "crew_id"))
    private CrewId crewId;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "playlist_id"))
    private PlaylistId playlistId;

    private int orderNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20, nullable = false)
    private DjKind kind;

    // 데이터 엔티티 생성자
    protected DjData() {
    }

    @Builder
    public DjData(Long id, PartyroomId partyroomId, CrewId crewId, PlaylistId playlistId, int orderNumber,
                  DjKind kind, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.partyroomId = partyroomId;
        this.crewId = crewId;
        this.playlistId = playlistId;
        this.orderNumber = orderNumber;
        this.kind = kind;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ── Business Methods ──

    public static DjData create(PartyroomId partyroomId, PlaylistId playlistId, CrewId crewId, int orderNumber) {
        return create(partyroomId, playlistId, crewId, orderNumber, DjKind.NORMAL);
    }

    public static DjData create(PartyroomId partyroomId, PlaylistId playlistId, CrewId crewId, int orderNumber, DjKind kind) {
        return DjData.builder()
                .partyroomId(partyroomId)
                .playlistId(playlistId)
                .crewId(crewId)
                .orderNumber(orderNumber)
                .kind(kind)
                .build();
    }

    public void updateOrderNumber(int orderNumber) {
        this.orderNumber = orderNumber;
    }

    public void updatePlaylist(PlaylistId playlistId) {
        this.playlistId = playlistId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DjData djData = (DjData) o;
        return Objects.equals(id, djData.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}