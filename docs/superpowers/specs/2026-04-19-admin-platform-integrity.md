# PFPlay Admin Platform — Integrity Enforcement (§8)

> Companion to `2026-04-19-admin-platform-design.md`. 본 문서는 §8 Integrity Enforcement를 다룬다.
> Cross-context FK 없는 상태에서 데이터 무결성을 보장하는 방법.

## 8.0 Problem Statement

Cross-context FK 제거 결정(§3.2)의 결과로 DB가 전통적으로 잡아주던 제약들이 사라진다:

| 전통적 FK가 잡아주던 것 | FK 없이 어떻게? |
|---|---|
| `member.user_account_id`가 실존하는 user_account를 가리키는지 | 애플리케이션 불변식 (생성 시점 검증) |
| user_account 삭제 시 member 자동 삭제/처리 | 도메인 이벤트 (UserAccountWithdrawn) |
| 중복 member per user_account 방지 | UNIQUE 제약 (DB 레벨 OK) |
| partyroom_admin_action.partyroom_id가 실존하는 룸을 가리키는지 | 애플리케이션 검증 (생성 시점) + Orphan 탐지 배치 |

FK가 제공했던 "자동 무결성"의 대체로 4가지 계층을 쌓는다:

1. **Command 시점 검증** — 데이터 생성/수정 시 존재 확인
2. **도메인 이벤트 전파** — 상태 변화를 관련 컨텍스트에 알림
3. **Orphan 탐지 배치** — 주기적 inconsistency 검출
4. **자동 테스트** — 불변식 단위 테스트

## 8.1 Layer 1 — Command 시점 검증

### 8.1.1 생성 검증

각 도메인 엔티티 생성 시 교차 컨텍스트 참조 값은 **반드시 해당 컨텍스트에 존재함을 확인** 후 저장.

예: `Administrator.create(UserAccountId, role, grantedBy)`:

```java
@Service
class AdministratorService {
    private final UserAccountRepository userAccountRepo;  // IAM 리포지토리
    private final AdministratorRepository adminRepo;       // 자기 컨텍스트
    
    public Administrator create(CreateAdminCommand cmd, AdministratorId by) {
        // 1. UserAccount 존재 + providerType=LOCAL 검증
        UserAccount ua = userAccountRepo.findById(cmd.userAccountId())
            .orElseThrow(() -> new InvariantViolation("user_account not found"));
        if (ua.getProviderType() != ProviderType.LOCAL) {
            throw new InvariantViolation("Administrator requires LOCAL provider");
        }
        
        // 2. 중복 방지 (DB UNIQUE는 있지만 friendlier error)
        if (adminRepo.existsByUserAccountId(cmd.userAccountId())) {
            throw new InvariantViolation("already an administrator");
        }
        
        // 3. SUPER_ADMIN 유일성 (DB functional index는 최종 방어)
        if (cmd.role() == SUPER_ADMIN) {
            throw new InvariantViolation("cannot create additional SUPER_ADMIN via API");
        }
        
        // 4. grantedBy 검증 (자기 컨텍스트 — FK가 잡지만 에러 메시지 개선)
        Administrator granter = adminRepo.findById(by)
            .orElseThrow(() -> new InvariantViolation("granter not found"));
        
        // 5. 생성
        Administrator admin = Administrator.create(cmd.userAccountId(), cmd.role(), by);
        return adminRepo.save(admin);
    }
}
```

핵심:
- **생성 시 반드시 해당 정보 조회** — 없으면 불변식 위반 예외
- 이걸 건너뛰면 dangling 참조가 발생 — 검증 생략 금지

### 8.1.2 수정 검증

상태 전이 시 불변식 체크. 예: `Partyroom.suspend()`:

```java
class Partyroom {
    public void suspend() {
        if (this.status == PartyroomStatus.TERMINATED) {
            throw new InvariantViolation("terminated partyroom cannot be suspended");
        }
        this.status = PartyroomStatus.SUSPENDED;
        this.recordEvent(new PartyroomSuspended(this.partyroomId));
    }
}
```

### 8.1.3 삭제 시 전파 (withdrawal)

UserAccount 탈퇴 시 여러 컨텍스트 반응:

```java
class UserAccount {
    public void withdraw() {
        // 1. IAM 내부 처리
        this.email = anonymizeEmail(this.userAccountId);
        this.passwordHash = null;
        this.withdrawnAt = LocalDateTime.now();
        
        // 2. 이벤트 발행
        this.recordEvent(new UserAccountWithdrawn(this.userAccountId));
    }
}
```

→ Party와 Administration의 이벤트 리스너가 각자 반응 (§8.2).

## 8.2 Layer 2 — 도메인 이벤트 전파

### 8.2.1 이벤트 종류 (신규 정의)

| 이벤트 | 발행 컨텍스트 | 리스너 | 리스너 처리 |
|---|---|---|---|
| `UserAccountWithdrawn` | IAM | Party | Member/Guest 프로필 익명화 |
| `UserAccountWithdrawn` | IAM | Administration | Administrator `revokedAt` 세팅 |
| `PartyroomSuspendedByAdmin` | Party | Administration | `partyroom_admin_action` INSERT |
| `PartyroomTerminatedByAdmin` | Party | Administration | 동일 |
| `PartyroomMetaUpdatedByAdmin` | Party | Administration | 동일 |
| `MemberTierChanged` | Party | Administration | `partyroom_admin_action` + `user_activity_log` |
| `AdminPenalizedCrew` | Party (또는 Administration 발행) | Administration | `partyroom_admin_action` INSERT with correlation id |
| `CrewAccessedEvent (ENTER/EXIT)` | Party (기존) | Administration, Party | `user_activity_log` + `partyroom.crew_count` 갱신 |
| `UserSignedIn` | IAM | Administration | `user_activity_log` SIGNED_IN |

### 8.2.2 이벤트 신뢰성

- **`@TransactionalEventListener(AFTER_COMMIT)`** — 비즈니스 트랜잭션 커밋 후 실행
- **`@Async`** — 핫패스 비동기화
- 로그/audit 쓰기는 **at-most-once 수용**: 드물게 유실돼도 비즈니스 실패 없음
- 중요한 후속 처리 (탈퇴 전파 등)는 **outbox 패턴** 고려 (MVP 이후)

### 8.2.3 주의점: 이벤트 실패 시 후속 처리

MVP는 단순 `@Async` — 실패 시 로그만 남음. 문제되면 나중에 outbox 도입.

outbox 패턴 개요 (향후):
- 비즈니스 DB 변경 + outbox 테이블 INSERT를 **같은 트랜잭션**으로
- 별도 publisher가 outbox 테이블 polling → 이벤트 퍼블리시 → outbox에서 soft-delete
- At-least-once 보장

## 8.3 Layer 3 — Orphan 탐지 배치

Command 시점 검증을 우회하는 경로(DB 직접 조작, 이벤트 유실 등)로 인해 drift 발생 가능. 정기적으로 탐지.

### 8.3.1 탐지 쿼리 예시

```sql
-- Orphan member (user_account가 사라진 경우)
SELECT m.member_id, m.user_account_id
FROM member m
LEFT JOIN user_account ua ON m.user_account_id = ua.user_id
WHERE ua.user_id IS NULL;

-- Administrator가 providerType != LOCAL인 계정을 가리키는 경우 (불변식 위반)
SELECT a.administrator_id, ua.provider_type
FROM administrator a
JOIN user_account ua ON a.user_account_id = ua.user_id
WHERE ua.provider_type <> 'LOCAL';

-- partyroom_admin_action의 partyroom_id가 존재하지 않는 경우
SELECT paa.action_id, paa.partyroom_id
FROM partyroom_admin_action paa
LEFT JOIN partyroom p ON paa.partyroom_id = p.partyroom_id
WHERE p.partyroom_id IS NULL;

-- partyroom.crew_count drift (denormalized vs 실제)
SELECT p.partyroom_id, p.crew_count AS denormalized, 
       (SELECT COUNT(*) FROM crew c WHERE c.partyroom_id = p.partyroom_id AND c.is_active = 1) AS actual
FROM partyroom p
HAVING denormalized <> actual;
```

### 8.3.2 실행 방식

- **Scheduled job** (Spring `@Scheduled`) — 매일 새벽 1시
- 결과 요약을 관리자 알림 채널(Slack/Discord)로 전송
- Drift 발견 시:
  - `crew_count` drift → 자동 보정 + 알림
  - Orphan member/admin → 알림만 (자동 처리 위험, 수동 판단)
- 실행 이력을 `data_integrity_check_log` 테이블에 기록 (optional, 향후)

### 8.3.3 Batch 코드 구조

```java
@Component
class DataIntegrityChecker {
    @Scheduled(cron = "0 0 1 * * *")  // 매일 01:00
    public void runChecks() {
        int orphanMembers = detectOrphanMembers();
        int invalidAdmins = detectInvalidAdminProviders();
        int crewCountDrift = correctCrewCountDrift();
        int orphanActions = detectOrphanPartyroomActions();
        
        notifyAdminChannel(Map.of(
            "orphan_members", orphanMembers,
            "invalid_admin_providers", invalidAdmins,
            "crew_count_drift_corrected", crewCountDrift,
            "orphan_admin_actions", orphanActions
        ));
    }
}
```

## 8.4 Layer 4 — 자동 테스트

### 8.4.1 불변식 단위 테스트

각 aggregate의 불변식 검증을 단위 테스트로 강제:

```java
@Test
void administrator_cannot_be_created_with_social_provider() {
    UserAccount social = UserAccount.forSocial("u@x.com", ProviderType.GOOGLE);
    userAccountRepo.save(social);
    
    assertThatThrownBy(() -> 
        administratorService.create(
            new CreateAdminCommand(social.getUserAccountId(), Role.ADMIN), 
            superAdminId))
        .isInstanceOf(InvariantViolation.class)
        .hasMessageContaining("LOCAL");
}

@Test
void cannot_create_second_super_admin_via_api() {
    assertThatThrownBy(() ->
        administratorService.create(
            new CreateAdminCommand(validLocalUserAccountId, Role.SUPER_ADMIN), 
            superAdminId))
        .isInstanceOf(InvariantViolation.class);
}

@Test
void terminated_partyroom_cannot_be_suspended() {
    Partyroom room = createActiveRoom();
    room.terminate();
    
    assertThatThrownBy(() -> room.suspend())
        .isInstanceOf(InvariantViolation.class);
}
```

### 8.4.2 통합 테스트 — 이벤트 전파

```java
@SpringBootTest
class UserWithdrawalEventPropagationTest {
    @Test
    void user_withdrawal_anonymizes_member_profile() {
        UserAccount ua = createSocialUserAccount();
        Member member = createMemberFor(ua);
        
        userAccountService.withdraw(ua.getUserAccountId());
        
        // 이벤트 비동기이므로 Awaitility 사용
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Member reloaded = memberRepo.findByUserAccountId(ua.getUserAccountId()).orElseThrow();
            assertThat(reloaded.getProfile().getNickname()).isEqualTo("탈퇴한 회원");
        });
    }
}
```

### 8.4.3 권한 회귀 테스트

§5.7 참고. 매 PR마다 자동 실행.

## 8.5 Layer 5 (선택) — Architecture Test

**ArchUnit** 등의 도구로 패키지 경계 정적 검증:

```java
@ArchTest
static final ArchRule administration_should_not_depend_on_party_internals =
    noClasses().that().resideInAPackage("..administration..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..party.domain.entity..",
            "..party.domain.service..",
            "..party.adapter.."
        );

@ArchTest
static final ArchRule iam_should_not_depend_on_other_contexts =
    noClasses().that().resideInAPackage("..iam..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..party..",
            "..administration..",
            "..operations.."
        );
```

컴파일 타임 보장 — CI에서 fail 시 PR 블록.

## 8.6 Summary — 무결성 보장 다층 방어

```
 +---------------------------+
 |  Layer 1: Command 검증    |  생성/수정 시 존재 확인, 불변식 체크
 +---------------------------+
              ↓ (혹시 통과 못한 경우)
 +---------------------------+
 |  Layer 2: Domain Events   |  상태 변화를 관련 컨텍스트에 전파
 +---------------------------+
              ↓ (혹시 이벤트 유실)
 +---------------------------+
 |  Layer 3: Orphan Batch    |  주기적 drift 탐지 & 보정
 +---------------------------+
              ↓ (코드 결함)
 +---------------------------+
 |  Layer 4: Test suite      |  단위/통합 테스트가 회귀 방지
 +---------------------------+
              ↓
 +---------------------------+
 |  Layer 5: ArchUnit (opt)  |  컴파일 타임 컨텍스트 경계 강제
 +---------------------------+
```

FK가 사라졌지만 **다층 방어로 무결성 수준은 실질적으로 유지** 가능.

## 8.7 FK 없음으로 얻는 이득

마지막으로, 왜 이 비용을 감수하는지 다시 짚음:

1. **진짜 컨텍스트 분리** — DB 레벨에서 결합 없음 → 각 컨텍스트 독립 진화
2. **마이크로서비스 분리 용이** — 분리 시 FK 제거 비용 0, 이미 이벤트 기반
3. **스키마 변경 유연성** — 한 컨텍스트의 테이블 재설계가 다른 컨텍스트 저해 안 함
4. **Domain event 인프라 강제** — 자연스럽게 CQRS/event-sourcing 방향 유도
5. **개발자 멘탈 모델** — "내 컨텍스트만 내가 owns"가 명확

단점(FK 잃음)을 Layer 1~5로 보완. **엔지니어링 비용 투자 대비 가치 있는 트레이드오프**로 판단.

---

**다음 문서**: `2026-04-19-admin-platform-roadmap.md` (§9, §10, §11)
