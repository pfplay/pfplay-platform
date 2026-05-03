package com.pfplaybackend.api.party.domain.entity.data.history;

import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Builder
@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "CREW_PENALTY_HISTORY"
)
@Entity
@AllArgsConstructor
public class CrewPenaltyHistoryData extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "partyroom_id")),
    })
    private PartyroomId partyroomId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "punisher_crew_id")),
    })
    private CrewId punisherCrewId;

    @Enumerated(EnumType.STRING)
    private PenaltyType penaltyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "punisher_type", nullable = false)
    private PunisherType punisherType;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "punished_crew_id")),
    })
    private CrewId punishedCrewId;

    @Column(name = "penalty_reason", length = 255)
    private String penaltyReason;

    @Column(name = "penalty_date", nullable = false)
    private LocalDateTime penaltyDate;

    @Column(name = "is_released", nullable = false)
    private boolean released;

    @Column(name = "release_date")
    private LocalDateTime releaseDate;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "released_by_crew_id")),
    })
    private CrewId releasedByCrewId;

    protected CrewPenaltyHistoryData() {}

    public void release(CrewId releaserCrewId, LocalDateTime now) {
        this.released = true;
        this.releasedByCrewId = releaserCrewId;
        this.releaseDate = now;
    }

    public void release(CrewId releaserCrewId) {
        release(releaserCrewId, LocalDateTime.now());
    }

    /**
     * 어드민이 부과한 페널티의 해제.
     * admin-released signal: released_by_crew_id IS NULL (Q8.9(i)에서 별도 released_by_type 컬럼 미도입).
     * V1 스키마에서 released_by_crew_id는 nullable이라 추가 마이그 불필요.
     * admin 정체는 partyroom_admin_action.administrator_id 경로로 식별
     * (correlation: metadata.crew_penalty_history_id).
     *
     * Caller invariant: this.punisherType must be ADMIN before invoking.
     * The admin service path (AdminCrewPenaltyCommandService.release, PR 9) checks
     * this guard. Crew path (CrewPenaltyCommandService.releaseCrewPenalty, PR 9 §4.3
     * guard) refuses to release admin-applied rows entirely. No entity-level
     * Preconditions guard added — symmetry with release(CrewId, LocalDateTime) which
     * also trusts callers (existing convention).
     */
    public void releaseByAdmin(LocalDateTime now) {
        release(null, now);
    }
}