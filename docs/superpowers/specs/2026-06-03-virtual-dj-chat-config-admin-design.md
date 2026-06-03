# 가상 DJ 채팅/자가갱신 설정 어드민 패널 — 설계 문서

- 작성일: 2026-06-03
- 상태: 설계 합의 완료 (구현 전)
- 범위: P3-A(채팅) 운영 보조 — `vdj.chat.*` 런타임 설정을 SQL 없이 어드민에서 토글/튜닝 + P3-B 자가갱신 선행 게이트 토글
- 대상 레포: `pfplay-platform`(백엔드), `pfplay-admin`(어드민 UI)
- 관계: P3-A([[2026-06-02-virtual-dj-p3-chat-design]])의 연장. 같은 feature 브랜치(backend `feature/virtual-dj-p3-chat` / admin `feature/virtual-dj-p3-personas`)에 얹어 P3-A 묶음으로 승격.

---

## 0. 배경

P3-A는 봇 채팅의 런타임 설정 5키(`vdj.chat.enabled` kill switch + 확률/쿨다운/맥락수/max tokens)를 `system_config`(DB)에 두고 `SystemConfigCache`로 읽는다. 현재 이 값을 바꾸려면 **SQL `UPDATE`** 가 유일한 경로다(점검모드처럼). 운영 중 봇 채팅을 켜고/끄고, 확률·쿨다운을 튜닝하려면 어드민 콘솔에서 가능해야 한다.

추가로, **P3-B(플레이리스트 자가갱신)** 의 전역 master 토글을 **지금 선행 게이트로 깔아둔다**(값만 저장, 동작은 P3-B 구현 시). 사용자 명시 요청.

**행운**: `system_config` write 인프라가 코드 주석상 *"PR 6 admin endpoints"* 용으로 이미 예약돼 있다 — `SystemConfigData.create/updateValue` 팩토리·세터, `SystemConfigCache.invalidate()`(public, "PR 6's event listener" 주석), `AdminContext.currentAdministratorId()`. 이번 작업이 **첫 in-app system_config writer**이며 그 의도와 정확히 일치한다.

---

## 1. 결정 (잠금)

- **노출 범위**: 마스터 토글 2개(봇 채팅 / 자가갱신) + 채팅 튜너블 4개(확률 % / 쿨다운 s / 맥락수 / max tokens). (사용자 결정)
- **DB 단일 소스**(사용자 결정): 이 설정들은 `system_config`(DB)에만 존재한다. env/yaml에 없다. 해석은 **DB 행 → (행 없을 때만) 코드 상수 default**. env는 관여하지 않으며, 어드민(DB) 변경은 재배포로 덮어써지지 않는다(=항상 DB 권위).
  - (대비: Anthropic 연결 `ANTHROPIC_API_KEY/MODEL/TIMEOUT`은 env 전용이며 이 패널 밖이다.)
- **자가갱신 토글 = 선행 게이트**: `vdj.playlist.self_update.enabled` 신규 키. 값만 저장, **현재 동작 없음**(P3-B 미구현). UI에 "P3-B 예정 — 현재 동작 없음" 힌트.
- **스코프 endpoint**: 고정 allowlist(위 6키)만 읽기/쓰기. **generic system_config CRUD 아님**(임의 키 쓰기 백도어 방지).
- **fail-closed**: 두 토글 모두 기본 false(P3-A에서 `vdj.chat.enabled` 이미 false 시드/코드 default false). 자가갱신도 false 시드.

---

## 2. 백엔드

### 2.1 신규 ConfigKey
`operations/domain/value/ConfigKey.java`에 상수 1개 추가(기존 vdj.chat.* 5개는 이미 존재):
```java
public static final ConfigKey VDJ_PLAYLIST_SELF_UPDATE_ENABLED = new ConfigKey("vdj.playlist.self_update.enabled");
```
(키 정규식 `^[a-z0-9_]+(\.[a-z0-9_]+)*$` 준수 — `self_update` 언더스코어 OK.)

### 2.2 마이그레이션 V28 (신규 키 시드만)
`V28__seed_virtual_dj_self_update_config.sql` — 자가갱신 키 1행 시드(fail-closed):
```sql
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.playlist.self_update.enabled', 'false',
     'P3-B 봇 플레이리스트 자가갱신 전역 토글(기본 잠금 — 구현 후 활성화)');
```
(vdj.chat.* 5키는 V27이 이미 시드 → 추가 시드 불필요. 어드민 write는 기존 행 UPDATE.)

### 2.3 어드민 서비스 `VirtualDjChatConfigAdminService`
`virtualdj/application/service/`. allowlist 6키만 read/validated-upsert.

- **read** `ChatConfig read()`: **`SystemConfigRepository.findByConfigKey`로 각 키 직접 조회**(캐시 staleness 회피 — 캐시 스냅샷은 게터 경로 전용이라 편집기엔 ground-truth가 정직). 행 없으면 코드 default(enabled=false / selfUpdate=false / prob=12 / cooldown=30 / context=20 / maxTokens=256) 폴백. boolean은 `"true"/"false"` 파싱, 정수는 `Integer.parseInt`(실패 시 default).
- **update** `@Transactional void update(UpdateCommand cmd, Long adminId)`:
  1. 범위 검증(§2.5) — 실패 시 `VirtualDjException`(BAD_REQUEST), **부분 저장 없음**(트랜잭션 롤백).
  2. 각 키: `findByConfigKey` → 존재하면 `updateValue(newValue, adminId)` / 없으면 `create(key, newValue, desc, adminId)` → `save`. (V27/V28 시드로 사실상 항상 존재.)
  3. 커밋 후 캐시 무효화: `ApplicationEventPublisher`로 `VirtualDjChatConfigChangedEvent` 발행.
- 협력자: `SystemConfigRepository`, `ApplicationEventPublisher`. (boolean/int↔String 직렬화는 이 서비스 내부.)

### 2.4 캐시 무효화 리스너
`SystemConfigCache.invalidate()` 주석의 설계 의도(*"PR 6's event listener"*)대로:
```java
@TransactionalEventListener(phase = AFTER_COMMIT)
void onChanged(VirtualDjChatConfigChangedEvent e) { systemConfigCache.invalidate(); }
```
- 커밋 후 로컬 스냅샷 비움 → 다음 read에서 즉시 재조회. **멀티 인스턴스는 best-effort**(다른 인스턴스는 30s TTL 내 수렴 — 기존 SystemConfigCache 정책과 동일, 신규 위험 아님).
- ⚠️ ArchUnit: 이 서비스/리스너는 virtualdj 패키지지만 `*MessagePublisher`/`*AggregatePort` 의존 없음(operations의 repo/cache + Spring 이벤트만) → 규칙 통과.

### 2.5 검증 규칙
- `chatEnabled`, `selfUpdateEnabled`: boolean.
- `probabilityPercent`: 정수 0–100.
- `cooldownSeconds`, `contextSize`, `outputMaxTokens`: 정수 ≥1.
- (참고: `vdj.chat.room.cooldown.seconds`는 게이트 SETLNX 키 TTL 겸용이라 Anthropic 타임아웃(12s)보다 큰 게 권장이나, 하드 차단은 안 함 — 운영 재량. UI 힌트로만.)

### 2.6 컨트롤러 (AdminVirtualDjController 확장)
- `GET /api/v1/admin/virtual-dj/chat-config` → `ApiCommonResponse<ChatConfigResponse>`
- `PUT /api/v1/admin/virtual-dj/chat-config` (@Valid `UpdateChatConfigRequest`) → 204
- 둘 다 `@PreAuthorize("@adminAuth.canManageVirtualDj()")`(기존 일관). updatedBy는 서비스가 `AdminContext.currentAdministratorId()`로 채움(컨트롤러 아닌 서비스 inject, 기존 관례).
- payload:
```java
record ChatConfigResponse(boolean chatEnabled, boolean selfUpdateEnabled,
        int probabilityPercent, int cooldownSeconds, int contextSize, int outputMaxTokens) {}
record UpdateChatConfigRequest(boolean chatEnabled, boolean selfUpdateEnabled,
        @Min(0) @Max(100) int probabilityPercent, @Min(1) int cooldownSeconds,
        @Min(1) int contextSize, @Min(1) int outputMaxTokens) {}
```

---

## 3. 프론트엔드 (pfplay-admin)

신규 slice `features/virtual-dj-chat-config/`. 템플릿: 페르소나 slice(폼·다이얼로그·zod) + song-pack(목록 패턴) 답습.

- `api/chat-config-api.ts` — `getChatConfig()`, `updateChatConfig(payload)` (`http<ApiCommonResponse<T>>` + `unwrap`).
- `api/use-chat-config.ts`(useQuery, key `["virtual-dj","chat-config"]`), `api/use-update-chat-config.ts`(useMutation, onSuccess invalidate + 토스트, onError `mutationErrorToast`).
- `model/chat-config-schema.ts` — zod(probability 0–100, cooldown/context/maxTokens ≥1, 두 boolean). 백엔드 검증과 동기.
- `ui/chat-config-page-content.tsx` — react-hook-form, 폼 로드시 `useChatConfig`로 prefill. 구성:
  - 봇 채팅 사용: 토글(Checkbox — 페르소나 slice 선례, Switch 없음)
  - **자가갱신 모드: 토글 + "P3-B 예정 — 현재 동작 없음" 보조 라벨**(켜기 가능, 정직한 힌트)
  - 응답 확률(%) / 쿨다운(초) / 맥락 수 / max tokens: number input
  - 저장 버튼(mutation.isPending disable)
- 라우팅: `pages/virtual-dj-page.tsx` resourceType 스위치 `case "chat-config"`; `app/layout.tsx` nav "채팅 설정" 추가; 필요시 `App.tsx`.
- CSRF는 http wrapper가 자동 처리(수동 금지).

---

## 4. 컴포넌트 경계

| 단위 | 위치 | 책임 |
|---|---|---|
| `VirtualDjChatConfigAdminService` | virtualdj application | allowlist 6키 read(repo direct) + 검증 upsert + 변경 이벤트 |
| `VirtualDjChatConfigChangedEvent` + AFTER_COMMIT 리스너 | virtualdj | 커밋 후 `SystemConfigCache.invalidate()` |
| chat-config 엔드포인트 + DTO | AdminVirtualDjController | 인증·검증·봉투 |
| chat-config slice | pfplay-admin | 폼 read/update UI |

기존 재사용(신규 작성 불필요): `SystemConfigData.create/updateValue`, `SystemConfigRepository`, `SystemConfigCache.invalidate/readX`, vdj.chat.* 5 ConfigKey, `AdminContext.currentAdministratorId`.

---

## 5. 테스트

- **백엔드 단위**(`VirtualDjChatConfigAdminServiceTest`, repo/publisher mock):
  - read: 행 존재 → 그 값 / 행 없음 → default 폴백 / 잘못된 값 → default.
  - update: 범위 위반(확률 101, 쿨다운 0 등) → 예외 + save 0(부분저장 없음). 정상 → 6키 각 find→updateValue/create→save + 이벤트 1회 발행.
- **컨트롤러 슬라이스**(AdminVirtualDjControllerTest 확장): GET 200(현재값), PUT 204(서비스 호출), PUT 검증 실패 400, member 403. `@MockBean VirtualDjChatConfigAdminService` 추가(@WebMvcTest 컨텍스트 로드).
- **프론트**: chat-config-api 테스트 + zod 스키마 경계.
- **로컬 e2e**(최종 게이트): 어드민에서 봇 채팅 ON + 확률 변경 저장 → `system_config` 행 갱신 확인 + 캐시 즉시 반영(invalidate 후 read 새 값) + V28 자가갱신 행 존재. validate 부팅(V28). dev 머지 전 필수.

---

## 6. 범위 밖
- P3-B 자가갱신 실제 동작(토글은 값만 저장).
- generic system_config 어드민 CRUD(allowlist만).
- 멀티 인스턴스 분산 캐시 무효화(기존 30s TTL 정책 유지, best-effort).
- env 계층(DB 단일 소스 결정).

## 7. 승격
P3-A 묶음에 포함되어 함께 dev/stg 축적 → P3(P3-B 포함) 완료 후 일괄 release/main. dev 머지 = 사용자 게이트.
