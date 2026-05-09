package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.DjQueueChangedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.specification.PartyroomEntrySpecification;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomAccessCommandService {

    private final ApplicationEventPublisher eventPublisher;
    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAggregateService partyroomAggregateService;
    private final PartyroomQueryService partyroomQueryService;
    private final PlaybackControlPort playbackControlPort;
    private final UserProfileQueryPort userProfileQueryPort;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate requiresNewReadOnlyTx;

    @PostConstruct
    void initTxTemplates() {
        this.requiresNewReadOnlyTx = new TransactionTemplate(transactionManager);
        this.requiresNewReadOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewReadOnlyTx.setReadOnly(true);
    }

    @Transactional
    public CrewData tryEnter(PartyroomId partyroomId, CountryCode countryCode) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        log.info("[tryEnter] START - userId={}, targetPartyroomId={}", userId, partyroomId.getId());

        assertHasProfile(userId);

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        long activeCrewCount = aggregatePort.countActiveCrews(partyroomId);
        Optional<CrewData> existingCrew = aggregatePort.findCrew(partyroomId, userId);
        log.debug("[tryEnter] Partyroom found - partyroomId={}, status={}, crewCount={}",
                partyroomId.getId(), partyroom.getStatus(), activeCrewCount);

        new PartyroomEntrySpecification().validate(partyroom, activeCrewCount, existingCrew);

        // Validate Crew Condition
        Optional<ActivePartyroomDto> optActiveRoomInfo = partyroomQueryService.getMyActivePartyroom(userId);
        log.info("[tryEnter] Active room check - userId={}, hasActiveRoom={}, activeRoomId={}",
                userId,
                optActiveRoomInfo.isPresent(),
                optActiveRoomInfo.map(ActivePartyroomDto::id).orElse(null));

        if (optActiveRoomInfo.isPresent()) {
            ActivePartyroomDto activeRoomInfo = optActiveRoomInfo.get();
            if (!partyroomId.equals(new PartyroomId(activeRoomInfo.id()))) {
                // 다른 룸에서 옮겨오는 중 — 기존 룸 exit 후 진입 흐름으로 fall-through
                log.info("[tryEnter] Auto-exit from another room - userId={}, exitingRoomId={}, enteringRoomId={}",
                        userId, activeRoomInfo.id(), partyroomId.getId());
                exit(new PartyroomId(activeRoomInfo.id()));
            } else {
                // 같은 룸 재입장 (websocket 재연결 등) — 이미 active인 경로.
                // ⚠️ 이전 코드는 여기서도 ENTER 이벤트 발행 → counter inflate.
                // PR 7: countryCode만 갱신, 이벤트 발행 금지 (spec §7.2 spurious ENTER 차단).
                log.info("[tryEnter] Same room re-entry — countryCode 갱신만, no ENTER publish. userId={}, partyroomId={}",
                        userId, partyroomId.getId());
                CrewData crew = existingCrew.orElseThrow(() ->
                        ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM));
                crew.updateCountryCode(countryCode);
                CrewData saved = aggregatePort.saveCrew(crew);
                enforceHostInvariant(partyroom, userId, saved);
                return saved;
            }
        }

        // 새 진입 (또는 다른 룸에서 옮겨와 새 진입)
        CrewActivationResult result = ensureCrewActive(partyroom, userId, countryCode);
        if (result.transitioned) {
            log.info("[tryEnter] SUCCESS - userId={}, partyroomId={}, crewId={}",
                    userId, partyroomId.getId(), result.crew.getId());
            publishAccessChangedEvent(partyroom.getPartyroomId(), result.crew, userId);
        } else {
            log.info("[tryEnter] IDEMPOTENT - already active or concurrent insert loser, no event. userId={}, partyroomId={}",
                    userId, partyroomId.getId());
        }
        enforceHostInvariant(partyroom, userId, result.crew);
        return result.crew;
    }

    /**
     * Crew를 active 상태로 만든다 (idempotent). 호출자에게 transitioned 플래그를 돌려 ENTER 이벤트
     * 발행 여부를 판단하게 한다.
     *
     * 흐름:
     *  1. activateCrew atomic toggle 시도 → 1이면 inactive→active 전이 성공.
     *  2. 0 (row missing 또는 이미 active) → findCrew 분기:
     *     a. row 없음 → INSERT. 동시 INSERT 패배자는 DataIntegrityViolationException —
     *        outer 트랜잭션이 rollback-only 상태가 되므로 winner 조회는 별 트랜잭션(REQUIRES_NEW)에서
     *        수행. 본 호출자는 idempotent return.
     *     b. row 있고 active → countryCode만 갱신, idempotent.
     */
    private CrewActivationResult ensureCrewActive(PartyroomData partyroom, UserId userId, CountryCode countryCode) {
        PartyroomId pid = partyroom.getPartyroomId();
        LocalDateTime now = LocalDateTime.now(clock);

        int activated = aggregatePort.activateCrew(pid, userId, now);
        if (activated == 1) {
            CrewData crew = aggregatePort.findCrew(pid, userId).orElseThrow();
            crew.updateCountryCode(countryCode);
            return new CrewActivationResult(aggregatePort.saveCrew(crew), true);
        }

        Optional<CrewData> existing = aggregatePort.findCrew(pid, userId);
        if (existing.isEmpty()) {
            try {
                CrewData newCrew = CrewData.create(pid, userId, GradeType.LISTENER, countryCode, now);
                return new CrewActivationResult(aggregatePort.saveCrew(newCrew), true);
            } catch (DataIntegrityViolationException e) {
                // INSERT race 패배 — outer tx가 rollback-only 상태. winner row 조회는 별 트랜잭션에서.
                log.info("[ensureCrewActive] CONCURRENT_INSERT_LOSER - userId={}, partyroomId={}",
                        userId, pid.getId());
                CrewData winner = findCrewInNewTransaction(pid, userId);
                return new CrewActivationResult(winner, false);
            }
        }

        // 이미 active — countryCode만 갱신
        CrewData crew = existing.get();
        crew.updateCountryCode(countryCode);
        return new CrewActivationResult(aggregatePort.saveCrew(crew), false);
    }

    /**
     * Outer @Transactional이 rollback-only로 진입한 후에도 안전하게 SELECT 가능하도록 별 트랜잭션 사용.
     * Spring AOP self-invocation 우회를 위해 @Transactional(REQUIRES_NEW) 메서드 호출 대신
     * TransactionTemplate 직접 사용 — 같은 클래스 내부 호출은 proxy를 거치지 않아
     * @Transactional 어노테이션이 무효화되기 때문.
     */
    private CrewData findCrewInNewTransaction(PartyroomId partyroomId, UserId userId) {
        return requiresNewReadOnlyTx.execute(status ->
                aggregatePort.findCrew(partyroomId, userId).orElseThrow()
        );
    }

    /**
     * Host invariant 강제: 진입 user가 partyroom host인데 grade가 HOST가 아니면 승격.
     * Idempotent — 이미 HOST면 no-op. createMainStage가 enterByHost를 건너뛰는 경우와
     * 기존 잘못된 grade row를 자동 healing.
     *
     * 호출 측 PRECONDITION: outer @Transactional이 rollback-only 상태가 아닐 것.
     * INSERT race-loser 분기에서는 호출하지 말 것 — outer tx가 rollback-only이므로
     * saveCrew가 UnexpectedRollbackException을 던진다. CrewActivationResult.raceLoser
     * 플래그로 식별하여 skip한다 (가드는 Task 5에서 추가됨).
     */
    private void enforceHostInvariant(PartyroomData partyroom, UserId userId, CrewData crew) {
        if (!userId.equals(partyroom.getHostId())) return;
        if (crew.getGradeType() == GradeType.HOST) return;
        GradeType prev = crew.getGradeType();
        crew.updateGrade(GradeType.HOST);
        aggregatePort.saveCrew(crew);
        log.info("[enforceHostInvariant] HEALED - userId={}, partyroomId={}, crewId={}, {} → HOST",
                userId, partyroom.getPartyroomId().getId(), crew.getId(), prev);
    }

    private record CrewActivationResult(CrewData crew, boolean transitioned) {}

    private void publishAccessChangedEvent(PartyroomId partyroomId, CrewData crew, UserId userId) {
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroomId, new CrewId(crew.getId()), userId, AccessType.ENTER));
    }

    @Transactional
    public void enterByHost(UserId hostId, PartyroomData partyroom) {
        assertHasProfile(hostId);
        CrewData crew = CrewData.create(partyroom.getPartyroomId(), hostId, GradeType.HOST, null, LocalDateTime.now(clock));
        aggregatePort.saveCrew(crew);
    }

    /**
     * 도메인 invariant: 프로필(아바타 설정)이 등록된 사용자만 partyroom의 active crew가 될 수 있다.
     * 프로필 미보유 사용자(super-admin/시스템 사용자 등)가 crew로 등록되면 customer 응답 빌드 시
     * ProfileSettingDto null lookup → NPE를 일으킨다 (PA-7 회귀 패턴 차단). enterByHost와 tryEnter
     * 양쪽에서 호출하여 어떤 진입 경로가 추가되더라도 invariant가 코드 레벨에서 강제되도록 한다.
     */
    private void assertHasProfile(UserId userId) {
        java.util.Map<UserId, com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto> profiles =
                userProfileQueryPort.getUsersProfileSetting(java.util.List.of(userId));
        if (!profiles.containsKey(userId)) {
            throw ExceptionCreator.create(CrewException.PROFILE_REQUIRED);
        }
    }

    @Transactional
    public void exit(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        exitInternal(partyroomId, userId);
    }

    /**
     * exit() body extracted so non-HTTP callers (presence grace expiration listener,
     * reconcile cron) can run the same flow without ThreadLocal AuthContext setup.
     * Strict invariant: callers MUST already have authority to act on this user/room
     * (e.g., the user themselves, or system-level recovery actions).
     */
    @Transactional
    public void exitInternal(PartyroomId partyroomId, UserId userId) {
        log.info("[exit] START - userId={}, partyroomId={}", userId, partyroomId.getId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        Optional<CrewData> optionalCrew = aggregatePort.findCrew(partyroomId, userId);
        if (optionalCrew.isEmpty()) {
            log.warn("[exit] INVALID_ACTIVE_ROOM - userId={} has no crew row in partyroomId={}",
                    userId, partyroomId.getId());
            throw ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM);
        }

        CrewData crew = optionalCrew.get();
        LocalDateTime now = LocalDateTime.now(clock);

        // Atomic toggle. 0 반환 시 이미 inactive — idempotent return.
        int deactivated = aggregatePort.deactivateCrew(partyroomId, userId, now);
        if (deactivated == 0) {
            log.info("[exit] IDEMPOTENT - already inactive, no event published. userId={}, partyroomId={}",
                    userId, partyroomId.getId());
            return;
        }

        handleDjQueueOnLeave(partyroom, new CrewId(crew.getId()));
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroom.getPartyroomId(), new CrewId(crew.getId()),
                userId, AccessType.EXIT));
    }

    /**
     * Admin-initiated expulsion. Migrated to atomic toggle for symmetry with exit() —
     * concurrent expel + voluntary exit on same crew would otherwise both publish EXIT
     * → counter -2 once PartyroomCounterListener (PR 7 Task 11) is wired.
     *
     * enforceBan side effect always applies (even on race-loss), since the ban must
     * persist regardless of who deactivated the crew first.
     */
    @Transactional
    public void expel(PartyroomData partyroom, CrewData crew, boolean isPermanent)  {
        LocalDateTime now = LocalDateTime.now(clock);
        int deactivated = aggregatePort.deactivateCrew(
                partyroom.getPartyroomId(), crew.getUserId(), now);

        if (deactivated == 0) {
            // Race loser — already inactive (admin double-click or concurrent voluntary exit).
            // Ban must still apply if permanent.
            log.info("[expel] IDEMPOTENT - crew already inactive, no EXIT event. crewId={}", crew.getId());
            if (isPermanent) {
                crew.enforceBan();
                aggregatePort.saveCrew(crew);
            }
            return;
        }

        if (isPermanent) {
            crew.enforceBan();
            aggregatePort.saveCrew(crew);
        }

        handleDjQueueOnLeave(partyroom, new CrewId(crew.getId()));
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroom.getPartyroomId(), new CrewId(crew.getId()),
                crew.getUserId(), AccessType.EXIT));
    }

    private void handleDjQueueOnLeave(PartyroomData partyroom, CrewId crewId) {
        boolean wasInDjQueue = aggregatePort.findDj(partyroom.getPartyroomId(), crewId)
                .isPresent();
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroom.getPartyroomId());
        boolean wasCurrentDj = playbackState.isActivated() && wasInDjQueue
                && playbackState.isCurrentDj(crewId);

        partyroomAggregateService.removeDjFromQueue(partyroom.getPartyroomId(), crewId);

        if (wasInDjQueue) {
            eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE_EXIT, crewId));
        }
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroom.getPartyroomId());
        }
    }
}
