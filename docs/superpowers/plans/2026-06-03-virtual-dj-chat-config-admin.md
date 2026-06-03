# 가상 DJ 채팅/자가갱신 설정 어드민 패널 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민 콘솔에서 `vdj.chat.*` 런타임 설정(봇 채팅 토글 + 확률/쿨다운/맥락수/max tokens)을 SQL 없이 읽고 수정하고, P3-B 자가갱신 선행 게이트 토글을 추가한다.

**Architecture:** `system_config`(DB) 단일 소스. 고정 allowlist 6키만 다루는 스코프 어드민 endpoint(GET/PUT). 쓰기는 `@Transactional` 서비스가 검증→`SystemConfigData.updateValue/create`→`save`→변경 이벤트 발행, AFTER_COMMIT 리스너가 `SystemConfigCache.invalidate()`로 즉시 반영. 프론트는 폼 slice(toggle 2 + number 4).

**Tech Stack:** Java 21 / Spring Boot 3.2.3 / JPA(MySQL) / Flyway / Spring Events / React(Vite, FSD) + TanStack Query + zod.

**참조 spec:** `docs/superpowers/specs/2026-06-03-virtual-dj-chat-config-admin-design.md`

---

## 사전 규칙 (공통)

- **TDD**: 실패 테스트 → 최소 구현 → 통과 → 커밋. @superpowers:test-driven-development.
- **빌드/테스트**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew ...` prefix 필수 ([[reference_pfplay_platform_jdk]]).
- **브랜치**: 백엔드(Chunk 1) = `feature/virtual-dj-p3-chat`(이미 체크아웃). 프론트(Chunk 2) = pfplay-admin `feature/virtual-dj-p3-personas`. **둘 다 P3-A 묶음의 기존 브랜치에 얹는다**(새 브랜치 X).
- **커밋 한글** ([[feedback_korean_issue_commit_pr]]), 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **확정 코드 사실**(재조사로 검증, 재확인 불필요):
  - `SystemConfigData`(`operations/domain/entity/data/`): `@Id String configKey`(assigned), 필드 `configValue`(TEXT), `description`, `updatedByAdministratorId`(Long), `updatedAt`(LocalDateTime, NOT NULL). **정적 팩토리** `create(String configKey, String configValue, String description, Long updatedByAdministratorId)`(updatedAt=now) + **세터** `updateValue(String newValue, Long updatedByAdministratorId)`(updatedAt=now). 둘 다 이미 존재("PR 6용 예약").
  - `SystemConfigRepository extends JpaRepository<SystemConfigData, String>` + `Optional<SystemConfigData> findByConfigKey(String)`. `save()`=upsert(PK assigned String).
  - `SystemConfigCache`(`operations/application/service/`): `public void invalidate()`(스냅샷 null), `public int readInt(ConfigKey,int)`, `public boolean readBoolean(ConfigKey,boolean)`. 30s TTL per-instance.
  - `ConfigKey`(`operations/domain/value/`): `record ConfigKey(String value)`, 키 정규식 `^[a-z0-9_]+(\.[a-z0-9_]+)*$` max64, `ConfigKey.of(String)`. 기존 `VDJ_CHAT_ENABLED/VDJ_CHAT_TRIGGER_PROBABILITY/VDJ_CHAT_ROOM_COOLDOWN_SECONDS/VDJ_CHAT_CONTEXT_SIZE/VDJ_CHAT_OUTPUT_MAX_TOKENS` 5개 존재.
  - `AdminContext`(`administration/application/`): `@Component`, `Long currentAdministratorId()`(SecurityContext→administrator_id, 미인증 시 IllegalStateException). **서비스 필드 inject 관례**(예: `AdminMemberTierCommandService`).
  - 최신 마이그레이션 V27 → 신규 V28.
  - 코드 default: enabled=false / selfUpdate=false / probability=12 / cooldown=30 / context=20 / maxTokens=256.
- **dev 머지 전 로컬 docker-compose 풀스택 e2e 필수** ([[feedback_local_e2e_before_dev_merge]]). 마이그레이션 추가 시 validate 부팅 게이트.

---

## Chunk 1: 백엔드 (설정 read/update 서비스 + 엔드포인트)

**File Structure:**
- Modify: `app/src/main/java/com/pfplaybackend/api/operations/domain/value/ConfigKey.java` (신규 키 상수)
- Create: `app/src/main/resources/db/migration/V28__seed_virtual_dj_self_update_config.sql`
- Create: `app/.../virtualdj/application/dto/ChatConfigView.java` (서비스 read 결과 record)
- Create: `app/.../virtualdj/application/event/VirtualDjChatConfigChangedEvent.java`
- Create: `app/.../virtualdj/application/service/VirtualDjChatConfigAdminService.java`
- Create: `app/.../virtualdj/application/service/VirtualDjChatConfigCacheInvalidator.java` (AFTER_COMMIT 리스너)
- Create: `app/.../virtualdj/adapter/in/web/payload/ChatConfigResponse.java`
- Create: `app/.../virtualdj/adapter/in/web/payload/UpdateChatConfigRequest.java`
- Modify: `app/.../virtualdj/adapter/in/web/AdminVirtualDjController.java` (GET/PUT 엔드포인트)
- Test: `app/src/test/java/.../virtualdj/application/service/VirtualDjChatConfigAdminServiceTest.java`
- Test: `app/src/test/java/.../virtualdj/AdminVirtualDjControllerTest.java` (확장)

### Task 1.1: 신규 ConfigKey + V28 시드

- [ ] **Step 1**: `ConfigKey.java`의 기존 vdj.chat.* 상수 블록(라인 ~48-52) 바로 아래에 추가:
```java
public static final ConfigKey VDJ_PLAYLIST_SELF_UPDATE_ENABLED = new ConfigKey("vdj.playlist.self_update.enabled");
```
- [ ] **Step 2**: `V28__seed_virtual_dj_self_update_config.sql` 생성 (V27 INSERT 스타일 답습 — system_config 컬럼 `config_key, config_value, description`):
```sql
-- P3-B 봇 플레이리스트 자가갱신 전역 토글의 선행 게이트(기본 잠금).
-- 값만 저장하며 동작은 P3-B 구현 시. 활성화: 어드민 패널 또는
-- UPDATE system_config SET config_value='true' WHERE config_key='vdj.playlist.self_update.enabled';
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.playlist.self_update.enabled', 'false',
     'P3-B 봇 플레이리스트 자가갱신 전역 토글(기본 잠금 — 구현 후 활성화)');
```
- [ ] **Step 3**: 컴파일 확인 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava -q`.
- [ ] **Step 4: 커밋** `git commit -am "feat(p3a): vdj.playlist.self_update.enabled 선행 게이트 키 + V28 시드"`

### Task 1.2: ChatConfigView record + 변경 이벤트

- [ ] **Step 1**: `ChatConfigView.java` (서비스가 read로 반환, 컨트롤러가 응답 DTO로 매핑):
```java
package com.pfplaybackend.api.virtualdj.application.dto;

public record ChatConfigView(
        boolean chatEnabled, boolean selfUpdateEnabled,
        int probabilityPercent, int cooldownSeconds, int contextSize, int outputMaxTokens) {}
```
- [ ] **Step 2**: `VirtualDjChatConfigChangedEvent.java` (빈 마커 — AFTER_COMMIT 트리거용):
```java
package com.pfplaybackend.api.virtualdj.application.event;

public record VirtualDjChatConfigChangedEvent() {}
```
- [ ] **Step 3: 커밋** `git commit -am "feat(p3a): chat-config view/event 타입"`

### Task 1.3: VirtualDjChatConfigAdminService (read/update) — TDD

- [ ] **Step 1: 실패 테스트** `VirtualDjChatConfigAdminServiceTest.java` — mock `SystemConfigRepository`, `ApplicationEventPublisher`, `AdminContext`. 케이스:
```java
// read:
// - 모든 행 존재 → 그 값 반환 (boolean "true"/"false", 정수 파싱)
// - 일부 행 없음(findByConfigKey 빈 Optional) → 해당 키 코드 default 폴백
// - 잘못된 값("abc" 정수 자리, "yes" boolean 자리) → default 폴백 (SystemConfigCache 의미와 동일)
// update:
// - 정상: 6키 각각 (존재→updateValue / 없으면→create) 후 save, 그리고 eventPublisher.publishEvent(ChangedEvent) 1회
// - probability=101 → 예외(VirtualDjException BAD_REQUEST류), repository.save 0회 (부분저장 없음)
// - cooldown=0 → 예외, save 0회
// - contextSize=0 / outputMaxTokens=0 → 예외, save 0회
// - update가 updatedByAdministratorId 에 adminContext.currentAdministratorId() 값을 전달
```
boolean 파싱 단언: trim 후 `"true"`(ci)→true / `"false"`→false / 그 외→default.

- [ ] **Step 2: 실패 확인** `... --tests "*VirtualDjChatConfigAdminServiceTest"`.

- [ ] **Step 3: 구현** `VirtualDjChatConfigAdminService.java`:
```java
@Service
@RequiredArgsConstructor
public class VirtualDjChatConfigAdminService {

    private static final boolean DEF_CHAT_ENABLED = false;
    private static final boolean DEF_SELF_UPDATE = false;
    private static final int DEF_PROBABILITY = 12;
    private static final int DEF_COOLDOWN = 30;
    private static final int DEF_CONTEXT = 20;
    private static final int DEF_MAX_TOKENS = 256;

    private final SystemConfigRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminContext adminContext;

    @Transactional(readOnly = true)
    public ChatConfigView read() {
        return new ChatConfigView(
                readBool(ConfigKey.VDJ_CHAT_ENABLED, DEF_CHAT_ENABLED),
                readBool(ConfigKey.VDJ_PLAYLIST_SELF_UPDATE_ENABLED, DEF_SELF_UPDATE),
                readInt(ConfigKey.VDJ_CHAT_TRIGGER_PROBABILITY, DEF_PROBABILITY),
                readInt(ConfigKey.VDJ_CHAT_ROOM_COOLDOWN_SECONDS, DEF_COOLDOWN),
                readInt(ConfigKey.VDJ_CHAT_CONTEXT_SIZE, DEF_CONTEXT),
                readInt(ConfigKey.VDJ_CHAT_OUTPUT_MAX_TOKENS, DEF_MAX_TOKENS));
    }

    @Transactional
    public void update(boolean chatEnabled, boolean selfUpdateEnabled,
                       int probabilityPercent, int cooldownSeconds, int contextSize, int outputMaxTokens) {
        // 1) 검증(전부 통과해야 어떤 write 도 안 함)
        if (probabilityPercent < 0 || probabilityPercent > 100) throw badRequest("확률은 0~100 이어야 합니다.");
        if (cooldownSeconds < 1)  throw badRequest("쿨다운은 1초 이상이어야 합니다.");
        if (contextSize < 1)      throw badRequest("맥락 수는 1 이상이어야 합니다.");
        if (outputMaxTokens < 1)  throw badRequest("max tokens 는 1 이상이어야 합니다.");
        Long adminId = adminContext.currentAdministratorId();
        // 2) upsert
        put(ConfigKey.VDJ_CHAT_ENABLED, Boolean.toString(chatEnabled), "P3 봇 채팅 전역 kill switch", adminId);
        put(ConfigKey.VDJ_PLAYLIST_SELF_UPDATE_ENABLED, Boolean.toString(selfUpdateEnabled), "P3-B 자가갱신 토글", adminId);
        put(ConfigKey.VDJ_CHAT_TRIGGER_PROBABILITY, Integer.toString(probabilityPercent), "사람 메시지당 봇 응답 확률(%)", adminId);
        put(ConfigKey.VDJ_CHAT_ROOM_COOLDOWN_SECONDS, Integer.toString(cooldownSeconds), "방별 봇 응답 최소 간격(초)", adminId);
        put(ConfigKey.VDJ_CHAT_CONTEXT_SIZE, Integer.toString(contextSize), "LLM 주입 최근 메시지 수", adminId);
        put(ConfigKey.VDJ_CHAT_OUTPUT_MAX_TOKENS, Integer.toString(outputMaxTokens), "봇 응답 최대 토큰", adminId);
        // 3) 커밋 후 캐시 무효화 트리거
        eventPublisher.publishEvent(new VirtualDjChatConfigChangedEvent());
    }

    private void put(ConfigKey key, String value, String desc, Long adminId) {
        repository.findByConfigKey(key.value())
                .ifPresentOrElse(
                        row -> { row.updateValue(value, adminId); repository.save(row); },
                        () -> repository.save(SystemConfigData.create(key.value(), value, desc, adminId)));
    }

    private boolean readBool(ConfigKey key, boolean def) {
        var opt = repository.findByConfigKey(key.value());
        if (opt.isEmpty()) return def;
        String v = opt.get().getConfigValue();
        if (v == null) return def;
        v = v.trim();
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        return def;
    }
    private int readInt(ConfigKey key, int def) {
        return repository.findByConfigKey(key.value())
                .map(SystemConfigData::getConfigValue)
                .map(v -> { try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; } })
                .orElse(def);
    }
    private RuntimeException badRequest(String msg) {
        // ExceptionCreator.create(VirtualDjException.CHAT_CONFIG_INVALID) 패턴 사용.
        // VirtualDjException 에 CHAT_CONFIG_INVALID("VDJ-0NN", msg기본, ErrorType.BAD_REQUEST) 신규 추가(번호=현재 최대+1).
        return ExceptionCreator.create(VirtualDjException.CHAT_CONFIG_INVALID);
    }
}
```
> ⚠️ `SystemConfigData` 의 getter 명(`getConfigValue`) 실제 확인 후 일치. `VirtualDjException` 에 `CHAT_CONFIG_INVALID(BAD_REQUEST)` 코드 1개 추가(번호=현재 최대+1) — throw 형태는 송팩/페르소나 서비스(`ExceptionCreator.create(VirtualDjException.XXX)`)와 동일. (개별 필드 메시지를 다르게 주고 싶으면 코드 1개로 두고 검증 분기에서 로깅만; HTTP 400 동일.)

- [ ] **Step 4: 통과 확인** → GREEN.
- [ ] **Step 5: 커밋** `git commit -am "feat(p3a): VirtualDjChatConfigAdminService (read/검증 upsert/이벤트) + 단위테스트"`

### Task 1.4: 캐시 무효화 리스너 (AFTER_COMMIT)

- [ ] **Step 1**: `VirtualDjChatConfigCacheInvalidator.java`:
```java
@Component
@RequiredArgsConstructor
public class VirtualDjChatConfigCacheInvalidator {
    private final SystemConfigCache systemConfigCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(VirtualDjChatConfigChangedEvent event) {
        systemConfigCache.invalidate();
    }
}
```
- [ ] **Step 2**: (선택) 통합 스모크 — 굳이 단위테스트 불필요(스프링 와이어링). 컴파일 확인.
- [ ] **Step 3: 커밋** `git commit -am "feat(p3a): chat-config 변경 시 AFTER_COMMIT 캐시 무효화 리스너"`

### Task 1.5: 엔드포인트 + payload

- [ ] **Step 1**: payload records:
```java
public record ChatConfigResponse(boolean chatEnabled, boolean selfUpdateEnabled,
        int probabilityPercent, int cooldownSeconds, int contextSize, int outputMaxTokens) {
    public static ChatConfigResponse from(ChatConfigView v) {
        return new ChatConfigResponse(v.chatEnabled(), v.selfUpdateEnabled(),
                v.probabilityPercent(), v.cooldownSeconds(), v.contextSize(), v.outputMaxTokens());
    }
}
public record UpdateChatConfigRequest(boolean chatEnabled, boolean selfUpdateEnabled,
        @Min(0) @Max(100) int probabilityPercent, @Min(1) int cooldownSeconds,
        @Min(1) int contextSize, @Min(1) int outputMaxTokens) {}
```
- [ ] **Step 2**: `AdminVirtualDjController` 에 추가(기존 song-pack/persona 엔드포인트 스타일, `@PreAuthorize("@adminAuth.canManageVirtualDj()")`, ApiCommonResponse):
```java
@GetMapping("/virtual-dj/chat-config")
public ApiCommonResponse<ChatConfigResponse> getChatConfig() {
    return ApiCommonResponse.success(ChatConfigResponse.from(chatConfigAdminService.read()));
}

@PutMapping("/virtual-dj/chat-config")
public ResponseEntity<Void> updateChatConfig(@Valid @RequestBody UpdateChatConfigRequest req) {
    chatConfigAdminService.update(req.chatEnabled(), req.selfUpdateEnabled(),
            req.probabilityPercent(), req.cooldownSeconds(), req.contextSize(), req.outputMaxTokens());
    return ResponseEntity.noContent().build();
}
```
컨트롤러에 `VirtualDjChatConfigAdminService` 생성자 주입(@RequiredArgsConstructor).
- [ ] **Step 3**: `AdminVirtualDjControllerTest` 확장 — `@MockBean VirtualDjChatConfigAdminService` 추가(컨텍스트 로드). 슬라이스 테스트(기존 persona 엔드포인트 테스트 스타일):
```
// - GET /api/v1/admin/virtual-dj/chat-config → 200, body 필드(mock read() 반환)
// - PUT 정상 body → 204, verify service.update(...) 인자 일치
// - PUT 검증 위반(probability 101) → 400 (bean validation), service 미호출
// - member 403 / anonymous 401
```
- [ ] **Step 4: 통과 + 커밋** `git commit -am "feat(p3a): 채팅 설정 어드민 GET/PUT 엔드포인트"`

### Chunk 1 완료 게이트
- [ ] `:app:test --tests "*VirtualDjChatConfigAdminServiceTest" --tests "*AdminVirtualDjControllerTest"` GREEN, 이어서 **FULL** `:app:test -q` 0 failures(회귀 없음 — 특히 AdminVirtualDjControllerTest @WebMvcTest 컨텍스트가 새 @MockBean 으로 로드되는지).
- [ ] 로컬 local-profile validate 부팅(V28 적용) — 또는 최종 e2e 게이트에서 일괄.
- [ ] @superpowers:requesting-code-review.

---

## Chunk 2: 프론트엔드 (pfplay-admin chat-config slice)

pfplay-admin (`C:\Users\Eisen\Desktop\Labs\[projects] pfplay\pfplay-admin`, 브랜치 `feature/virtual-dj-p3-personas`). Vite + React + FSD + TanStack Query + zod.

**File Structure:**
- Create: `src/features/virtual-dj-chat-config/api/chat-config-api.ts`
- Create: `src/features/virtual-dj-chat-config/api/use-chat-config.ts`
- Create: `src/features/virtual-dj-chat-config/api/use-update-chat-config.ts`
- Create: `src/features/virtual-dj-chat-config/model/chat-config-schema.ts`
- Create: `src/features/virtual-dj-chat-config/ui/chat-config-page-content.tsx`
- Create: `src/features/virtual-dj-chat-config/index.ts`
- Modify: `src/pages/virtual-dj-page.tsx` (resourceType 스위치 case)
- Modify: `src/app/layout.tsx` (nav)
- Modify: `src/App.tsx` (필요 시 라우트)
- Test: `src/features/virtual-dj-chat-config/api/chat-config-api.test.ts`, `model/chat-config-schema.test.ts`

> 템플릿: `src/features/virtual-dj-personas/`(api/hooks/zod/ui) + `virtual-dj-song-packs`. 읽기 전 두 slice 정독.

### Task 2.1: API + 훅 + 스키마

- [ ] **Step 1**: `model/chat-config-schema.ts` — zod (백엔드 검증 동기): `chatEnabled` boolean, `selfUpdateEnabled` boolean, `probabilityPercent` int 0–100, `cooldownSeconds`/`contextSize`/`outputMaxTokens` int ≥1. `z.infer` 타입. 경계 테스트 `chat-config-schema.test.ts`.
- [ ] **Step 2**: `api/chat-config-api.ts` — `getChatConfig(): Promise<ChatConfig>` = GET `/api/v1/admin/virtual-dj/chat-config` (`http<ApiCommonResponse<T>>`+`unwrap`); `updateChatConfig(payload): Promise<void>` = PUT(`http<void>`). `chat-config-api.test.ts`(요청 경로/바디/unwrap, 에러 propagation) — persona-api 테스트 스타일.
- [ ] **Step 3**: `api/use-chat-config.ts`(useQuery, key `["virtual-dj","chat-config"]`), `api/use-update-chat-config.ts`(useMutation, onSuccess invalidate `["virtual-dj","chat-config"]` + 성공 토스트, onError `mutationErrorToast`).
- [ ] **Step 4: 빌드/테스트** `yarn build`(tsc+vite) + `yarn test:run` GREEN. 커밋.

### Task 2.2: 설정 폼 UI + 라우팅

- [ ] **Step 1**: `ui/chat-config-page-content.tsx` — `useChatConfig`로 prefill(react-hook-form + zodResolver), 로딩/에러 처리. 구성:
  - "봇 채팅 사용" 토글(Checkbox — 페르소나 slice 선례, Switch 없음)
  - "자가갱신 모드" 토글 + 보조 텍스트 **"P3-B 예정 — 현재 동작 없음"**(켜기 가능, 정직 힌트)
  - 응답 확률(%) / 방 쿨다운(초) / 맥락 메시지 수 / 응답 max tokens: number input (각 라벨 + 범위 힌트; 쿨다운엔 "LLM 타임아웃(12s)보다 크게 권장" 보조 힌트만)
  - 저장 버튼(`useUpdateChatConfig`, isPending disable)
- [ ] **Step 2**: 라우팅 — `pages/virtual-dj-page.tsx` resourceType 스위치 `case "chat-config"` → `<ChatConfigPageContent/>`; `app/layout.tsx` nav "채팅 설정"(아이콘 임의, 예 `Settings`/`MessageSquare`) `/virtual-dj/chat-config`; `App.tsx` 라우트는 기존 `/virtual-dj/:resourceType` 가 커버하면 불필요(persona 선례 확인).
- [ ] **Step 3**: `index.ts` barrel(`ChatConfigPageContent` export). `entities/virtual-dj` 타입에 `ChatConfig` 추가 필요하면 배럴 re-export.
- [ ] **Step 4: 빌드/테스트** `yarn build` + `yarn test:run` GREEN. 커밋.

### Chunk 2 완료 게이트
- [ ] `yarn build`(tsc) + `yarn test:run` GREEN.
- [ ] @superpowers:requesting-code-review.

---

## 최종 게이트 (dev 머지 전 — 필수)

[[feedback_local_e2e_before_dev_merge]] / [[reference_local_docker_compose]]:
- [ ] **로컬 풀스택 부팅** — V28 validate 적용 확인(엔티티 변경 없음 → drift 위험 낮으나 부팅 게이트 유지).
- [ ] **e2e 시나리오**:
  1. 어드민 로그인 → "채팅 설정" 진입 → 현재값 표시(GET, 기본 잠금 false + 기본 튜너블).
  2. 봇 채팅 ON + 확률 20 저장(PUT) → 200/204.
  3. DB 확인: `system_config` 의 `vdj.chat.enabled='true'`, `vdj.chat.trigger.probability='20'` 갱신.
  4. **캐시 즉시 반영**: 저장 직후(30s 안 기다림) GET 재조회 또는 러닝 피처가 새 값 사용(invalidate 동작). 자가갱신 토글 저장 → `vdj.playlist.self_update.enabled` 행 갱신(동작은 없음).
  5. 검증 위반(확률 150) 저장 시도 → 400, DB 무변경(부분저장 없음).
  6. V28 `vdj.playlist.self_update.enabled='false'` 시드 확인.
- [ ] ⚠️ V28 시드 수정 없음(신규 행) → 기존 V27 적용 DB와 충돌 없음. 단 **로컬 docker DB는 V28 새로 적용 위해 부팅만 하면 됨**(볼륨 리셋 불필요 — V28은 신규 추가라 체크섬 충돌 없음).
- [ ] **dev 머지 = 사용자 게이트**.

## 승격
P3-A 묶음에 포함 — P3(P3-B 포함) 완료 후 일괄 release/main.
