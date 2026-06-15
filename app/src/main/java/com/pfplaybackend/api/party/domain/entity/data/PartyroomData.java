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
// activeCrewCount/lastActivityAt/displayFlag have no setters — they are mutated
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

    /**
     * 활성(is_active=true) crew row 의 수 — denormalized counter.
     *
     * <p>의미상 "방 안에 현재 머무는 인원". V16 grace 정책상 PENDING_EXIT 도 is_active=true 라 카운트에 포함된다
     * (transient disconnect 중 grace window 안 사용자도 "방 안에 있는 것" 으로 본다).
     *
     * <p>누적 입장자 수 아님. 과거 max 아님. atomic UPDATE 만으로 갱신
     * ({@link com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository#incrementCrewCount}
     * / {@link com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository#decrementCrewCount}).
     *
     * <p>DB 컬럼은 {@code crew_count} 로 유지 (V6 schema, prod 라이브 — rename 시 expand-and-contract 마이그 필요).
     */
    @Column(name = "crew_count", nullable = false)
    private int activeCrewCount;

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
                         String noticeContent, PartyroomStatus status, int activeCrewCount,
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
        this.activeCrewCount = activeCrewCount;
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
                .activeCrewCount(0)
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

    /**
     * 신고 가능 여부 (PR 13 G2 / Spec §3.1 D6).
     * Allow-list: ACTIVE 만 신고 가능. 새 enum 값 추가 시 명시적으로 opt-in해야 신고 접수 가능 — fail-safe.
     * SUSPENDED/TERMINATED 룸은 이미 운영자가 인지/조치된 상태이므로 추가 신고 접수 불요.
     */
    public boolean isReportable() {
        return this.status == PartyroomStatus.ACTIVE;
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

    /**
     * MAIN 시스템 stage 전용 lifecycle 복원 (#280).
     * <p>일반 파티룸은 {@link #terminate()} 가 terminal 이지만, MAIN 은 시스템 stage 라
     * {@code ApplicationReadyEventListener.initializeMainStage} 가 부팅마다 자동 복원해야 한다.
     * 어떤 비-ACTIVE 상태에서든 ACTIVE 로 idempotent 복원하며, GENERAL stage 에서 호출 시 거부한다.
     * <p>일반 파티룸의 terminal invariant 는 그대로 보존된다.
     */
    public void reactivateAsMainStage() {
        if (this.stageType != StageType.MAIN) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.ACTIVE;
    }

    /**
     * MAIN 시스템 stage 보호 가드 (#280 root-cause fix).
     * <p>모든 termination 경로 (host self-delete / cron / admin terminate) 가
     * MAIN 까지 잡지 않도록 하는 도메인 측 가드. caller 의 호출 의도 컨텍스트와
     * 결합돼 footgun 을 차단한다 (suspend/setHidden 같은 비-terminal 동작은 정책 미정으로 미적용).
     */
    public void validateNotMainStage() {
        if (this.stageType == StageType.MAIN) {
            throw ExceptionCreator.create(PartyroomException.MAIN_STAGE_PROTECTED);
        }
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
