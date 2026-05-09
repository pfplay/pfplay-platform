package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.CountryCodeConverter;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.Objects;


@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "CREW",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_crew_partyroom_user", columnNames = {"partyroom_id", "user_id"})
        },
        indexes = {
                @Index(name = "crew_partyroom_id_user_id_IDX", columnList = "partyroom_id, user_id"),
                @Index(name = "crew_user_id_is_active_IDX", columnList = "user_id, is_active")
        })
@Entity
public class CrewData extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "crew_id")
    private Long id;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "partyroom_id", nullable = false)),
    })
    private PartyroomId partyroomId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "uid", column = @Column(name = "user_id")),
    })
    private UserId userId;

    // 파티룸에서 활동중 여부
    @Column(name = "is_active")
    private boolean isActive;
    // 파티룸 내에서의 등급
    private GradeType gradeType;
    // 영구 퇴장 페널티 부과 여부
    private boolean isBanned;
    //
    @Column(nullable = false)
    private LocalDateTime enteredAt;
    private LocalDateTime exitedAt;
    // Presence grace window: when set, the crew row is in PENDING_EXIT (still is_active=1
    // but client signal lost). Cleared on reconnect; promoted to OFFLINE when grace elapses.
    private LocalDateTime pendingExitAt;
    // 입장 시점에 프론트엔드가 전달한 국가 코드 (ISO 3166-1 alpha-2). 전달되지 않으면 null.
    @Column(name = "country_code", length = 2)
    @Convert(converter = CountryCodeConverter.class)
    private CountryCode countryCode;

    // 데이터 엔티티 생성자
    protected CrewData() {}

    @Builder
    public CrewData(Long id, PartyroomId partyroomId, UserId userId, GradeType gradeType,
                    boolean isActive, boolean isBanned, LocalDateTime enteredAt, LocalDateTime exitedAt,
                    LocalDateTime pendingExitAt,
                    CountryCode countryCode,
                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.partyroomId = partyroomId;
        this.userId = userId;
        this.gradeType = gradeType;
        this.isActive = isActive;
        this.isBanned = isBanned;
        this.enteredAt = enteredAt;
        this.exitedAt = exitedAt;
        this.pendingExitAt = pendingExitAt;
        this.countryCode = countryCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ── Business Methods ──

    public static CrewData create(PartyroomId partyroomId, UserId userId, GradeType gradeType,
                                  CountryCode countryCode, LocalDateTime now) {
        return CrewData.builder()
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(gradeType)
                .isActive(true)
                .isBanned(false)
                .enteredAt(now)
                .countryCode(countryCode)
                .build();
    }

    public static CrewData create(PartyroomId partyroomId, UserId userId, GradeType gradeType,
                                  CountryCode countryCode) {
        return create(partyroomId, userId, gradeType, countryCode, LocalDateTime.now());
    }

    public void deactivatePresence(LocalDateTime now) {
        this.isActive = false;
        this.exitedAt = now;
        this.pendingExitAt = null;
    }

    public void deactivatePresence() {
        deactivatePresence(LocalDateTime.now());
    }

    /**
     * Re-entry path: clears prior exited_at and pending_exit_at so a stale value from a
     * previous session does not poison the new active row. Fixes Issue #193.
     */
    public void activatePresence(LocalDateTime now) {
        this.isActive = true;
        this.enteredAt = now;
        this.exitedAt = null;
        this.pendingExitAt = null;
    }

    public void activatePresence() {
        activatePresence(LocalDateTime.now());
    }

    public void markPending(LocalDateTime now) {
        if (this.pendingExitAt == null) {
            this.pendingExitAt = now;
        }
    }

    public void clearPending() {
        this.pendingExitAt = null;
    }

    public boolean isPendingExit() {
        return this.pendingExitAt != null;
    }

    public void updateGrade(GradeType gradeType) {
        this.gradeType = gradeType;
    }

    public void updateCountryCode(CountryCode countryCode) {
        this.countryCode = countryCode;
    }

    public boolean isBelowGrade(GradeType threshold) {
        return this.gradeType.isLowerThan(threshold);
    }

    public boolean isGradeHigherThan(CrewData other) {
        return this.gradeType.isHigherThan(other.gradeType);
    }

    public void enforceBan() {
        this.isBanned = true;
    }

    public void releaseBan() {
        this.isBanned = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CrewData crewData = (CrewData) o;
        return Objects.equals(id, crewData.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}