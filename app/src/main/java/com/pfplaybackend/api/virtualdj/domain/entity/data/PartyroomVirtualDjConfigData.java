package com.pfplaybackend.api.virtualdj.domain.entity.data;

import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@DynamicInsert
@DynamicUpdate
@Table(name = "partyroom_virtual_dj_config")
@Entity
public class PartyroomVirtualDjConfigData extends BaseEntity {

    @Id
    @Column(name = "partyroom_id", columnDefinition = "bigint unsigned")
    private Long partyroomId;

    @Comment("OFF/MANAGED/FROZEN")
    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private VirtualDjStatus status;

    @Comment("목표 가상 DJ 수")
    @Column(name = "target_count", nullable = false, columnDefinition = "int unsigned")
    private Integer targetCount;

    @Comment("동반 진입 하한선 (사람 DJ 수가 이 값 미만이면 봇 진입 보류)")
    @Column(name = "companion_floor", nullable = false, columnDefinition = "int unsigned")
    private Integer companionFloor;

    @Comment("이 룸에 사용할 송 팩 id")
    @Column(name = "song_pack_id", columnDefinition = "bigint unsigned")
    private Long songPackId;

    @Comment("마지막 자가갱신 시각(P3-B watermark 겸 쿨다운 기준). null=미실행")
    @Column(name = "last_self_update_at")
    private java.time.LocalDateTime lastSelfUpdateAt;

    protected PartyroomVirtualDjConfigData() {
    }

    @Builder
    public PartyroomVirtualDjConfigData(Long partyroomId, VirtualDjStatus status,
                                         Integer targetCount, Integer companionFloor,
                                         Long songPackId) {
        this.partyroomId = partyroomId;
        this.status = status;
        this.targetCount = targetCount;
        this.companionFloor = companionFloor;
        this.songPackId = songPackId;
    }

    // ── Factory ──

    /**
     * 새 룸용 기본 config: status=OFF, target_count=2, companion_floor=1.
     */
    public static PartyroomVirtualDjConfigData create(Long partyroomId) {
        return PartyroomVirtualDjConfigData.builder()
                .partyroomId(partyroomId)
                .status(VirtualDjStatus.OFF)
                .targetCount(2)
                .companionFloor(1)
                .build();
    }

    // ── Domain Methods ──

    /**
     * 가상 DJ 관리 모드 전환.
     */
    public void applyManaged(int targetCount, int companionFloor, Long songPackId) {
        this.status = VirtualDjStatus.MANAGED;
        this.targetCount = targetCount;
        this.companionFloor = companionFloor;
        this.songPackId = songPackId;
    }

    /**
     * 가상 DJ 동결 — 현재 봇 수 유지, 신규 조정 금지.
     */
    public void freeze() {
        this.status = VirtualDjStatus.FROZEN;
    }

    /**
     * 가상 DJ 비활성화.
     */
    public void turnOff() {
        this.status = VirtualDjStatus.OFF;
    }

    /** P3-B 자가갱신 사이클 완료 표시(watermark 전진). */
    public void markSelfUpdated(java.time.LocalDateTime now) {
        this.lastSelfUpdateAt = now;
    }

}
