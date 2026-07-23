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
import com.pfplaybackend.api.party.domain.event.SessionSupersededEvent;
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
    public TryEnterResult tryEnter(PartyroomId partyroomId, CountryCode countryCode) {
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

        // Validate Crew Condition — 다른 룸에서 옮겨오는 중이면 helper가 기존 룸을 auto-exit한다.
        // helper는 resolve한 active 룸 DTO를 그대로 반환(같은-룸 재입장 분기는 tryEnter 고유 흐름이라
        // 여기서 처리). 다른 룸이었다면 helper 내부에서 이미 exit 완료 → fall-through.
        Optional<ActivePartyroomDto> optActiveRoomInfo =
                autoExitPriorActiveRoomIfDifferent(userId, partyroomId);

        if (optActiveRoomInfo.isPresent()) {
            ActivePartyroomDto activeRoomInfo = optActiveRoomInfo.get();
            if (partyroomId.equals(new PartyroomId(activeRoomInfo.id()))) {
                // 같은 룸 재입장 (websocket 재연결 등) — 이미 active인 경로.
                // ⚠️ 이전 코드는 여기서도 ENTER 이벤트 발행 → counter inflate.
                // PR 7: countryCode만 갱신, 이벤트 발행 금지 (spec §7.2 spurious ENTER 차단).
                log.info("[tryEnter] Same room re-entry — countryCode 갱신만, no ENTER publish. userId={}, partyroomId={}",
                        userId, partyroomId.getId());
                CrewData crew = existingCrew.orElseThrow(() ->
                        ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM));
                crew.updateCountryCode(countryCode);
                CrewData saved = aggregatePort.saveCrew(crew);
                // SE4 defense-in-depth — REST same-room re-entry cancels grace: a page
                // refresh sets pending_exit_at on STOMP DISCONNECT, and "REST enter = user
                // is here" so we clear it immediately rather than relying solely on the
                // STOMP CONNECT-time clearPending (closes the SE4 race even if the reconnect
                // is delayed). DB-only clear via the already-injected aggregatePort keeps
                // this cycle-free (injecting PartyroomPresenceService would form a
                // constructor cycle via forceOffline→exitInternal). Any stale Redis
                // presence-timer key is safely no-op'd by forceOffline's
                // !isPendingExit() guard, which also deletes the leftover key.
                int graceCancelled = aggregatePort.clearCrewPending(partyroomId, userId);
                if (graceCancelled > 0) {
                    log.debug("[presence] grace cancelled via REST same-room re-entry — userId={}, partyroomId={}",
                            userId, partyroomId.getId());
                }
                enforceHostInvariant(partyroom, userId, saved);
                // 멤버십 유지(이미 active) → reactivated=false (web#402)
                return new TryEnterResult(saved, false);
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
        if (!result.raceLoser) {
            enforceHostInvariant(partyroom, userId, result.crew);
        }
        // transitioned(inactive→active 재활성 또는 신규 INSERT) → reactivated. web#402 재연결 소비자용 신호.
        return new TryEnterResult(result.crew, result.transitioned);
    }

    /**
     * Crew를 active 상태로 만든다 (idempotent). 호출자에게 transitioned 플래그를 돌려 ENTER 이벤트
     * 발행 여부를, raceLoser 플래그를 돌려 추가 mutation(예: enforceHostInvariant healing) skip 여부를
     * 판단하게 한다.
     *
     * 흐름:
     *  1. activateCrew atomic toggle 시도 → 1이면 inactive→active 전이 성공. (transitioned=true, raceLoser=false)
     *  2. 0 (row missing 또는 이미 active) → findCrew 분기:
     *     a. row 없음 → INSERT. 동시 INSERT 패배자는 DataIntegrityViolationException —
     *        outer 트랜잭션이 rollback-only 상태가 되므로 winner 조회는 별 트랜잭션(REQUIRES_NEW)에서
     *        수행. 본 호출자는 idempotent return하며 raceLoser=true로 표시한다 — 호출자 측에서
     *        추가 saveCrew 호출 시 UnexpectedRollbackException 위험이 있으므로 skip해야 한다.
     *     b. row 있고 active → countryCode만 갱신, idempotent. (transitioned=false, raceLoser=false)
     */
    private CrewActivationResult ensureCrewActive(PartyroomData partyroom, UserId userId, CountryCode countryCode) {
        PartyroomId pid = partyroom.getPartyroomId();
        LocalDateTime now = LocalDateTime.now(clock);

        int activated;
        try {
            activated = aggregatePort.activateCrew(pid, userId, now);
        } catch (DataIntegrityViolationException e) {
            // #349 activateCrew UPDATE(is_active false→true)가 위반할 수 있는 유일한 유니크는
            // uk_crew_active_user 다 = 동시에 다른 방 입장이 이 유저를 먼저 active 로 선점(버스트 패자).
            // 조용한 다중 활성 대신 CONFLICT 로 실패 → outer tx 롤백 → 활성 방은 정확히 1개 유지.
            // (PR2 의 유저 단위 락이 들어오면 애초에 이 경쟁 자체가 사라져 이 경로는 사문화된다.)
            throw asConcurrentEntryOrRethrow(e);
        }
        if (activated == 1) {
            CrewData crew = aggregatePort.findCrew(pid, userId).orElseThrow();
            crew.updateCountryCode(countryCode);
            return new CrewActivationResult(aggregatePort.saveCrew(crew), true, false);
        }

        Optional<CrewData> existing = aggregatePort.findCrew(pid, userId);
        if (existing.isEmpty()) {
            try {
                CrewData newCrew = CrewData.create(pid, userId, GradeType.LISTENER, countryCode, now);
                return new CrewActivationResult(aggregatePort.saveCrew(newCrew), true, false);
            } catch (DataIntegrityViolationException e) {
                // #349 INSERT 가 uk_crew_active_user 위반이면 다른 방 동시 입장 패자 → CONFLICT.
                if (isActiveUserConstraint(e)) {
                    throw ExceptionCreator.create(CrewException.CONCURRENT_ACTIVE_ROOM);
                }
                // uk_crew_partyroom_user 위반 = 같은 방 동시 INSERT 패배 — outer tx가 rollback-only 상태.
                // winner row 조회는 별 트랜잭션에서.
                log.info("[ensureCrewActive] CONCURRENT_INSERT_LOSER - userId={}, partyroomId={}",
                        userId, pid.getId());
                CrewData winner = findCrewInNewTransaction(pid, userId);
                return new CrewActivationResult(winner, false, true);
            }
        }

        // 이미 active — countryCode만 갱신
        CrewData crew = existing.get();
        crew.updateCountryCode(countryCode);
        return new CrewActivationResult(aggregatePort.saveCrew(crew), false, false);
    }

    /**
     * #349 "유저당 활성 방 1개" DB 불변식(uk_crew_active_user)을 강제하는 UNIQUE 이름.
     * MySQL 무결성 위반 메시지("Duplicate entry '...' for key 'CREW.uk_crew_active_user'")로 식별한다.
     */
    private static final String ACTIVE_USER_UNIQUE_CONSTRAINT = "uk_crew_active_user";

    /**
     * activateCrew UPDATE 가 던진 무결성 위반이 uk_crew_active_user 면 동시 입장 경쟁 패자로 간주해
     * CONFLICT 도메인 예외로 매핑, 아니면(예상 밖 무결성 위반) 원 예외를 그대로 surface 한다.
     */
    private RuntimeException asConcurrentEntryOrRethrow(DataIntegrityViolationException e) {
        if (isActiveUserConstraint(e)) {
            return ExceptionCreator.create(CrewException.CONCURRENT_ACTIVE_ROOM);
        }
        return e;
    }

    private boolean isActiveUserConstraint(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        String msg = (root == null) ? null : root.getMessage();
        return msg != null && msg.contains(ACTIVE_USER_UNIQUE_CONSTRAINT);
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
     * saveCrew가 UnexpectedRollbackException을 던진다. tryEnter 호출 측에서
     * CrewActivationResult.raceLoser 플래그로 식별하여 skip한다.
     *
     * saveCrew 호출은 명시적이지만 같은 트랜잭션 내 managed entity merge라 DB write 비용은 0
     * (dirty check + flush 시 단일 UPDATE). 명시 호출은 expel/exit 패턴과의 대칭 + mock 기반
     * 테스트의 검증 용이성을 위해 유지.
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

    private record CrewActivationResult(CrewData crew, boolean transitioned, boolean raceLoser) {}

    /**
     * tryEnter 결과. {@code reactivated}=true 는 inactive→active 재활성(또는 신규 INSERT)을 뜻한다.
     * web#402 재연결 resync 소비자에게만 유의미 — 재연결은 crew row가 이미 존재해 INSERT 분기에 닿지 않는다.
     */
    public record TryEnterResult(CrewData crew, boolean reactivated) {}

    private void publishAccessChangedEvent(PartyroomId partyroomId, CrewData crew, UserId userId) {
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroomId, new CrewId(crew.getId()), userId, AccessType.ENTER));
    }

    @Transactional
    public void enterByHost(UserId hostId, PartyroomData partyroom) {
        assertHasProfile(hostId);
        // B/T1-3 (#212): one-active-room invariant. enterByHost는 HOST crew를 무조건
        // active INSERT하므로, host가 이미 다른 룸에 active(예: 다른 방 DJ/listener)면
        // 두 룸 동시 active → getActivePartyroomByUserId .fetchOne() 폭발(wedge)이 된다.
        // HOST crew INSERT 전에 tryEnter와 동일한 active-room 체크 + auto-exit을 수행
        // (exit→handleDjQueueOnLeave로 기존 룸 DJ큐 정리 + playback skip). 방금 생성한
        // 신규 룸은 partyroomId 비교로 제외되므로 self-exit 위험 없음. createMainStage는
        // enterByHost를 거치지 않고 tryEnter가 처리하므로 이중 exit 없음.
        autoExitPriorActiveRoomIfDifferent(hostId, partyroom.getPartyroomId());
        CrewData crew = CrewData.create(partyroom.getPartyroomId(), hostId, GradeType.HOST, null, LocalDateTime.now(clock));
        try {
            aggregatePort.saveCrew(crew);
        } catch (DataIntegrityViolationException e) {
            // #351 auto-exit statement 와 본 INSERT 사이의 밀리초 창에 같은 유저의 타 기기
            // tryEnter 가 active_user_id 슬롯을 선점하면 uk_crew_active_user 위반. 정합성은
            // 무해(방 생성 포함 outer tx 전체 롤백)하므로 미처리 500 대신 ensureCrewActive 와
            // 동일하게 CRW-005 CONFLICT 로 매핑해 재시도를 유도한다. 그 외 위반은 원예외 유지.
            throw asConcurrentEntryOrRethrow(e);
        }
    }

    /**
     * one-active-room invariant 강제용 공유 helper (tryEnter / enterByHost 양쪽 사용 — DRY).
     *
     * <p>사용자의 권위 있는 active 룸을 resolve하고, 그것이 {@code target}과 다르면
     * (= 다른 룸에서 옮겨오는 중) 그 기존 룸을 {@link #exitInternal} 경로로 auto-exit한다.
     * exitInternal은 {@code handleDjQueueOnLeave}를 호출하므로 기존 룸의 DJ큐 정리 +
     * (현재 DJ였다면) playback skip이 함께 일어난다.
     *
     * <p>PENDING_EXIT(V16 grace) 상태의 기존 룸도 의도적으로 여기서 auto-exit된다 —
     * markCrewPending은 pending_exit_at만 SET하고 is_active=true를 유지하므로
     * getMyActivePartyroom(is_active=true 필터)에 그대로 잡힌다. 진행 중인 forceOffline과도
     * idempotent하다: 양쪽 모두 deactivateCrew의 atomic {@code WHERE is_active=true} toggle을
     * 거치므로 누가 이기든 EXIT 이벤트와 DJ큐 정리는 정확히 1회만 발생한다.
     *
     * <p>resolve한 DTO를 그대로 반환한다(부작용 없이) — tryEnter는 같은-룸 재입장 분기에서
     * 이 DTO가 필요하다. tryEnter 호출 측 동작은 추출 전과 byte-identical:
     * "active 룸 있고 target과 다름 → log + exit, fall-through". 같은-룸 재입장 분기와
     * PR-1 clearPending은 tryEnter 본문에 그대로 남겨 동작을 보존한다.
     *
     * @return 사용자의 active 룸 DTO(있으면). exit 수행 여부와 무관하게 resolve된 값을 반환.
     */
    private Optional<ActivePartyroomDto> autoExitPriorActiveRoomIfDifferent(UserId userId, PartyroomId target) {
        Optional<ActivePartyroomDto> optActiveRoomInfo = partyroomQueryService.getMyActivePartyroom(userId);
        log.info("[autoExitPriorActiveRoom] Active room check - userId={}, hasActiveRoom={}, activeRoomId={}, targetRoomId={}",
                userId,
                optActiveRoomInfo.isPresent(),
                optActiveRoomInfo.map(ActivePartyroomDto::id).orElse(null),
                target.getId());

        if (optActiveRoomInfo.isPresent()) {
            ActivePartyroomDto activeRoomInfo = optActiveRoomInfo.get();
            if (!target.equals(new PartyroomId(activeRoomInfo.id()))) {
                // 다른 룸에서 옮겨오는 중 — 기존 룸 exit (DJ큐 정리 + playback skip 포함)
                log.info("[autoExitPriorActiveRoom] Auto-exit from another room - userId={}, exitingRoomId={}, enteringRoomId={}",
                        userId, activeRoomInfo.id(), target.getId());
                exitInternal(new PartyroomId(activeRoomInfo.id()), userId);
                // #369 멀티 디바이스 승계 — 밀려난 유저에게 SESSION_SUPERSEDED 알림을 보내기 위한 이벤트.
                // exit 는 위에서 서버가 이미 완결했고, 이 이벤트/알림은 순수 UX 신호다. AFTER_COMMIT 리스너가
                // 소비하므로 exit 이 커밋된 뒤에만 발송된다(서버 권위). tryEnter/enterByHost 공용 helper 라
                // 양 진입 경로의 밀어냄을 모두 커버한다.
                eventPublisher.publishEvent(new SessionSupersededEvent(
                        userId, new PartyroomId(activeRoomInfo.id()), target, clock.millis()));
            }
        }
        return optActiveRoomInfo;
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
        log.info("[handleDjQueueOnLeave] partyroomId={}, crewId={}, wasInDjQueue={}, wasCurrentDj={}",
                partyroom.getPartyroomId().getId(), crewId.getId(), wasInDjQueue, wasCurrentDj);

        partyroomAggregateService.removeDjFromQueue(partyroom.getPartyroomId(), crewId);

        if (wasInDjQueue) {
            eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE_EXIT, crewId));
        }
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroom.getPartyroomId());
        }
    }
}
