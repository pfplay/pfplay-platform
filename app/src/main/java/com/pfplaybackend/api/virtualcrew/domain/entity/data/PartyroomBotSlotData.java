package com.pfplaybackend.api.virtualcrew.domain.entity.data;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;

import java.io.Serializable;
import java.util.Objects;

/**
 * virtual_crew_bot_slot row mapping (V32).
 *
 * <p>방 안에서 각 DJ 봇이 점유하는 slot index 를 영속한다. 이 매핑으로 트랙(
 * {@code TrackDistribution} 파티션)이 봇에 안정적으로 대응된다.
 *
 * <p><b>base-class 노트:</b> {@link com.pfplaybackend.api.common.entity.BaseEntity} 는
 * {@code created_at}/{@code updated_at} 두 감사 컬럼을 선언하지만 V32 slot 테이블은
 * {@code created_at} 만 갖는다. BaseEntity 를 상속하면 실 DB 부팅 시 Hibernate
 * {@code validate} 가 없는 {@code updated_at} 컬럼에서 실패한다(create-drop 은 이 drift 를
 * 못 잡는다). slot row 는 배정 시 insert, 회수 시 delete 되는 불변 레코드라
 * {@code updatedAt}/도메인이벤트가 필요 없으므로 standalone {@code @Entity} 로 매핑한다.
 * {@code created_at} 은 아예 매핑하지 않고 DB DEFAULT 에 맡긴다(미매핑 여분 컬럼은
 * validate 대상이 아니다).
 */
@Getter
@DynamicInsert
@Table(name = "virtual_crew_bot_slot")
@Entity
@IdClass(PartyroomBotSlotData.PartyroomBotSlotId.class)
public class PartyroomBotSlotData {

    @Id
    @Comment("파티룸 id(앱가드 참조, 무FK)")
    @Column(name = "partyroom_id", columnDefinition = "bigint unsigned")
    private Long partyroomId;

    @Id
    @Comment("봇 user_account id(앱가드 참조, 무FK)")
    @Column(name = "bot_user_id", columnDefinition = "bigint unsigned")
    private Long botUserId;

    @Comment("방 안에서 봇이 점유하는 slot index")
    @Column(name = "slot_index", nullable = false, columnDefinition = "int unsigned")
    private Integer slotIndex;

    protected PartyroomBotSlotData() {
    }

    @Builder
    public PartyroomBotSlotData(Long partyroomId, Long botUserId, Integer slotIndex) {
        this.partyroomId = partyroomId;
        this.botUserId = botUserId;
        this.slotIndex = slotIndex;
    }

    public static PartyroomBotSlotData create(Long partyroomId, Long botUserId, int slotIndex) {
        return PartyroomBotSlotData.builder()
                .partyroomId(partyroomId)
                .botUserId(botUserId)
                .slotIndex(slotIndex)
                .build();
    }

    /**
     * 복합 PK 식별자 클래스 (partyroom_id, bot_user_id).
     */
    @Getter
    public static class PartyroomBotSlotId implements Serializable {
        private Long partyroomId;
        private Long botUserId;

        public PartyroomBotSlotId() {
        }

        public PartyroomBotSlotId(Long partyroomId, Long botUserId) {
            this.partyroomId = partyroomId;
            this.botUserId = botUserId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PartyroomBotSlotId that = (PartyroomBotSlotId) o;
            return Objects.equals(partyroomId, that.partyroomId)
                    && Objects.equals(botUserId, that.botUserId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(partyroomId, botUserId);
        }
    }
}
