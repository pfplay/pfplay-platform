# 가상 DJ(P1) 봇 아바타 변별 셋팅 콘솔 — 설계

- 작성일: 2026-06-02
- 대상 레포: `pfplay-platform`(백엔드, 주), `pfplay-admin`(어드민 UI)
- 선행: P2 = `develop` 라이브(봇 풀 / 송팩 / 룸별 config / reconcile / drain·freeze / 어드민 콘솔 Plan B). PR #283·#285·#287 + admin #17.
- 위치: 3단계 누적 비전(**P1 아바타 콘솔** / P2 능동 디제잉 / P3 AI 에이전트화) 중 P1. P2의 봇 계정 모델 + Plan B 콘솔 위에 증분.

---

## 0. 배경과 목적

P2로 봇(가상 DJ)이 빈 방에 상주하지만, **봇이 전부 같은 기본 아바타로 보인다**. 정확히는 봇이 생성 시 받는 기본 바디 `ava_body_basic_001`은 **combinable 바디**(face 합성 전제)라 자체 채팅 아이콘이 없다(`avatar_icon_uri` NULL). 그 결과:

1. 한 방에 봇이 여러 명이면 **시각적으로 변별 불가** — "여러 사람이 있는 방"이 아니라 "복제된 더미"로 보임.
2. 채팅 아이콘 NULL = P2에서 방어적 null-가드는 넣었으나(PR #287 버그 B), **시각적으로는 빈/깨진 아이콘** 상태.

P1의 목적은 운영자가 어드민 콘솔에서 **봇들에 카탈로그의 다양한 아바타를 입혀(일괄·개별) 방을 "살아있게" 보이게** 하고, **null-icon 갭을 원천 제거**하는 것이다.

### 핵심 결정 (brainstorming 잠금, 2026-06-02)
| ID | 결정 | 근거 |
|---|---|---|
| P1-D1 | 목적 = **시각적 다양성(변별)**, 컨셉/송팩 결속 아님 | 봇들이 똑같이 안 보이게. 카탈로그에서 다양한 바디를 봇별/그룹별로 입힘 |
| P1-D2 | "일괄" = **셋에서 랜덤 분배** (관리자가 아바타 셋 선택 → 선택 봇들에 랜덤 1개씩 배분), 개별 셋팅 별도 제공 | "전부 같은 값"은 다양성 목적과 모순 |
| P1-D3 | 셋 범위 = **전체 카탈로그**(BASIC 4 + DJ_PNT 12, 점수 게이트 없음) | 봇=구별불가(P2 D1)라 실유저에 봇티 안 남, 현재 과금 미개시=희소성 판매 전 |
| P1-D4 | combinable 바디(11종) = **셋에 포함, 공통 face 합성** | 룸 실루엣 풀 다양성 확보. 단 face 카탈로그 1종이라 combinable 11종은 얼굴/채팅아이콘 동일 공유(수용) |
| P1-D5 | provision = **생성 즉시 카탈로그 랜덤 변별 자동 부여** | 깨진 null-icon 디폴트를 원천 제거. distribute와 동일 헬퍼 |

---

## 1. 확인된 사실 (탐색 결과, 코드 근거)

### 1.1 아바타 모델
- `AvatarSetting`(embeddable, `user/.../domain/value/AvatarSetting.java`): `avatarBodyUri` / `avatarFaceUri` / `avatarIconUri` + `avatarCompositionType`(`SINGLE_BODY`|`BODY_WITH_FACE`) + `faceSourceType`(`INTERNAL_IMAGE`|`NFT_URI`) + face 변환 좌표. null-safe 접근자 `getAvatarBodyUriValue()`/`...FaceUriValue()`/`...IconUriValue()`(PR #287)는 null→`""`.
- 저장: `user_profile` 테이블의 위 컬럼들(기존). **P1은 신규 스키마 없음**(아래 §2.6).

### 1.2 카탈로그 ground truth (V3 seed + V12 재구조화)
바디 15종(`avatar_body_resource`), face 1종(`avatar_face_resource`), V12가 icon을 부모 row의 `icon_uri`로 이관(이름-prefix 매칭) 후 `avatar_icon_resource` DROP.

| 구분 | 바디 | `is_combinable` | `icon_uri` | 채팅 아이콘 변별 |
|---|---|---|---|---|
| standalone 4 | `ava_body_basic_002`, `_003`, `ava_body_djing_001`, `_002` | 0 | 있음(body 페어) | **4종 distinct** |
| combinable 11 | `ava_body_basic_001` + `ava_body_djing_003`~`012` | 1 | **NULL** | face 합성 필요 → face 1종이라 **11종 동일** |

- face `ava_face_basic_001`은 `icon_uri` 보유(`ava_icon_face_basic_001`).
- **결론**: 룸 내 바디 실루엣은 15종 다양, 채팅 distinct 아이콘은 사실상 5종(body 4 + face 1). P1-D4에서 이 제약을 수용.

### 1.3 합성/아이콘 결정 로직 (재사용 대상)
`AdminProfileService`(app 모듈) — 주어진 (bodyUri, faceUri)로 composition/icon 자동 결정:
- faceUri 비어있음 → `SINGLE_BODY` + `findAvatarIconPairWithSingleBody(body)` = `body.iconUri`.
- faceUri 있음(non-NFT) → `BODY_WITH_FACE` + `findPairAvatarIconByFaceUri(face)`.
- `AdminAvatarResourceAdapter`(`AdminAvatarResourcePort` 구현): `findAllAvatarBodyResources()`, `findAvatarBodyByUri()`, 아이콘 페어링 제공.
- **함의**: combinable 바디에 face=""를 주면 `icon_uri`가 NULL이라 다시 깨진다. 따라서 **combinable → 반드시 기본 face 합성**해야 유효 아이콘이 나온다(P1 합성 규칙의 근거).

### 1.4 봇 계정 모델 (P2)
- `VirtualUserPoolService.provision(n)`(`virtualdj` 모듈): `VirtualMemberProvisionPort.createVirtualMember(nickname)` → 실회원 경로(LOCAL+프로필+activity+GRABLIST+FM) → `markAsDummy()` → PLAYLIST 1개. 현재 아바타는 포트 내부 디폴트(`basic_001`, combinable=깨짐).
- 봇 식별 = `user_account.is_dummy=true`.

### 1.5 기존 아바타 update 표면
`AdminUserController`(`/api/v1/admin/users/virtual/...`, `@Hidden`, `@adminAuth.canChangeMemberTier()` 게이트) = **P2 이전 데모용 가상멤버 도구**. `PUT /virtual/{userId}/avatar {avatarBodyUri, avatarFaceUri}` 존재(내부 `AdminUserService.updateVirtualMemberAvatar` → composition 자동). **게이트(`canChangeMemberTier`)·경로가 P2 콘솔(`canManageVirtualDj`, `/admin/virtual-dj`)과 불일치** → P1은 콘솔 계약에 맞춰 신설하되 내부 update 로직은 재사용(§2.2).

### 1.6 어드민 콘솔 (pfplay-admin, P2 Plan B)
- nav "가상 DJ"(운영 관리 그룹, role 무제한) → `/virtual-dj/:resourceType`(`pool`|`song-packs`).
- `features/virtual-dj-pool/{api,model,ui}`: `pool-summary-cards`는 방별 `botCount`만, **개별 봇 신원(userId/nickname/아바타) 미노출** = P1이 채울 갭.
- `features/music-search`: pfplay-web 포팅 + **boundary 매퍼**(`to-pack-track.ts`, `videoTitle→name`) — 아바타 피커의 매퍼 패턴 모델.
- `features/partyrooms`: 체크박스 다중선택 + `bulk-action-toolbar`/`virtual-dj-bulk-dialog` 인프라 — 일괄 배분 다이얼로그 모델.
- 호출: `shared/api/http.ts`(AdminAccessToken 쿠키 + XSRF double-submit + Origin). 테스트 vitest + MSW(`server.use`).

---

## 2. 백엔드 (pfplay-platform)

모두 `@adminAuth.canManageVirtualDj()` 게이팅, `ApiCommonResponse` 봉투, 기존 query/port 패턴 준수. 엔드포인트는 P2 콘솔 표면(`AdminVirtualDjController`, app 모듈 `virtualdj`)에 합류.

### 2.1 합성 규칙 — `BotAvatarAssigner` (도메인 헬퍼, 단일 소스)
주어진 `bodyUri`에 대해:
- 카탈로그에서 바디 조회 → `isCombinable=true` → faceUri = 기본 face(`ava_face_basic_001`) → `BODY_WITH_FACE` → face 페어 아이콘.
- `false` → faceUri = `""` → `SINGLE_BODY` → body 페어 아이콘(`body.iconUri`).
내부적으로 기존 (bodyUri, faceUri) → composition 결정 로직(§1.3)에 위임. **불변식: 결과 `avatarIconUri`는 절대 NULL/`""`이 아니다**(단위 테스트로 전수 단언).

랜덤 배분/단건 적용/provision 자동부여가 **모두 이 헬퍼 1곳**을 거친다(divergence 불가).

- **모듈 경계(plan 코드확인 §6-C1)**: `BotAvatarAssigner`는 `virtualdj` 모듈. 두 out-port 필요 — (a) 카탈로그 published 바디 목록/조회(avatar BC), (b) 봇 멤버에 (body,face) 적용(admin/user BC, `updateVirtualMemberAvatar` 래핑). 기존 `AdminAvatarResourcePort`/`AdminUserService` 재사용 가능성 우선 확인, 불가 시 얇은 신규 포트.

### 2.2 랜덤성 — `Randomizer` 포트 (테스트 결정성)
`BotAvatarAssigner`의 셋→봇 매핑은 시드 가능한 `Randomizer`(예: `int nextIndex(int bound)`) 주입. 운영=`SecureRandom`/`ThreadLocalRandom` 어댑터, 테스트=결정적 stub. 분배 결과를 단위/통합에서 검증 가능하게.

### 2.3 신규 엔드포인트
| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/api/v1/admin/virtual-dj/avatar-catalog` | — | `[ { bodyUri, name, thumbnailUri, isCombinable, obtainableType } ]` |
| GET | `/api/v1/admin/virtual-dj/bots` | — | `[ { userId, nickname, avatarBodyUri, avatarIconUri, placementRoomId?, placementRoomTitle? } ]` |
| PUT | `/api/v1/admin/virtual-dj/bots/{userId}/avatar` | `{ avatarBodyUri }` | 200 `{ ...bot }` |
| POST | `/api/v1/admin/virtual-dj/bots/avatar/distribute` | `{ botIds[], bodyUris[] }` | 200 `{ assigned: [ { userId, avatarBodyUri } ] }` |

- **avatar-catalog**: `published`(`lifecycle_status='PUBLISHED'`) 바디만. `thumbnailUri`=`resource_uri`. obtainableType 표시는 운영자 참고용(게이트 아님). face=1종이라 face 선택 UI 없음(combinable은 서버가 자동 합성).
- **bots(로스터)**: is_dummy 봇 목록 + 현재 아바타(바디/아이콘) + 배치된 방(있으면). 데이터원 `BotPoolQueryRepository` 확장(idle 판정 패턴 재사용, crew→partyroom 조인). 정렬은 nickname 또는 생성순.
- **개별 PUT**: `{avatarBodyUri}`만 받고 face는 §2.1 규칙으로 서버 결정(요청에 face 없음). 미존재/비-봇 userId → 404.
- **distribute**: `bodyUris[]`(셋, 비어있으면 400) + `botIds[]`(비어있으면 400). 각 봇에 셋에서 `Randomizer`로 1개 뽑아 §2.1 적용, 한 트랜잭션. 비-봇 id 포함 시 정책 = **무시하고 유효 봇만 적용**(응답 `assigned`에 실제 적용분만; 부분성공). bodyUris 중 카탈로그 비존재 URI → 400(셋 무결성).

### 2.4 provision 변경 (P1-D5)
`VirtualUserPoolService.provision`: 봇 생성·markAsDummy·playlist 후 각 봇에 `BotAvatarAssigner.assignRandomFromCatalog(botUserId)` 호출(전체 published 카탈로그에서 랜덤 1개). 기존 깨진 `basic_001` 디폴트 경로 제거. 동일 헬퍼라 합성/아이콘 불변식 동일 보장.

### 2.5 에러/예외
- 신규 도메인 예외 최소화: 빈 셋/빈 봇목록/미존재 바디 URI = 400(`INVALID_*`, 기존 `GlobalExceptionHandler` 매핑 패턴). 미존재 봇 = 404. 권한 = `canManageVirtualDj` 게이트(403).

### 2.6 마이그레이션 없음 (긍정 신호)
P1은 `user_profile`의 **기존 컬럼만** 갱신. 셋은 요청 본문 transient. provision 자동부여는 코드. **신규 Flyway 마이그레이션 불필요** → V24 같은 마이그레이션↔엔티티 drift / `validate` 부팅 실패 리스크 없음([[reference_ddl_auto_create_drop_hides_migration_drift]] 클래스 회피). e2e 부팅 게이트는 여전히 수행하되, 부팅 실패 표면적이 작다.

### 2.7 테스트 (백엔드)
- `BotAvatarAssigner` 단위: **15종 바디 전수** — combinable→`BODY_WITH_FACE`+face아이콘, standalone→`SINGLE_BODY`+body아이콘, **모든 경우 `avatarIconUri` non-null·non-blank 단언**.
- distribute: `Randomizer` stub으로 결정적 분배 검증 + 빈 셋/빈 봇 400 + 비-봇 무시(부분성공) + 미존재 URI 400.
- 개별 PUT: 성공·404·권한(403).
- provision 통합: n명 생성 후 전원 `avatarIconUri` non-null + 바디가 카탈로그 소속.
- 권한 게이팅(`canManageVirtualDj`) 슬라이스. `:app:test` + `:app:integrationTest` GREEN.

---

## 3. 어드민 프론트 (pfplay-admin)

### 3.1 봇 로스터 (풀 페이지 확장)
`/virtual-dj/pool` 페이지에 **봇 로스터 섹션** 추가(기존 요약 카드 아래):
- 행: 바디 썸네일(`avatarBodyUri`) + 닉네임 + 배치룸(있으면 링크) + 행 체크박스 + "아바타 변경" 버튼.
- 데이터 = `GET /virtual-dj/bots`(§2.3). react-query 키 `["virtual-dj","bots"]`.
- feature: 기존 `features/virtual-dj-pool` 확장(`ui/bot-roster.tsx` 등).
- 봇 수가 많을 수 있으나(N=20~50급) 단순 목록 + 클라 필터로 충분(페이지네이션=비블로커 후속).

### 3.2 아바타 피커 (신규 공용 컴포넌트)
- 카탈로그 썸네일 그리드. 데이터 = `GET /virtual-dj/avatar-catalog`(정적 → staleTime 길게).
- **boundary 매퍼**(`to-avatar-option.ts`): 서버 `{bodyUri, name, thumbnailUri, isCombinable, obtainableType}` → UI 옵션. 어휘 정리 + spread 금지(music-search 매퍼 패턴, [[reference_dto_vocabulary_zero_content_hidden]]).
- 모드: **다중선택(셋, 일괄용)** / **단일선택(개별용)**. combinable/standalone 시각 구분 배지(선택).

### 3.3 일괄 배분 다이얼로그
- 로스터에서 봇 다중선택(체크박스) → 툴바 "아바타 일괄 변경" → 피커(다중선택, 셋) → `POST /virtual-dj/bots/avatar/distribute {botIds[], bodyUris[]}`.
- 빈 셋/빈 선택 zod 차단. 성공 시 결과 요약(몇 명에 배분) + `["virtual-dj","bots"]` invalidate. P2 `virtual-dj-bulk-dialog`/`bulk-action-toolbar` 패턴 계승.

### 3.4 개별 편집 다이얼로그
- 로스터 행 "아바타 변경" → 피커(단일선택) → `PUT /virtual-dj/bots/{userId}/avatar {avatarBodyUri}`. 성공 시 invalidate.

### 3.5 데이터 흐름 & 에러
- 기존 `http` 클라이언트(쿠키+XSRF+Origin). 조회 `useQuery` / 변경 `useMutation`+invalidate. 에러는 admin 패턴(toast + 인라인), 400/404/403 코드별 메시지.

### 3.6 테스트 (프론트, vitest + MSW)
- `to-avatar-option` 매퍼 단위(필드 매핑·빈값 없음·source 어휘 누수 가드).
- 로스터 렌더(아이콘 non-null 썸네일), 피커 다중/단일 선택, 일괄 distribute 요청 바디 매핑, 개별 PUT.
- 회귀: 기존 pool 페이지 테스트 보존(로스터 추가가 요약 카드/생성 폼 안 깨뜨림).

---

## 4. dev 머지 전 로컬 풀스택 e2e 게이트 (필수)

[[feedback_local_e2e_before_dev_merge]] — 단위/통합 GREEN ≠ 배포안전. docker-compose 풀스택(backend :8080 validate 부팅 + admin) 기동 후:
1. **부팅 검증**: `ddl-auto=validate` 프로파일 정상 부팅(마이그레이션 없어도 엔티티 정합 확인).
2. 어드민 로그인 → `GET /virtual-dj/avatar-catalog` published 바디 반환 확인.
3. **풀 생성(provision)** → `GET /virtual-dj/bots` 로스터 전원 **`avatarIconUri` non-null** 확인(P1-D5 검증).
4. **일괄 배분**: 봇 다중선택 → 셋 배분 → 로스터 아바타 변경 반영 확인.
5. **개별 변경** 1건 확인.
6. (가능 시) MANAGED 방 배치 → pfplay-web/렌더 경로에서 봇 아바타가 변별되어 보이는지 확인(없으면 API 레벨 검증으로 대체).
무NPE·무500 확인. 그 후에만 dev 머지.

---

## 5. 범위 밖 (YAGNI)

- 아바타 리소스 CRUD 콘솔(바디/face 추가·편집·retire) — 별도 미래 콘솔. P1은 카탈로그 **소비만**.
- face 카탈로그 확장(데이터/디자인) — combinable 동일 얼굴 제약은 본 단계 수용(P1-D4).
- 컨셉/송팩 결속 아바타(P1-D1 기각).
- 구별가능 모드 뱃지(P2 deferred).
- 봇 닉네임 관리 / 봇 개별 삭제 UI.
- 로스터 페이지네이션·고급 필터(비블로커 후속).

---

## 6. plan 단계 코드확인 항목

- **C1 (모듈 경계)**: `BotAvatarAssigner`(virtualdj)가 카탈로그 조회 + 봇 아바타 적용을 위해 기존 `AdminAvatarResourcePort`/`AdminUserService.updateVirtualMemberAvatar`를 재사용 가능한지(가시성·트랜잭션). 불가 시 얇은 out-port 신설.
- **C2 (로스터 쿼리)**: `BotPoolQueryRepositoryImpl`의 idle 판정(NOT EXISTS active crew) 패턴 위에 "봇+현재 아바타+배치룸" 조인 추가(QueryDSL tuple). placement는 crew→partyroom.
- **C3 (avatar-catalog 노출)**: published 필터 + `resource_uri`를 thumbnail로. avatar BC에 어드민 조회 useCase 존재 여부(`AvatarCatalogQueryUseCase`) 확인 후 재사용.
- **C4 (provision 자동부여 트랜잭션)**: provision 트랜잭션 안에서 아바타 적용이 같은 영속 컨텍스트로 안전한지(멤버 update 경로가 self-flush/이벤트 발행 시 부수효과 없는지).

---

## 7. 산출물 요약

- **pfplay-platform**: `BotAvatarAssigner`(합성 규칙 단일 소스) + `Randomizer` 포트 + GET 2종(avatar-catalog·bots 로스터) + 개별 PUT + distribute POST + provision 자동부여 변경 + 테스트. **마이그레이션 없음.**
- **pfplay-admin**: 봇 로스터(풀 페이지 확장) + 아바타 피커(공용, boundary 매퍼) + 일괄 배분 다이얼로그 + 개별 편집 + 테스트.
- **게이트**: 두 레포 단위/통합 GREEN → 로컬 docker-compose 풀스택 e2e(§4) → dev 머지. **prod 승격은 P1+P3 후 #283~ 묶음 일괄**(메모리 정책 유지).
