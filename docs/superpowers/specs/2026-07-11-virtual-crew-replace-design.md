# 가상 크루 재배치(replace) — 설계 (platform #327 / admin #26)

## 배경 (운영 함정)

가상 크루 봇 모델(2026-07-10 머지, spec `2026-07-08-virtual-dj-bot-model-overhaul-design.md`)은:

- 배치 시점에 송팩을 **스냅샷 복사**한다 — `SongPackApplier.copyTracksToBot`이 봇 개인 플레이리스트를 전량 삭제 후 송팩 트랙을 값 복사. 봇은 송팩을 라이브로 읽지 않는다.
- `BotPlacementService.placeToTarget`은 **카운트 수렴만** 한다 — 송팩 diff 감지 없음, 기존 봇 재청크 없음.

따라서 어드민이 config에서 **songPackId만 교체하고 「적용」하면 무음 no-op**(카운트 불변 → add/remove 0 → `applyChunkToBot` 미호출)이며, 기존 봇은 옛 송팩 복사본을 계속 재생한다. **송팩 곡 구성 편집**도 기존 배치 봇에 전파되지 않는다. 현재 유일한 반영 수단은 수동 「리소스 회수」→「부활」 2단계다.

## 사용자 확정 스코프 (2026-07-11)

- **(a)** `applyConfig`에서 **songPackId 변경 감지 시에만** 자동 재배치. djBotCount 변경은 자동 재배치 **제외**(방 UX 출렁임 회피 — (b) 버튼으로 수동 커버).
- **(b)** 재배치는 **백엔드 단일 엔드포인트**(동일 룸 락 안 drain→place) + 어드민 콘솔 원버튼.

## 목표 / 성공 기준

- 어드민이 송팩을 교체하고 「적용」하면 **추가 조작 없이** 기존 배치 봇들이 새 송팩 스냅샷으로 재생한다 (무음 no-op 소멸).
- 「재배치」 버튼 1회로 회수→부활이 원자적(단일 락)으로 수행된다 — 송팩 내용 편집 반영·djBotCount 변경 후 파티션 재분배·자기갱신 드리프트 리셋 용도.
- 기존 동작 회귀 0: 카운트만 변경 시 기존 수렴(전원 교체 없음), drain/drain-resources/revive/점검/부팅 경로 불변.

## 비목표 (YAGNI)

- djBotCount 변경 시 자동 재배치 (사용자 결정으로 제외).
- 송팩 편집 화면에서 "사용 중 방 N개에 재배치" 프롬프트 (후속 아이디어로만 기록).
- 부분 재배치(특정 봇만), 무중단 롤링 교체.
- Flyway 마이그레이션 없음 (스키마 무변경).

## 설계 — platform

### ① `replaceRoom` 프리미티브 (orchestrator)

- `VirtualCrewOrchestrator` 포트에 `replaceRoom(PartyroomId)` 추가.
- `VirtualCrewOrchestratorImpl.replaceRoom`: 기존 두 메서드와 동일 패턴 — `@Transactional` + **룸 분산락 `virtualcrew:{roomId}` 1회 획득 안에서** `botPlacementService.drainResources(id)` → `botPlacementService.placeToTarget(id)` 순차 실행.
  - 락 1회로 회수·부활 사이 타 어드민/이벤트 개입 창을 봉쇄. **내부는 언락 프리미티브(`drainResources`/`placeToTarget`)를 직접 호출** — 락 잡힌 `drainRoom`/`reconcileRoom`을 합성하면 비재진입 executor가 무음 skip하므로 금지.
  - place 단계 예외 시 복구 모델: `/replace` 엔드포인트 경로는 config 무변경(MANAGED 유지)이라 「부활」 재시도로 복구. applyConfig 자동 replace 경로는 같은 트랜잭션이라 **팩 변경까지 롤백**(기존 reconcile 실패 시맨틱과 동일) — 두 경로의 차이를 주석에 명기.
  - 트랜잭션 경계 주석은 기존 reconcileRoom 문서와 동일 논리로 기술. ⚠️락 TTL(10s) 대비 임계구간이 drain+place로 길어짐 — 기존 placeToTarget 단독도 초과 가능한 선재 리스크 클래스로, 플랜에 주석 한 줄로 기록(스코프 확장 없음).

### ② (a) applyConfig 송팩 변경 감지

`VirtualCrewAdminService.applyConfig`에서:

1. `loadOrCreate` 직후, 변경 **전** 송팩 캡처: `previousSongPackId`.
2. `applyStatus` + `saveAndFlush` (기존 그대로 — 검증 포함).
3. 트리거 분기 (기존 `if (MANAGED) reconcile / else if (OFF) drain` 교체):
   - `status == MANAGED && !Objects.equals(previousSongPackId, songPackId)` → **`orchestrator.replaceRoom`**
   - `status == MANAGED` (팩 동일: 카운트만 변경 등) → 기존 `reconcileRoom`
   - `status == OFF` → 기존 `drainRoom`
- **previousStatus 조건은 두지 않는다** (스펙 리뷰 반영): "OFF였으면 이미 drain됐다"는 가정은 drain 부분실패(per-bot try/catch 삼킴)·락 경합 무음 skip 후 OFF 커밋 시나리오에서 깨질 수 있고, 그 경우 옛 스냅샷 봇이 잔존한 채 OFF→MANAGED(새 팩)가 reconcile로 빠져 함정이 재발한다. 조건 제거 비용은 활성봇 조회+clearRoom no-op 1회로 무시 가능 — **"MANAGED 적용에서 팩이 달라졌으면 무조건 replace"**가 더 단순하고 견고. 신규 config(prev=null)에 팩 지정도 replace 경로로 흐르며 drain이 무해 no-op.
- null↔값 변경도 "변경"이다: 값→null이면 replace의 place 단계가 게이트(songPackId null → skip)에서 자연 no-op → **"송팩 제거=봇 회수"** 의미가 일관되게 성립.
- `applyBulk`는 per-room `self.applyConfig` 경유라 자동 적용(변경 없음).

### ③ (b) 명시적 재배치 엔드포인트

- `VirtualCrewAdminService.replace(PartyroomId)`: `orchestrator.replaceRoom` 위임 + info 로그 (기존 `revive`/`drainResources`와 동일 형태, `@Transactional`).
- `AdminVirtualCrewController`: `POST /api/v1/admin/partyrooms/{id}/virtual-crew/replace` — 가드 `@adminAuth.canManageVirtualCrew()`, 응답 형태는 기존 drain-resources/revive와 동일.
- **상태 검증은 두지 않는다**(기존 revive와 동일 컨벤션): OFF 방에 호출 시 drain은 잔존 봇 정리, place는 게이트 skip — 안전 no-op. MANAGED 게이트는 어드민 UI 버튼 활성화 조건으로만.

## 설계 — admin (lockstep 후행)

- `features/partyrooms/api/virtual-crew-room-api.ts`에 `replaceVirtualCrew(partyroomId)` 추가 (`POST .../replace`).
- 훅 `use-replace-virtual-crew.ts` — 기존 `use-revive-virtual-crew`/`use-drain-resources-virtual-crew` 패턴(뮤테이션 + live status invalidate + 토스트).
- `virtual-crew-config-card.tsx`: 「재배치」 버튼 추가 — 부활·리소스 회수 옆, `live.status === 'MANAGED'` 게이트 동일. **가벼운 확인 다이얼로그 1회**(봇 전원이 퇴장 후 현재 설정·송팩 기준으로 재입장한다는 안내 — drain의 파괴적 빨간 톤 아님, 상태 보존됨). 버튼 부근 헬프 텍스트: 용도 3가지(송팩 내용 편집 반영 / DJ봇 수 변경 후 재분배 / 드리프트 리셋).
- 라벨은 기존 콘솔 표기 관행(한국어 라벨) 준수: 「재배치」.

## 오류 처리

- replace 중 drain 단계 실패: 룸별 per-bot try/catch(기존 drainResources 내부)로 봇 단위 격리 — 이후 place가 잔존 봇을 카운트에 포함해 수렴하므로 부분 실패도 다음 replace/revive로 수습 가능.
- place 단계 실패(풀 고갈 등): 기존과 동일 best-effort + `INSUFFICIENT_IDLE_BOTS` 로그, 예외 아님.
- applyConfig의 자동 replace에서 예외 발생 시: 같은 트랜잭션이므로 config 저장까지 롤백 — 기존 reconcile 실패 시맨틱과 동일(변경 없음), applyBulk에선 per-room 격리로 다른 방 계속 진행.
- **락 경합 무음 skip** (기존 컨벤션 계승): `DistributedLockExecutor`는 락 점유 중이면 warn 로그 후 no-op — 자동 replace/`/replace`가 이 경우 실행되지 않은 채 2xx가 반환될 수 있다(기존 reconcile/drain과 동일). 운영 가이드: 반영이 안 보이면 「재배치」 재클릭. 별도 재시도 로직은 도입하지 않음(기존 컨벤션 유지, YAGNI).

## 테스트

- **유닛**: `VirtualCrewOrchestratorImpl.replaceRoom` — 락 1회 획득·drain→place 순서(언락 프리미티브 직접 호출). `VirtualCrewAdminService.applyConfig` 분기 — (MANAGED 적용, 팩 변경)→replaceRoom / (MANAGED 적용, 팩 동일·카운트만)→reconcileRoom / (신규 config에 팩 지정)→replaceRoom / (팩 null화)→replaceRoom / OFF→drainRoom. `replace()` 위임.
- **기능 IT** (기존 orchestrator IT 하네스 재사용, 패턴A admin 시드): 봇 배치된 방에서 다른 송팩으로 applyConfig → **봇 플레이리스트가 새 팩 스냅샷으로 재복사됨을 단언**(트랙 linkId 비교 — 무음 no-op 회귀 방지의 핵심). 명시적 `/replace` 호출 동등 단언. 카운트만 변경 시 기존 봇 유지(전원 교체 아님) 단언.
- **admin**: api/훅/버튼 테스트(확인 다이얼로그→호출→invalidate) + build(tsc+vite). 기존 686 테스트 회귀 그린.
- **로컬 검증 게이트**: platform fresh DB docker 부팅(bootJar 선행) + 라이브 e2e(admin HTTP로 배치→송팩 교체 적용→새 팩 반영 확인→재배치 엔드포인트 확인). 기존 전체 유닛/IT 회귀 그린.

## 산출물 / 절차

- 이슈: platform **#327** / admin **#26**. 브랜치: `feat/virtual-crew-replace-327`(platform, origin/develop 분기) / `feat/virtual-crew-replace-button-26`(admin, origin/develop 분기).
- **lockstep 머지 순서**: platform #327 → dev 배포 green → admin #26. 커밋/PR 한글, 머지=사용자 게이트.
