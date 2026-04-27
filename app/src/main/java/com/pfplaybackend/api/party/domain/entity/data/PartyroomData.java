package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.*;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@AggregateRoot
@Getter
// @DynamicUpdate is load-bearing for PR 7's atomic counter pattern.
// Without it, JPA dirty-checking saves (e.g., terminate() from cleanup job)
// would write back ALL columns including a stale crew_count, silently
// overwriting concurrent atomic UPDATEs from PartyroomCounterListener.
// crewCount/lastActivityAt/displayFlag have no setters — they are mutated
// exclusively via PartyroomRepository.{increment,decrement,touch}* methods.
@DynamicInsert
@DynamicUpdate
@Table(
        name = "PARTYROOM",
        indexes = {
                @Index(name = "partyroom_host_id_IDX", columnList = "host_id")
        }
)
@Entity
public class PartyroomData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "partyroom_id")
    private Long id;

    @Transient
    private PartyroomId partyroomId;

    @Enumerated(EnumType.STRING)
    private StageType stageType;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "uid", column = @Column(name = "host_id")),
    })
    private UserId hostId;

    private String title;
    private String introduction;
    @Convert(converter = LinkDomainConverter.class)
    private LinkDomain linkDomain;
    @Convert(converter = PlaybackTimeLimitConverter.class)
    private PlaybackTimeLimit playbackTimeLimit;
    private String noticeContent;

    // V6: 상태 모델 (is_terminated → status ENUM 3-state)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PartyroomStatus status;

    // V6: denormalized counter — atomic UPDATE만 갱신 (PartyroomRepository.incrementCrewCount/decrementCrewCount)
    @Column(name = "crew_count", nullable = false)
    private int crewCount;

    // V6: 최근 활동 시각 — atomic UPDATE만 갱신 (PartyroomRepository.touchLastActivity)
    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    // V6: 표시 플래그 — getter only. setter는 PR 8 (Administration BC).
    @Enumerated(EnumType.STRING)
    @Column(name = "display_flag", nullable = false, length = 16)
    private DisplayFlag displayFlag;

    @PostPersist
    public void updatePartyroomId() {
        initializePartyroomId();
    }

    @PostLoad
    private void postLoad() {
        initializePartyroomId();
    }

    private void initializePartyroomId() {
        this.partyroomId = new PartyroomId(this.id);
    }

    protected PartyroomData() {}

    @Builder
    public PartyroomData(Long id, PartyroomId partyroomId, UserId hostId, StageType stageType,
                         String title, String introduction, LinkDomain linkDomain, PlaybackTimeLimit playbackTimeLimit,
                         String noticeContent, PartyroomStatus status, int crewCount,
                         LocalDateTime lastActivityAt, DisplayFlag displayFlag,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.partyroomId = partyroomId;
        this.hostId = hostId;
        this.stageType = stageType;
        this.title = title;
        this.introduction = introduction;
        this.linkDomain = linkDomain;
        this.playbackTimeLimit = playbackTimeLimit;
        this.noticeContent = noticeContent;
        this.status = status != null ? status : PartyroomStatus.ACTIVE;
        this.crewCount = crewCount;
        this.lastActivityAt = lastActivityAt;
        this.displayFlag = displayFlag != null ? displayFlag : DisplayFlag.NORMAL;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ── Factory Method ──

    public static PartyroomData create(String title, String introduction, LinkDomain linkDomain,
                                        PlaybackTimeLimit timeLimit, StageType stageType, UserId hostId) {
        return PartyroomData.builder()
                .stageType(stageType)
                .hostId(hostId)
                .title(title)
                .introduction(introduction)
                .linkDomain(linkDomain)
                .playbackTimeLimit(timeLimit)
                .noticeContent("")
                .status(PartyroomStatus.ACTIVE)
                .crewCount(0)
                .displayFlag(DisplayFlag.NORMAL)
                .build();
    }

    // ── Business Methods ──

    public PartyroomData updateBaseInfo(String title, String introduction, LinkDomain linkDomain, PlaybackTimeLimit timeLimit) {
        this.title = title;
        this.introduction = introduction;
        this.linkDomain = linkDomain;
        this.playbackTimeLimit = timeLimit;
        return this;
    }

    // ── State Inspection ──

    public boolean isActive() {
        return this.status == PartyroomStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return this.status == PartyroomStatus.SUSPENDED;
    }

    /** TERMINATED 여부. 시그니처 유지 — 기존 호출자 무수정 작동. */
    public boolean isTerminated() {
        return this.status == PartyroomStatus.TERMINATED;
    }

    // ── State Transitions ──
    // ACTIVE     -- suspend() --> SUSPENDED
    // SUSPENDED  -- restore() --> ACTIVE
    // ACTIVE/SUSPENDED -- terminate() --> TERMINATED  (terminal)
    // 비허용 전이는 ILLEGAL_STATE_TRANSITION 예외.

    public void suspend() {
        if (this.status != PartyroomStatus.ACTIVE) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.SUSPENDED;
    }

    public void restore() {
        if (this.status != PartyroomStatus.SUSPENDED) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.ACTIVE;
    }

    public void terminate() {
        if (this.status == PartyroomStatus.TERMINATED) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.TERMINATED;
        registerEvent(new PartyroomClosedEvent(this.partyroomId, this.hostId, this.title));
    }

    // ── Display Flag (admin-only via Administration BC; ArchUnit 가드는 별 task) ──

    /**
     * displayFlag 변경. TERMINATED 룸은 거부 (ILLEGAL_STATE_TRANSITION).
     * SUSPENDED 룸은 허용 — admin이 정지 중에도 분류 라벨 변경 가능.
     * 같은 값 재설정은 idempotent (no exception, no event recommended at caller).
     */
    public void setDisplayFlagFeatured() {
        guardDisplayFlagChangeable();
        this.displayFlag = DisplayFlag.FEATURED;
    }

    public void setDisplayFlagHidden() {
        guardDisplayFlagChangeable();
        this.displayFlag = DisplayFlag.HIDDEN;
    }

    public void setDisplayFlagNormal() {
        guardDisplayFlagChangeable();
        this.displayFlag = DisplayFlag.NORMAL;
    }

    private void guardDisplayFlagChangeable() {
        if (this.status == PartyroomStatus.TERMINATED) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
    }

    // ── Validation ──

    public void validateHost(UserId userId) {
        if (!this.hostId.equals(userId)) {
            throw ExceptionCreator.create(GradeException.GRADE_INSUFFICIENT_FOR_OPERATION);
        }
    }

    /** TERMINATED 룸 가드. 시맨틱 유지 — SUSPENDED는 통과. */
    public void validateNotTerminated() {
        if (isTerminated()) {
            throw ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED);
        }
    }

    public PartyroomData assignPartyroomId(PartyroomId partyroomId) {
        this.partyroomId = partyroomId;
        return this;
    }
}
