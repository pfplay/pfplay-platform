# 가상 DJ P3-A (방 컨셉 + LLM 채팅 응답) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가상 DJ 봇이 방 대화에 확률적으로 끼어들어 LLM(Claude)으로 응답하게 한다. 봇 페르소나(프리셋 라이브러리 + 봇 매핑)와 방 컨셉(기존 title/introduction)을 결합해 "살아있는 방"을 만든다.

**Architecture:** path A 계승 — 봇은 실계정이고 채팅 송신은 party BC의 가드된 서비스(`PartyroomChatCommandService.sendMessageAsCrew`)로만 한다. 채팅 발행 → Redis `chat_message_sent` 구독자(`BotChatTrigger`)가 사람 메시지만 트리거(루프 차단) → 방별 in-flight 락 + 확률/쿨다운 게이트 통과 시 전용 스레드풀에서 LLM 워커가 프롬프트(페르소나+방맥락+최근채팅) 조립 → Claude 호출 → `sendMessageAsCrew`. 페르소나 CRUD·봇 매핑은 어드민(pfplay-admin) 콘솔.

**Tech Stack:** Java 21 / Spring Boot 3.2.3 / JPA(MySQL) / Flyway / Redis(Lettuce, pub-sub + SETNX lock) / QueryDSL / Anthropic Messages API / React(Vite, FSD) + TanStack Query + zod.

**참조 spec:** `docs/superpowers/specs/2026-06-02-virtual-dj-p3-chat-design.md`

---

## 사전 규칙 (모든 chunk 공통)

- **TDD**: 각 Task는 실패 테스트 → 최소 구현 → 통과 → 커밋. @superpowers:test-driven-development.
- **빌드/테스트 실행**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수 (`reference_pfplay_platform_jdk`). 예:
  `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PersonaServiceTest"`
- **마이그레이션 주의**: test는 create-drop이라 마이그레이션↔엔티티 drift를 가린다(`reference_ddl_auto_create_drop_hides_migration_drift`). 제약은 **엔티티에도** 반영하고, chunk 완료 시 로컬 풀스택 validate 부팅으로 검증.
- **커밋 메시지 한글** (`feedback_korean_issue_commit_pr`), 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **브랜치**: `feature/virtual-dj-p3-chat` (이미 origin/develop에서 분기됨).
- **dev 머지 전 로컬 docker-compose 풀스택 e2e 필수** (`feedback_local_e2e_before_dev_merge`, `reference_local_docker_compose`). 마지막 §최종 게이트 참조.

---

## Chunk 1: 페르소나 라이브러리 (백엔드 CRUD)

페르소나 프리셋 테이블 + CRUD. 템플릿 = `VirtualSongPackData`/`VirtualSongPackService`/`AdminVirtualDjController` 송팩 슬라이스를 그대로 답습.

**File Structure:**
- Create: `app/src/main/resources/db/migration/V25__create_virtual_persona.sql`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/entity/data/VirtualPersonaData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/persistence/VirtualPersonaRepository.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/exception/VirtualDjException.java` (페르소나 에러코드 추가)
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualPersonaService.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/in/web/payload/CreatePersonaRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/in/web/payload/UpdatePersonaRequest.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/in/web/AdminVirtualDjController.java` (페르소나 엔드포인트 추가)
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/VirtualPersonaServiceTest.java`

### Task 1.1: 마이그레이션 V25 — virtual_persona 테이블

- [ ] **Step 1: 마이그레이션 작성**

`app/src/main/resources/db/migration/V25__create_virtual_persona.sql`:
```sql
-- P3-A: 가상 DJ 봇 페르소나(LLM 지시문) 프리셋 라이브러리
CREATE TABLE virtual_persona (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name          VARCHAR(64)     NOT NULL COMMENT '어드민 식별용 페르소나 이름',
    instruction   TEXT            NOT NULL COMMENT 'LLM system 지시문(성격/톤/장르 성향)',
    is_active     TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '비활성 시 신규 매핑 불가(기존 보존)',
    created_at    DATETIME(0)     NOT NULL,
    updated_at    DATETIME(0)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_virtual_persona_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 로컬 validate 부팅으로 마이그레이션 적용 확인** (chunk 마지막에 일괄 확인 가능하나, 단독 확인 시)

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:flywayInfo` 또는 local profile 부팅.
Expected: V25 pending → 적용 후 success. (DATETIME(0) 라운딩 주의 — `reference_mysql_datetime0_rounding`, 단 persona는 시간쿼리 없음.)

- [ ] **Step 3: 커밋**
```bash
git add app/src/main/resources/db/migration/V25__create_virtual_persona.sql
git commit -m "feat(p3a): virtual_persona 테이블 마이그레이션(V25)"
```

### Task 1.2: VirtualPersonaData 엔티티

- [ ] **Step 1: 엔티티 작성** — 템플릿 `VirtualSongPackData.java` 답습(`extends BaseEntity`, `@Entity @Table @Getter @DynamicInsert @DynamicUpdate`, IDENTITY PK, protected 기본생성자 + `@Builder` + static `create` + 도메인 메서드).

`VirtualPersonaData.java`:
```java
package com.pfplaybackend.api.virtualdj.domain.entity.data;

import com.pfplaybackend.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "virtual_persona")
@Getter
@DynamicInsert
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VirtualPersonaData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    @Comment("어드민 식별용 페르소나 이름")
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Comment("LLM system 지시문")
    private String instruction;

    @Column(name = "is_active", nullable = false)
    @Comment("비활성 시 신규 매핑 불가")
    private boolean active = true;

    @Builder
    private VirtualPersonaData(String name, String instruction, boolean active) {
        this.name = name;
        this.instruction = instruction;
        this.active = active;
    }

    public static VirtualPersonaData create(String name, String instruction) {
        return VirtualPersonaData.builder().name(name).instruction(instruction).active(true).build();
    }

    public void update(String name, String instruction) { this.name = name; this.instruction = instruction; }
    public void setActive(boolean active) { this.active = active; }
}
```
> ⚠️ `@NoArgsConstructor` import (`lombok.NoArgsConstructor`) 추가. `VirtualSongPackData`의 정확한 import/애너테이션 세트를 먼저 열어 그대로 맞출 것.

- [ ] **Step 2: 리포지토리 작성** `VirtualPersonaRepository.java`:
```java
package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualPersonaRepository extends JpaRepository<VirtualPersonaData, Long> {
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, Long id);
}
```

- [ ] **Step 3: 커밋**
```bash
git add app/src/main/java/com/pfplaybackend/api/virtualdj/domain/entity/data/VirtualPersonaData.java \
        app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/persistence/VirtualPersonaRepository.java
git commit -m "feat(p3a): VirtualPersonaData 엔티티 + 리포지토리"
```

### Task 1.3: 예외 코드 추가

- [ ] **Step 1**: `VirtualDjException.java` enum에 페르소나 코드 추가 (기존 `SONG_PACK_DUPLICATE_NAME` 패턴 답습 — 코드번호는 기존 마지막+1 확인 후):
```java
PERSONA_NOT_FOUND("VDJ-0XX", "페르소나를 찾을 수 없습니다.", ErrorType.NOT_FOUND),
PERSONA_DUPLICATE_NAME("VDJ-0XX", "이미 존재하는 페르소나 이름입니다.", ErrorType.CONFLICT),
```
- [ ] **Step 2: 커밋** `git commit -am "feat(p3a): 페르소나 예외 코드"`

### Task 1.4: VirtualPersonaService (CRUD) — TDD

- [ ] **Step 1: 실패 테스트 작성** `VirtualPersonaServiceTest.java` (송팩 서비스 테스트 패턴 답습, 리포지토리 mock):
```java
// 핵심 케이스:
// - create: 정상 생성 시 저장 호출 + id 반환
// - create: 중복 이름(existsByName=true) → VirtualDjException PERSONA_DUPLICATE_NAME
// - update: 존재하지 않는 id → PERSONA_NOT_FOUND
// - update: 다른 row가 같은 이름(existsByNameAndIdNot=true) → PERSONA_DUPLICATE_NAME
// - delete: 존재하지 않는 id → PERSONA_NOT_FOUND
// - setActive: 토글 반영
// - list: 전체 반환(DTO 매핑)
```
예시 한 케이스:
```java
@Test
void create_중복이름이면_예외() {
    given(personaRepository.existsByName("Chill")).willReturn(true);
    assertThatThrownBy(() -> service.create("Chill", "느긋하게"))
        .isInstanceOf(VirtualDjException.class); // 또는 BusinessException 래퍼 — 송팩 테스트 확인 후 일치
}
```

- [ ] **Step 2: 실패 확인** `... --tests "*VirtualPersonaServiceTest"` → 컴파일/실패.

- [ ] **Step 3: 서비스 구현** — 템플릿 `VirtualSongPackService` 답습. 내부 record DTO(`PersonaListItem(id,name,active)`, `PersonaDetail(id,name,instruction,active)`):
```java
@Service
@RequiredArgsConstructor
public class VirtualPersonaService {
    private final VirtualPersonaRepository personaRepository;

    @Transactional(readOnly = true)
    public List<PersonaListItem> list() { /* findAll → map */ }

    @Transactional(readOnly = true)
    public PersonaDetail get(Long id) { /* findById.orElseThrow(PERSONA_NOT_FOUND) */ }

    @Transactional
    public Long create(String name, String instruction) {
        if (personaRepository.existsByName(name)) throw ...(PERSONA_DUPLICATE_NAME);
        return personaRepository.save(VirtualPersonaData.create(name, instruction)).getId();
    }

    @Transactional
    public void update(Long id, String name, String instruction) {
        if (personaRepository.existsByNameAndIdNot(name, id)) throw ...(PERSONA_DUPLICATE_NAME);
        VirtualPersonaData p = personaRepository.findById(id).orElseThrow(...PERSONA_NOT_FOUND);
        p.update(name, instruction);
    }

    @Transactional
    public void setActive(Long id, boolean active) { /* find → setActive */ }

    @Transactional
    public void delete(Long id) {
        if (!personaRepository.existsById(id)) throw ...(PERSONA_NOT_FOUND);
        personaRepository.deleteById(id);
    }
    // record PersonaListItem / PersonaDetail
}
```
> 예외 throw 방식은 송팩 서비스가 쓰는 `ExceptionCreator.create(VirtualDjException.XXX)` 와 정확히 일치시킬 것.
> **삭제 시 매핑 참조 가드는 Chunk 2에서 추가**(bot_persona_assignment 생긴 후). 여기선 단순 삭제.

- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: 커밋** `git commit -am "feat(p3a): VirtualPersonaService CRUD + 단위테스트"`

### Task 1.5: 페르소나 어드민 엔드포인트

- [ ] **Step 1: payload DTO 작성** (jakarta validation, 송팩 payload 답습):
```java
public record CreatePersonaRequest(@NotBlank @Size(max=64) String name, @NotBlank @Size(max=4000) String instruction) {}
public record UpdatePersonaRequest(@NotBlank @Size(max=64) String name, @NotBlank @Size(max=4000) String instruction, boolean active) {}
```

- [ ] **Step 2: 컨트롤러에 엔드포인트 추가** — `AdminVirtualDjController`에 송팩 CRUD와 동일 형태(`@PreAuthorize("@adminAuth.canManageVirtualDj()")`, `ApiCommonResponse` 봉투, 204):
```java
@GetMapping("/virtual-dj/personas")
public ApiCommonResponse<List<PersonaListItem>> listPersonas() { return ApiCommonResponse.success(personaService.list()); }

@GetMapping("/virtual-dj/personas/{id}")
public ApiCommonResponse<PersonaDetail> getPersona(@PathVariable Long id) { return ApiCommonResponse.success(personaService.get(id)); }

@PostMapping("/virtual-dj/personas")
public ApiCommonResponse<CreatedIdResponse> createPersona(@Valid @RequestBody CreatePersonaRequest req) {
    return ApiCommonResponse.success(new CreatedIdResponse(personaService.create(req.name(), req.instruction())));
}

@PutMapping("/virtual-dj/personas/{id}")
public ResponseEntity<Void> updatePersona(@PathVariable Long id, @Valid @RequestBody UpdatePersonaRequest req) {
    personaService.update(id, req.name(), req.instruction());
    personaService.setActive(id, req.active());
    return ResponseEntity.noContent().build();
}

@DeleteMapping("/virtual-dj/personas/{id}")
public ResponseEntity<Void> deletePersona(@PathVariable Long id) { personaService.delete(id); return ResponseEntity.noContent().build(); }
```
> `CreatedIdResponse`는 송팩이 쓰는 기존 DTO 재사용.

- [ ] **Step 3: 컨트롤러 슬라이스 테스트(있으면 패턴 답습)** 또는 통합 스모크. 송팩 컨트롤러 테스트 유무 확인 후 동일 수준으로.
- [ ] **Step 4: 커밋** `git commit -am "feat(p3a): 페르소나 어드민 CRUD 엔드포인트"`

### Chunk 1 완료 게이트
- [ ] `:app:test` 전체 GREEN.
- [ ] 로컬 local-profile 부팅 validate 통과(V25 적용).
- [ ] @superpowers:requesting-code-review (chunk 단위).

---

## Chunk 2: 봇 ↔ 페르소나 매핑 (백엔드)

봇 1명당 페르소나 0..1. 매핑 테이블 `bot_persona_assignment`(bot_user_id PK). 행 없음 = 페르소나 없음 = 채팅 침묵. 일괄 매핑은 `BotAvatarAdminService.distribute` 패턴(사전 `filterBotUserIds` 필터링으로 트랜잭션 rollback-only 회피).

**File Structure:**
- Create: `.../db/migration/V26__create_bot_persona_assignment.sql`
- Create: `.../virtualdj/domain/entity/data/BotPersonaAssignmentData.java`
- Create: `.../virtualdj/adapter/out/persistence/BotPersonaAssignmentRepository.java`
- Modify: `.../virtualdj/adapter/out/persistence/BotPoolQueryRepositoryImpl.java` (roster에 persona 조인)
- Modify: `.../virtualdj/application/dto/BotRosterRow.java` (personaId/personaName 필드)
- Modify: `.../virtualdj/adapter/in/web/payload/...` (BotRosterItemResponse에 persona 노출)
- Create: `.../virtualdj/application/service/BotPersonaAssignmentService.java`
- Create: `.../virtualdj/adapter/in/web/payload/AssignPersonaRequest.java`
- Modify: `AdminVirtualDjController.java` (매핑 엔드포인트)
- Modify: `VirtualPersonaService.delete` (in-use 가드)
- Test: `.../application/service/BotPersonaAssignmentServiceTest.java`

### Task 2.1: 마이그레이션 V26 — bot_persona_assignment

- [ ] **Step 1**: ⚠️ **먼저 `user_account` PK 컬럼명 확인**(FK 대상). `V24__create_virtual_dj.sql` 및 user_account 엔티티(`UserAccountData`)에서 PK 컬럼명(`id` vs `user_id`)을 확인하고 FK를 정확히 작성.
```sql
-- P3-A: 봇(가상멤버) ↔ 페르소나 매핑. 행 존재 = 그 봇은 채팅 참여, 행 없음 = 침묵.
CREATE TABLE bot_persona_assignment (
    bot_user_id   BIGINT UNSIGNED NOT NULL COMMENT '봇 user_account PK',
    persona_id    BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME(0)     NOT NULL,
    updated_at    DATETIME(0)     NOT NULL,
    PRIMARY KEY (bot_user_id),
    CONSTRAINT fk_bpa_persona FOREIGN KEY (persona_id) REFERENCES virtual_persona(id)
    -- bot_user_id FK는 user_account PK 컬럼명 확인 후 추가(또는 무FK + 앱가드, 기존 컨벤션 확인)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
> 기존 테이블들이 user_account로 FK를 거는지(아니면 앱레벨 가드만 쓰는지) `V24`/다른 마이그레이션 컨벤션을 확인해 일치시킬 것.

- [ ] **Step 2: 커밋** `git add ... && git commit -m "feat(p3a): bot_persona_assignment 매핑 테이블(V26)"`

### Task 2.2: 매핑 엔티티 + 리포지토리

- [ ] **Step 1**: `BotPersonaAssignmentData.java` — `@Entity @Table(name="bot_persona_assignment")`, `@Id Long botUserId`(IDENTITY 아님, 명시 할당), `Long personaId`, BaseEntity 상속. static `create(botUserId, personaId)` + `changePersona(personaId)`.
- [ ] **Step 2**: `BotPersonaAssignmentRepository extends JpaRepository<BotPersonaAssignmentData, Long>`:
```java
boolean existsByPersonaId(Long personaId);     // 삭제 in-use 가드용
void deleteByBotUserIdIn(Collection<Long> botUserIds);
List<BotPersonaAssignmentData> findByBotUserIdIn(Collection<Long> botUserIds);
```
- [ ] **Step 3: 커밋**

### Task 2.3: roster에 persona 노출

- [ ] **Step 1**: `BotRosterRow`에 `Long personaId, String personaName` 추가. `BotPoolQueryRepositoryImpl.findRoster()` QueryDSL에 `bot_persona_assignment` + `virtual_persona` left join 추가(봇별 현재 페르소나). `BotRosterItemResponse`(payload)에 personaId/personaName 매핑.
- [ ] **Step 2**: 기존 roster 테스트가 있으면 persona null 케이스 GREEN 확인. 커밋.

### Task 2.4: BotPersonaAssignmentService (일괄 매핑) — TDD

- [ ] **Step 1: 실패 테스트** `BotPersonaAssignmentServiceTest.java`:
```java
// - assign(botIds, personaId): 페르소나 존재+active 확인, botIds 중 실제 봇만 filterBotUserIds로 필터,
//   upsert(있으면 changePersona, 없으면 create). 반환: 적용된 봇 수.
// - assign: 비활성 페르소나 → 예외(PERSONA_INACTIVE) 또는 거부(설계: 비활성은 신규 매핑 불가)
// - assign: 존재하지 않는 personaId → PERSONA_NOT_FOUND
// - assign: botIds에 비봇 userId 섞이면 그 id는 무시(필터), 봇만 적용
// - unassign(botIds): 해당 행 삭제
```
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현** — `BotAvatarAdminService.distribute` 패턴(사전 `botPoolQueryRepository.filterBotUserIds(botIds)`로 봇만 추림 → rollback-only 회피). 비활성 페르소나 매핑 거부(`PERSONA_INACTIVE` 신규 코드 또는 BAD_REQUEST). upsert는 `findByBotUserIdIn`으로 기존 조회 후 분기.
```java
@Transactional
public int assign(List<Long> botIds, Long personaId) {
    VirtualPersonaData persona = personaRepository.findById(personaId).orElseThrow(...NOT_FOUND);
    if (!persona.isActive()) throw ...(PERSONA_INACTIVE);
    List<Long> botUserIds = botPoolQueryRepository.filterBotUserIds(botIds);
    Map<Long, BotPersonaAssignmentData> existing = assignmentRepository.findByBotUserIdIn(botUserIds).stream()
        .collect(toMap(BotPersonaAssignmentData::getBotUserId, identity()));
    for (Long botUserId : botUserIds) {
        BotPersonaAssignmentData row = existing.get(botUserId);
        if (row != null) row.changePersona(personaId);
        else assignmentRepository.save(BotPersonaAssignmentData.create(botUserId, personaId));
    }
    return botUserIds.size();
}

@Transactional
public int unassign(List<Long> botIds) {
    List<Long> botUserIds = botPoolQueryRepository.filterBotUserIds(botIds);
    assignmentRepository.deleteByBotUserIdIn(botUserIds);
    return botUserIds.size();
}
```
- [ ] **Step 4: 통과.** **Step 5: 커밋.**

### Task 2.5: 매핑 엔드포인트 + 삭제 in-use 가드

- [ ] **Step 1**: payload `AssignPersonaRequest(@NotEmpty List<Long> botIds, Long personaId)` (personaId null이면 unassign 의미, 또는 별도 endpoint).
- [ ] **Step 2**: 컨트롤러 (avatar distribute 답습):
```java
@PostMapping("/virtual-dj/bots/persona/assign")
public ApiCommonResponse<AssignPersonaResponse> assignPersona(@Valid @RequestBody AssignPersonaRequest req) {
    int applied = assignmentService.assign(req.botIds(), req.personaId());
    return ApiCommonResponse.success(new AssignPersonaResponse(applied));
}
@PostMapping("/virtual-dj/bots/persona/unassign")
public ApiCommonResponse<AssignPersonaResponse> unassignPersona(@Valid @RequestBody UnassignPersonaRequest req) { ... }
```
- [ ] **Step 3**: `VirtualPersonaService.delete`에 in-use 가드 추가 — `assignmentRepository.existsByPersonaId(id)`면 거부(`PERSONA_IN_USE`) 또는 매핑 cascade 정책 결정(권장: 거부, 어드민이 먼저 unassign). 테스트 추가.
- [ ] **Step 4: 커밋.**

### Chunk 2 완료 게이트
- [ ] `:app:test` GREEN + local validate 부팅(V25·V26).
- [ ] @superpowers:requesting-code-review.

---

## Chunk 3: 채팅 송신 시임 + 설정 인프라 + ArchUnit

봇이 path A로 채팅 송신할 가드 오버로드 + P3 chat 설정 키 + kill switch. LLM·트리거 없이 이 시임만 독립 테스트.

**File Structure:**
- Modify: `.../party/application/dto/chat/ChatMessageDto.java` (`ofCrew` 팩토리)
- Modify: `.../party/application/service/chat/PartyroomChatCommandService.java` (`sendMessageAsCrew`)
- Modify: `.../operations/domain/value/ConfigKey.java` (vdj.chat.* 키 상수)
- Create: `.../virtualdj/application/service/VirtualDjChatConfig.java` (설정 읽기, SystemConfigCache 래핑)
- Create: `.../db/migration/V27__seed_virtual_dj_chat_config.sql` (키 시드 + kill switch)
- Modify: `app/src/test/java/.../architecture/VirtualDjArchitectureTest.java` (채팅 송신 규칙)
- Test: `.../party/application/service/chat/PartyroomChatCommandServiceTest.java`, `.../virtualdj/application/service/VirtualDjChatConfigTest.java`

### Task 3.1: ChatMessageDto.ofCrew + sendMessageAsCrew — TDD

- [ ] **Step 1: 실패 테스트** `PartyroomChatCommandServiceTest.java`:
```java
// - sendMessageAsCrew: penalty 없음(isChatBanned=false) → messagePublisher.publish(CHAT_MESSAGE_SENT, payload) 1회,
//   payload.crew.crewId == 전달 crewId, partyroomId 일치, content 일치.
// - sendMessageAsCrew: chat banned(isChatBanned=true) → publish 0회(무발행).
```
mock: `ChatPenaltyCachePort`, `RedisMessagePublisher`, `Clock`(고정).

- [ ] **Step 2: 실패 확인.**

- [ ] **Step 3: 구현**
`ChatMessageDto.ofCrew`:
```java
public static ChatMessageDto ofCrew(PartyroomId partyroomId, long crewId, String content, long timestamp) {
    return new ChatMessageDto(
        partyroomId, MessageTopic.CHAT_MESSAGE_SENT, UUID.randomUUID().toString(),
        timestamp, new CrewInfo(crewId), new ChatContent(timestamp + ":" + crewId, content));
}
```
`PartyroomChatCommandService.sendMessageAsCrew`:
```java
public void sendMessageAsCrew(PartyroomId partyroomId, long crewId, String content) {
    if (isPossibleChat(crewId)) {
        ChatMessageDto payload = ChatMessageDto.ofCrew(partyroomId, crewId, content, clock.millis());
        messagePublisher.publish(MessageTopic.CHAT_MESSAGE_SENT.topic(), payload);
    }
}
```
- [ ] **Step 4: 통과. Step 5: 커밋** `feat(p3a): sendMessageAsCrew 가드 오버로드(봇 채팅 송신 시임)`

### Task 3.2: ArchUnit 규칙 — 채팅 송신은 party 서비스로만

- [ ] **Step 1**: `VirtualDjArchitectureTest`에 규칙 추가 — virtualdj 패키지는 `RedisMessagePublisher`(EndingWith "MessagePublisher") 의존 금지(규칙 4 이미 커버)이며, 채팅 송신은 `PartyroomChatCommandService` 경유만 허용됨을 문서화하는 주석 + (선택) `PartyroomChatCommandService`만 허용 의존으로 명시하는 규칙. 최소: 기존 규칙 4가 이미 보장하므로 **새 테스트는 BotChatTrigger/LlmChatTaskRunner가 생긴 Chunk 4·5에서 실효 검증**. 여기선 규칙 4가 통과하는지 재확인만.
- [ ] **Step 2: 커밋**(주석/문서 변경 시).

### Task 3.3: 설정 키 + kill switch + 읽기 래퍼 — TDD

- [ ] **Step 1**: `ConfigKey.java`에 상수 추가(기존 `VIRTUALDJ_*` 패턴, 정규식 `^[a-z0-9_]+(\.[a-z0-9_]+)*$` 준수):
```java
public static final ConfigKey VDJ_CHAT_ENABLED = new ConfigKey("vdj.chat.enabled");
public static final ConfigKey VDJ_CHAT_TRIGGER_PROBABILITY = new ConfigKey("vdj.chat.trigger.probability");
public static final ConfigKey VDJ_CHAT_ROOM_COOLDOWN_SECONDS = new ConfigKey("vdj.chat.room.cooldown.seconds");
public static final ConfigKey VDJ_CHAT_ROOM_MAX_INFLIGHT = new ConfigKey("vdj.chat.room.max.inflight");
public static final ConfigKey VDJ_CHAT_CONTEXT_SIZE = new ConfigKey("vdj.chat.context.size");
public static final ConfigKey VDJ_CHAT_OUTPUT_MAX_TOKENS = new ConfigKey("vdj.chat.output.max.tokens");
```
> probability는 정수 퍼센트로 저장 권장(`SystemConfigCache.readInt`만 있음 — double 없음). 키를 `vdj.chat.trigger.probability.percent` 정수(0~100)로 두거나, readDouble 추가. **정수 퍼센트(0~100) 채택**(캐시 변경 최소).

- [ ] **Step 2: 실패 테스트** `VirtualDjChatConfigTest.java`:
```java
// - enabled 기본값(키 없음) → fail-open true
// - probabilityPercent 기본 12, cooldownSeconds 기본 30, maxInflight 기본 1, contextSize 기본 20, outputMaxTokens 기본 (예 256)
// - SystemConfigCache mock으로 readInt/readBoolean 반환 시 그 값 노출
```
- [ ] **Step 3: 구현** `VirtualDjChatConfig.java` (SystemConfigCache 래핑, P2 `getDjGraceSeconds` 패턴):
```java
@Component
@RequiredArgsConstructor
public class VirtualDjChatConfig {
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_PROBABILITY_PERCENT = 12;
    private static final int DEFAULT_COOLDOWN_SECONDS = 30;
    private static final int DEFAULT_MAX_INFLIGHT = 1;
    private static final int DEFAULT_CONTEXT_SIZE = 20;
    private static final int DEFAULT_OUTPUT_MAX_TOKENS = 256;
    private final SystemConfigCache cache;

    public boolean isEnabled() { return cache.readBoolean(ConfigKey.VDJ_CHAT_ENABLED, DEFAULT_ENABLED); }
    public int probabilityPercent() { return cache.readInt(ConfigKey.VDJ_CHAT_TRIGGER_PROBABILITY, DEFAULT_PROBABILITY_PERCENT); }
    public int cooldownSeconds() { return cache.readInt(ConfigKey.VDJ_CHAT_ROOM_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS); }
    public int maxInflight() { return cache.readInt(ConfigKey.VDJ_CHAT_ROOM_MAX_INFLIGHT, DEFAULT_MAX_INFLIGHT); }
    public int contextSize() { return cache.readInt(ConfigKey.VDJ_CHAT_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE); }
    public int outputMaxTokens() { return cache.readInt(ConfigKey.VDJ_CHAT_OUTPUT_MAX_TOKENS, DEFAULT_OUTPUT_MAX_TOKENS); }
}
```
> `SystemConfigCache.readBoolean`/`readInt` 시그니처(ConfigKey 인자 형태)를 실제로 확인해 일치시킬 것.

- [ ] **Step 4: 마이그레이션 V27 시드**:
```sql
INSERT INTO system_config (config_key, config_value, description) VALUES
 ('vdj.chat.enabled', 'true', 'P3 봇 채팅 전역 kill switch'),
 ('vdj.chat.trigger.probability', '12', '사람 메시지당 봇 응답 시도 확률(%)'),
 ('vdj.chat.room.cooldown.seconds', '30', '방별 봇 응답 최소 간격(초)'),
 ('vdj.chat.room.max.inflight', '1', '방당 동시 진행 LLM 응답 수'),
 ('vdj.chat.context.size', '20', 'LLM 주입 최근 사람 메시지 수'),
 ('vdj.chat.output.max.tokens', '256', '봇 응답 최대 토큰');
```
> system_config 컬럼명(`description` 존재)은 `V16__add_presence.sql` 시드 형태 확인 후 일치.

- [ ] **Step 5: 통과 + local validate(V27). Step 6: 커밋.**

### Chunk 3 완료 게이트
- [ ] `:app:test` GREEN(+ ArchUnit GREEN) + local validate(V25~V27).
- [ ] @superpowers:requesting-code-review.

---

## Chunk 4: 채팅 관찰 파이프라인 (구독 → 버퍼 → 게이트)

사람 메시지 구독 → 방별 맥락 버퍼 → 루프가드/확률/쿨다운/in-flight 락 게이트. **LLM 호출은 포트(`BotChatDispatcher`)로 분리하고 Chunk 4에선 mock/no-op으로 테스트**(Chunk 5가 실제 구현).

**File Structure:**
- Create: `.../virtualdj/application/service/BotIdentityResolver.java` (crewId→is_dummy 판별, 캐시)
- Create: `.../virtualdj/application/port/RoomContextReader.java` + impl (title/intro/now-playing)
- Create: `.../virtualdj/application/service/ChatContextBuffer.java` (Redis capped list)
- Create: `.../virtualdj/application/port/BotChatDispatcher.java` (포트 — Chunk 5 구현)
- Create: `.../virtualdj/adapter/in/listener/BotChatTrigger.java` (구독자 + 게이트)
- Modify: `.../party/adapter/in/listener/config/RedisListenerConfig.java` (BotChatTrigger 등록)
- Test: 각 단위 테스트 + 트리거 게이트 테스트

### Task 4.1: BotIdentityResolver (crewId→봇 판별) — TDD

- [ ] **Step 1: 실패 테스트** — `isBotCrew(crewId)`: crew 조회 → userId → is_dummy. 봇이면 true. 캐시(짧은 TTL 또는 Caffeine, 없으면 무캐시) — **설계 §11.6: 우선 무캐시로 단순 구현**, 부하 시 캐시 추가. 비봇/없는 crew → false.
- [ ] **Step 2~4**: 구현(`CrewRepository.findById(crewId)` → `crew.getUserId()` → `BotPoolQueryRepository.filterBotUserIds(List.of(userId))` 비어있지 않으면 봇) + 통과 + 커밋.
> crewId로 crew 단건 조회 메서드 존재 여부 확인(`CrewRepository.findById` 또는 추가).

### Task 4.2: RoomContextReader — TDD

- [ ] **Step 1: 실패 테스트** — `read(partyroomId)`: title/introduction + now-playing 곡명(activated면). 반환 record `RoomContext(title, introduction, nowPlayingTitle/*nullable*/)`.
- [ ] **Step 2~4**: 구현 — `PartyroomQueryService.getPartyroomById` → title/intro; `aggregatePort.findPlaybackState`(또는 query service 경유) → activated면 `playbackQueryService.getPlaybackById(currentPlaybackId).getName()`.
  > ⚠️ ArchUnit: RoomContextReader가 virtualdj 패키지면 `*AggregatePort` 직접 의존 금지(규칙 5). → **party의 query service(`PartyroomQueryService`/`PlaybackQueryService`)를 경유**하거나, party BC에 read 메서드를 추가해 호출. aggregatePort 직접 주입 금지. 통과 경로를 plan 실행 시 확정.
- [ ] **Step 5: 커밋.**

### Task 4.3: ChatContextBuffer (Redis capped list) — TDD

- [ ] **Step 1: 실패 테스트**(embedded redis 또는 RedisTemplate mock) — `append(partyroomId, message)` 후 `recent(partyroomId, n)`가 최근 n개(오래된 것 trim) 반환. TTL 세팅. 키 `vdj:chat:ctx:{partyroomId}`.
- [ ] **Step 2~4**: 구현 — `redisTemplate.opsForList().rightPush` + `trim(key, -n, -1)` + `expire`. 통과 + 커밋.

### Task 4.4: BotChatDispatcher 포트 + BotChatTrigger 게이트 — TDD

- [ ] **Step 1**: 포트 인터페이스 `BotChatDispatcher { void dispatch(PartyroomId partyroomId, long botCrewId, long botUserId); }` (Chunk 5 구현). Chunk 4 테스트는 mock.

- [ ] **Step 2: 실패 테스트** `BotChatTriggerTest.java` — 핵심 게이트 로직(메시지 수신 핸들러를 직접 호출하는 단위 테스트, Redis 리스너 역직렬화는 분리):
```java
// 입력: ChatMessageDto(또는 역직렬화된 형태) + 협력자 mock(BotIdentityResolver, ChatContextBuffer, VirtualDjChatConfig, RedisLockService, RandomProvider, persona봇 조회, BotChatDispatcher)
// - kill switch OFF(config.isEnabled=false) → 아무것도 안 함(버퍼 append도 X 또는 append만? → 설계: 전면중단, dispatch 0)
// - 봇 메시지(isBotCrew=true) → 버퍼 append X + 트리거 X (루프가드 핵심)
// - 사람 메시지 → 버퍼 append O
// - 사람 메시지 + 확률 실패(random ≥ p) → dispatch 0
// - 사람 메시지 + 확률 성공 + 쿨다운/락 미획득 → dispatch 0
// - 사람 메시지 + 확률 성공 + persona봇 0명 → dispatch 0
// - 사람 메시지 + 확률 성공 + 락 획득 + persona봇 ≥1 → dispatch 1 (선택봇 crewId/userId 전달)
```
- [ ] **Step 3: 구현** `BotChatTrigger`:
```java
public void onChatMessage(ChatMessageDto msg) {
    if (!config.isEnabled()) return;
    long crewId = msg.crew().crewId();
    if (identityResolver.isBotCrew(crewId)) return;            // 루프가드: 봇 발화 무시
    buffer.append(msg.partyroomId(), extractContent(msg));     // 사람 메시지만 버퍼
    if (!rollProbability(config.probabilityPercent())) return; // 확률
    // persona 보유 + 그 방 활성 봇 후보 조회
    List<BotCandidate> bots = personaBotQuery.findActivePersonaBotsInRoom(msg.partyroomId());
    if (bots.isEmpty()) return;
    // in-flight 락(= 쿨다운 겸용 또는 별도). 게이트 동기 구간에서 획득.
    String lockKey = "vdj:chat:inflight:" + msg.partyroomId().getId();
    String token = UUID.randomUUID().toString();
    if (!redisLockService.acquireLock(lockKey, token, inflightTtlSeconds(), TimeUnit.SECONDS)) return;
    // 쿨다운 별도 키(선택): vdj:chat:cooldown:{id} SETNX cooldownSeconds — 통과 못하면 락 해제 후 return
    BotCandidate chosen = pick(bots);
    dispatcher.dispatch(msg.partyroomId(), chosen.crewId(), chosen.userId(), lockKey, token); // 락 토큰 전달 → Chunk5가 finally 해제
}
```
> **락 해제 책임**: dispatch가 비동기이므로 락 토큰을 dispatcher에 넘겨 **Chunk 5 워커가 작업 종료(성공/실패/타임아웃) finally에서 해제**. 동기 게이트에서 못 dispatch한 경우(확률 실패 등)는 락을 애초에 안 잡았거나, 잡았다면 즉시 해제. → 포트 시그니처에 lockKey/token 포함하도록 §Step1 수정.
> **쿨다운 vs in-flight**: in-flight 락(작업 중 1건)과 쿨다운(작업 후 N초 간격)은 다른 목적. MVP는 **in-flight 락 + 별도 쿨다운 SETNX 키** 둘 다. 쿨다운 키는 dispatch 성공 시 워커가 설정(또는 게이트에서 설정).
- [ ] **Step 4: 통과.**

- [ ] **Step 5: persona 봇 in-room 조회** — `findActivePersonaBotsInRoom(partyroomId)`: bot_persona_assignment ∩ 그 방 활성 crew(is_dummy) → (botUserId, crewId, personaId). QueryDSL로 `BotPoolQueryRepository` 또는 신규 쿼리. 단위/통합 테스트.
- [ ] **Step 6: 커밋.**

### Task 4.5: Redis 리스너 등록

- [ ] **Step 1**: `RedisListenerConfig.redisContainer`에 BotChatTrigger를 `chat_message_sent` 토픽 리스너로 등록(역직렬화 어댑터는 기존 `GroupBroadcastTopicListener` 스타일 참조 — `ChatMessageDto`로 역직렬화 후 `onChatMessage` 호출하는 얇은 MessageListener 작성).
```java
container.addMessageListener(
    new ChatMessageTopicListener(objectMapper, botChatTrigger),  // 신규 얇은 리스너
    new ChannelTopic(MessageTopic.CHAT_MESSAGE_SENT.topic()));
```
> ⚠️ BotChatTrigger/리스너는 virtualdj 패키지 → ArchUnit 규칙 B 준수(MessagePublisher/AggregatePort 직접 의존 금지). 협력자는 모두 service/port 경유.
> ⚠️ config 클래스는 party 패키지에 있음 — 거기서 virtualdj BotChatTrigger를 주입받아 등록(생성은 ArchUnit 가드 대상 아님).
- [ ] **Step 2**: 통합 테스트(가능하면) — 채팅 발행 → 리스너 수신 → trigger 호출. 또는 수동 e2e로 위임(최종 게이트).
- [ ] **Step 3: 커밋.**

### Chunk 4 완료 게이트
- [ ] `:app:test` GREEN(트리거 게이트·루프가드 단위 GREEN) + ArchUnit GREEN.
- [ ] @superpowers:requesting-code-review.

---

## Chunk 5: LLM 프로바이더 + 비동기 워커

`BotChatDispatcher` 실제 구현 = 전용 스레드풀에서 프롬프트 조립 → Claude 호출 → 송신 가드 → `sendMessageAsCrew`, finally 락 해제.

**File Structure:**
- Create: `.../virtualdj/application/port/LlmChatProvider.java` (포트)
- Create: `.../virtualdj/adapter/out/llm/AnthropicChatProvider.java` (구현)
- Create: `.../virtualdj/adapter/out/llm/AnthropicProperties.java` (@ConfigurationProperties 또는 @Value)
- Create: `.../common/config/VirtualDjChatAsyncConfig.java` (전용 ThreadPoolTaskExecutor 빈)
- Create: `.../virtualdj/application/service/LlmChatTaskRunner.java` (BotChatDispatcher 구현)
- Create: `.../virtualdj/application/service/ChatPromptAssembler.java` (프롬프트 조립)
- Modify: `app/build.gradle` (필요 시 — 직접 HTTP면 무변경)
- Modify: `app/src/main/resources/application.yml` (service-api.anthropic.*)
- Test: provider(mock 서버/WireMock), prompt assembler, task runner

### Task 5.1: LlmChatProvider 포트 + 프롬프트 조립 — TDD

- [ ] **Step 1**: 포트 `LlmChatProvider { String complete(String systemPrompt, List<String> recentUserMessages, int maxTokens); }`. 실패 시 빈/예외 — 구현이 결정.
- [ ] **Step 2: 실패 테스트** `ChatPromptAssemblerTest.java` — `assembleSystem(persona, roomContext)`:
```java
// - 고정 규칙(한국어/짧게/AI비공개/지시무시) + persona.instruction + 방맥락(title/intro/현재곡) 포함
// - introduction 비어있으면 그 줄 생략(빈 줄 안 들어감)
// - 현재곡 null이면 곡 줄 생략
```
- [ ] **Step 3: 구현** `ChatPromptAssembler` — system 문자열 조립(고정 규칙 상수 + persona + RoomContext). recentUserMessages는 버퍼에서.
- [ ] **Step 4: 통과 + 커밋.**

### Task 5.2: AnthropicChatProvider — TDD

> **@claude-api 스킬을 호출**해 Messages API 호출 형태(엔드포인트/헤더/바디/프롬프트 캐싱)를 정확히 확정한 뒤 구현할 것.

- [ ] **Step 1: 실패 테스트** — WireMock(또는 MockWebServer)로 Anthropic `/v1/messages` 스텁:
```java
// - 정상 200 응답(content[0].text) → 그 텍스트 반환
// - system 블록에 cache_control ephemeral 포함(프롬프트 캐싱)
// - 헤더 x-api-key, anthropic-version:2023-06-01 전송
// - 4xx/5xx/타임아웃 → 빈 문자열 반환(best-effort, 예외 삼킴) + 로그
```
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현** — 기존 `RestTemplate`(JdkClientHttpRequestFactory) 또는 신규 전용 `WebClient`로 POST. 워커가 이미 별도 스레드라 동기 RestTemplate로 충분. 바디:
```json
{ "model": "<claude-최신>", "max_tokens": <n>, "system": [{"type":"text","text":"<sys>","cache_control":{"type":"ephemeral"}}],
  "messages": [{"role":"user","content":"<recent chat joined>"}] }
```
설정 주입: `service-api.anthropic.api-key: ${ANTHROPIC_API_KEY}`, `model`, `base-uri`(기본 https://api.anthropic.com), `timeout`. application.yml(Pytube 패턴 답습).
- [ ] **Step 4: 통과 + 커밋.**

### Task 5.3: 전용 스레드풀 빈

- [ ] **Step 1**: `VirtualDjChatAsyncConfig` — `ThreadPoolTaskExecutor`(core 2, max 4, queue 50, CallerRunsPolicy 또는 AbortPolicy로 과부하 시 드롭, prefix `vdj-chat-`, graceful shutdown). `@Async` 안 씀(ArchUnit `@Async`↔`@TransactionalEventListener` 쌍 강제 회피) — 직접 `executor.submit`.
- [ ] **Step 2: 커밋.**

### Task 5.4: LlmChatTaskRunner (BotChatDispatcher 구현) — TDD

- [ ] **Step 1: 실패 테스트** `LlmChatTaskRunnerTest.java`(executor는 동기 실행 stub로 주입해 결정적):
```java
// - dispatch: 프롬프트 조립 → provider.complete → 송신직전 봇 활성 crew 재확인 → sendMessageAsCrew 호출
// - provider 빈/공백 반환 → sendMessageAsCrew 호출 0 (빈출력 드롭, 설계 §3.4.2)
// - 송신시점 봇이 비활성 crew → sendMessageAsCrew 호출 0 (이탈 드롭)
// - 정상/실패/예외 무관 finally에서 redisLockService.releaseLock(lockKey, token) 1회 (락 해제 보장)
// - 쿨다운 키 설정(성공 시) 확인
```
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현**:
```java
@Service
@RequiredArgsConstructor
public class LlmChatTaskRunner implements BotChatDispatcher {
    private final ThreadPoolTaskExecutor vdjChatExecutor; // VDJ_CHAT_EXECUTOR
    private final ChatPromptAssembler assembler;
    private final RoomContextReader roomContextReader;
    private final ChatContextBuffer buffer;
    private final PersonaQueryPort personaQuery;       // botUserId→persona.instruction
    private final LlmChatProvider provider;
    private final PartyroomChatCommandService chatCommandService; // sendMessageAsCrew
    private final CrewRepository crewRepository;       // 송신직전 활성 재확인
    private final RedisLockService redisLockService;
    private final VirtualDjChatConfig config;
    private final RedisTemplate<String,Object> redisTemplate; // 쿨다운 키

    @Override
    public void dispatch(PartyroomId partyroomId, long botCrewId, long botUserId, String lockKey, String lockToken) {
        vdjChatExecutor.submit(() -> {
            try {
                String instruction = personaQuery.instructionOf(botUserId);     // 없으면 return
                RoomContext ctx = roomContextReader.read(partyroomId);
                String system = assembler.assembleSystem(instruction, ctx);
                List<String> recent = buffer.recent(partyroomId, config.contextSize());
                String reply = provider.complete(system, recent, config.outputMaxTokens());
                if (reply == null || reply.isBlank()) return;                    // 빈출력 드롭
                if (!isStillActiveCrew(partyroomId, botUserId)) return;          // 이탈 드롭
                chatCommandService.sendMessageAsCrew(partyroomId, botCrewId, reply.trim());
                setCooldown(partyroomId);                                        // 쿨다운 키
            } catch (Exception e) {
                log.warn("vdj chat dispatch failed: {}", e.getMessage());
            } finally {
                redisLockService.releaseLock(lockKey, lockToken);               // 락 해제 보장
            }
        });
    }
}
```
> `isStillActiveCrew` = `crewRepository.findByPartyroomIdAndUserId(pid, UserId.of(botUserId)).filter(CrewData::isActive).isPresent()`.
> ⚠️ ArchUnit: `LlmChatTaskRunner`가 `CrewRepository`(EndingWith "Repository") 의존 → **규칙 B는 `*AggregatePort`/`*MessagePublisher`만 금지**(규칙 A는 Orchestrator 한정). LlmChatTaskRunner는 Orchestrator 아님 → Repository 의존 허용됨. 단 깔끔하게 query service 경유 가능하면 그게 나음. 실행 시 확인.
> ⚠️ `RedisTemplate` 의존은 MessagePublisher/AggregatePort 아님 → 허용. 단 쿨다운을 ChatContextBuffer류 포트로 감싸면 더 깔끔.
- [ ] **Step 4: 통과.**
- [ ] **Step 5: dispatcher 포트 시그니처(lockKey/token) Chunk 4와 정합** — BotChatTrigger가 동일 시그니처로 호출하는지 컴파일 확인.
- [ ] **Step 6: 커밋** `feat(p3a): LLM 채팅 워커(프롬프트 조립+Claude 호출+가드 송신+락해제)`.

### Chunk 5 완료 게이트
- [ ] `:app:test` GREEN + ArchUnit GREEN.
- [ ] `ANTHROPIC_API_KEY` 미설정 시 부팅/동작 영향 없음(키 없으면 provider가 빈문자열·로그만) 확인.
- [ ] @superpowers:requesting-code-review.

---

## Chunk 6: 어드민 프론트엔드 (페르소나 CRUD + 봇 매핑)

pfplay-admin. 경로: `C:\Users\Eisen\Desktop\Labs\[projects] pfplay\pfplay-admin`. 별도 git 레포(브랜치 분기 필요 — origin/develop 기준). **pfplay-admin은 GHA 없음**(`reference_pfplay_admin_no_gha`) — Cloudflare/Vercel native.

### Task 6.1: 페르소나 CRUD slice

> 템플릿 = `src/features/virtual-dj-song-packs/` 통째 복제.

- [ ] **Step 1**: `entities/virtual-dj/model/types.ts`에 `Persona { id; name; instruction; active }` + `PersonaListItem` 추가.
- [ ] **Step 2**: `features/virtual-dj-personas/` 생성:
  - `api/personas-api.ts` — `listPersonas/getPersona/createPersona/updatePersona/deletePersona` (`http<ApiCommonResponse<T>>` + `unwrap`).
  - `api/use-personas.ts`, `api/use-create-persona.ts`(+rename→update, delete) — TanStack Query, queryKey `["virtual-dj","personas"]`, mutation onSuccess invalidate + `mutationErrorToast`.
  - `model/persona-schema.ts` — zod(`name` max 64 필수, `instruction` max 4000 필수, `active` boolean).
  - `ui/personas-page-content.tsx`(목록+생성/수정/삭제 다이얼로그 타깃 state), `ui/personas-list.tsx`(Table), `ui/create-persona-dialog.tsx`·`ui/edit-persona-dialog.tsx`·`ui/delete-persona-dialog.tsx`(react-hook-form + zodResolver).
  - `index.ts`.
- [ ] **Step 3**: 라우팅 — `pages/virtual-dj-page.tsx`의 `resourceType` 스위치에 `case "personas"` + `App.tsx` 라우트 + `app/layout.tsx` nav에 "페르소나" 추가.
- [ ] **Step 4**: 빌드/타입/린트 — `yarn build`/`tsc`/lint GREEN. 커밋.

### Task 6.2: 봇 로스터 페르소나 매핑

> 템플릿 = `ui/bot-roster.tsx` + `ui/distribute-avatars-dialog.tsx`.

- [ ] **Step 1**: `entities/virtual-dj/model/types.ts`의 `BotRosterItem`에 `personaId/personaName` 추가.
- [ ] **Step 2**: `features/virtual-dj-pool/api/bots-api.ts`에 `assignPersona(botIds, personaId)`·`unassignPersona(botIds)` + `use-assign-persona.ts` 훅(invalidate `["virtual-dj","bots"]`).
- [ ] **Step 3**: `ui/bot-roster.tsx` — 행에 현재 페르소나 표시(personaName), 선택 시 sticky 툴바에 "페르소나 일괄 지정" 버튼 → 신규 `ui/assign-persona-dialog.tsx`(페르소나 select = `usePersonas`, distribute 다이얼로그 복제). 행별 "페르소나 변경"도 동일 다이얼로그 단건.
- [ ] **Step 4**: 빌드/타입/린트 GREEN. 커밋.

### Chunk 6 완료 게이트
- [ ] `yarn build` + `tsc` + lint GREEN.
- [ ] (선택) 로컬 admin dev 서버로 페르소나 CRUD + 봇 매핑 수동 확인(백엔드 로컬 연동).
- [ ] @superpowers:requesting-code-review.

---

## 최종 게이트 (dev 머지 전 — 필수)

`feedback_local_e2e_before_dev_merge` / `reference_local_docker_compose`:

- [ ] **로컬 풀스택 부팅** — docker-compose.local.yml + .env.local, `local` profile, MySQL validate(V25~V27 적용·엔티티 정합).
- [ ] **e2e 시나리오**:
  1. 어드민에서 페르소나 1개 생성 → 봇 N명에 일괄 매핑.
  2. 그 봇들이 상주하는 방에 사람 계정으로 채팅 송신(여러 번) → `vdj.chat.trigger.probability` 임시 100으로 올려 결정적 확인.
  3. 봇이 LLM 응답을 실제 채팅으로 송신하고 클라이언트로 브로드캐스트되는지 확인(`ANTHROPIC_API_KEY` 실키 또는 mock provider 프로파일).
  4. **루프가드**: 봇 발화가 추가 봇 응답을 유발하지 않음(연쇄 0).
  5. **동시성**: 같은 방 동시 사람 메시지 2건 → 응답 1건(in-flight 락).
  6. persona 없는 봇은 침묵.
  7. `vdj.chat.enabled=false`(kill switch) → 전면 중단.
- [ ] e2e에서 발견된 deploy-blocking 버그 fix 후 재실증(P1·P2 모두 e2e로만 잡힘).
- [ ] **dev 머지** = 사용자 게이트(자동 머지 금지). PR로 develop(platform) / origin-develop(admin).

## 승격
- prod 승격 보류 — P3 전체(P3-B 플레이리스트 자가갱신) 완료 후 `#283~#287 + P1 + P3` 묶음 일괄 release/main. (`project_virtual_dj_p3_entry`)

## 범위 밖 (P3-B 후속)
- 플레이리스트 자가갱신(반응/히스토리 기반). 방 컨셉 저장(title/introduction)은 본 plan에서 확정되어 P3-B가 재사용.
