# PFPlay Admin Platform — Roadmap & Decisions (§9, §10, §11)

> Companion to `2026-04-19-admin-platform-design.md`. 본 문서는 §9 Roadmap + §10 Architecture Review Resolutions + §11 Open Decisions를 다룬다.

## 9. Implementation Roadmap

### 9.1 PR Sequence

전체 작업은 14개 PR 내외로 분할한다 (Avatar BC 추가로 기존 12 → 14). 각 PR은 독립 배포/롤백 가능하도록 설계.

| PR | 내용 | Migration | 의존 PR | 크기 |
|---|---|---|---|---|
| **PR 0** | `/api/v1/admin/**` 임시 permitAll 제거, authenticated + belt-and-suspenders 가드 | — | — | **XS** |
| **PR 1** | V4 IAM Refactor (+ 엔티티 재편, profileData 이동, providerType VARCHAR) | V4 | 0 | **XL** |
| **PR 2** | V5 Administrator 테이블 + 슈퍼어드민 placeholder seed + 기존 addAdminUser 제거 | V5 | 1 | M |
| **PR 3** | V9 system_config + 유지보수 모드 filter | V9 | 1 | S |
| **PR 4** | JWT 클레임 재설계 + `/api/v1/auth/admin/login` + BearerTokenResolver 2개 + 쿠키 2개 분리 + rate limit + BCrypt 12 | — | 2 | L |
| **PR 5** | SecurityConfig 전체 개편 + `@PreAuthorize` 중앙화 (adminAuth SpEL bean) + 기존 hasAuthority('FM') 정리 | — | 4 | L |
| **PR 6** | 어드민 CRUD API (F-1): POST/GET/PATCH/revoke/reset-password for administrators | — | 5 | M |
| **PR 7** | V6 Partyroom 상태 enum + 카운터 + display_flag + 전체 엔티티 리팩토링 | V6 | 5 (security 정리 후) | **XL** |
| **PR 8** | V7 partyroom_admin_action + admin partyroom management API (B-2~B-6, B-8) | V7 | 7 | L |
| **PR 9** | V8 penalty history punisher_type + 어드민 페널티 경로 (B-7) | V8 | 8 | M |
| **PR 10** 🆕 | **Avatar 모듈 스캐폴드 + V12 + 엔티티 이관.** 신규 `avatar` Gradle 모듈 생성, `AvatarBody/FaceResourceData` + VO + 레포 user→avatar 이관, `AvatarIconResourceData`/`PairType` 삭제. V12로 icon_uri 흡수 + lifecycle + 감사 컬럼 + face.obtainable_type. `user` 모듈 서비스/레포가 avatar 모듈 포트 경유하도록 내부 재배선 (외부 계약 불변). settings.gradle 수정. | V12 | 8 | **XL** |
| **PR 11** 🆕 | **Avatar 어드민 CRUD + GCS 업로드 + 감사 리스너.** `AdminAvatarCommandController/QueryController`, `AvatarCatalogCommandService/QueryService`, `GcsAvatarStorageAdapter` (google-cloud-storage SDK), 아이콘 전용 재업로드 엔드포인트, Administration 리스너가 `AvatarResourcePublished/Retired` 소비 → `admin_action` 기록. SUPER_ADMIN 권한 가드 (§5 adminAuth bean). | — | 10 (+ 8의 admin_action) | **L** |
| **PR 12** (←10) ✅ | V10 user_activity_log (partitioned) + event listeners + member 관리 API (A-1~A-4) | V10 | 8 | L |
| **PR 13** (←11) ✅ | V13 partyroom_report + 유저용 신고 API + 어드민 검토 API (C-1~C-2) | V13 | 12 | M |
| **PR 14** (←12) | pfplay-admin (프런트엔드 — 별 레포) — 로그인 + 보호 라우트 + AuthStore + 유저/룸 목록 + **Avatar 관리 UI** (§6.I-10) | — | 4 (admin login API) + 11 (Avatar API) | **XL** |

### 9.2 PR 의존 그래프

```
PR 0
 ↓
PR 1 ─── PR 2 ─── PR 3 (병렬 가능)
         ↓
         PR 4
         ↓
         PR 5 ─── PR 6 (병렬 가능)
         ↓
         PR 7
         ↓
         PR 8
         ↓
         PR 9
         ↓
         PR 10 ─── PR 12 (병렬 가능)
         ↓         ↓
         PR 11     PR 13
         └──┬──────┘
            ▼
          PR 14 (프런트)
          (의존: PR 4 로그인 API + PR 11 Avatar API + PR 13 Report API)
```

Avatar PR 10-11은 PR 9 이후에 들어간다. 이유:
- PR 10은 V12 마이그레이션을 수반하며 V4~V11(PR 6 must_change_password 포함) 뒤에 자연스럽게 배치.
- PR 11은 PR 8의 `admin_action` 테이블을 리스너가 참조.
- PR 14(프런트) 출시에 Avatar 관리 UI가 포함되어야 하므로 PR 11이 PR 14 이전에 머지돼야 함. PR 13(신고 API)도 함께 포함되는 게 자연스러움.

### 9.3 위험 항목 & 완화

| 위험 | 완화 |
|---|---|
| PR 1 (V4 IAM refactor) 실패 → 모든 user 기능 중단 | pre-launch라 실유저 데이터 손실 없음. 단, 충분한 테스트 + staging 검증 후 prod 반영 |
| PR 7 (V6 Partyroom 상태 리팩토링) 기존 `isTerminated()` 호출 전부 누락 시 버그 | ArchUnit 테스트 or 정적 분석, 리팩토링 체크리스트, 파티룸 관련 기능 회귀 테스트 |
| Cross-context FK 없음으로 인한 drift | §8 Integrity layer들 적용. Orphan 탐지 배치 day 1부터 운영 |
| 어드민 쿠키 CSRF 노출 | §5.4 2-쿠키 분리 + CSRF 토큰 활성화 |
| Rate limit 우회 (실수/공격) | bucket4j 사용. IP + email 각각 제한. 로드밸런서의 실제 client IP 헤더(X-Forwarded-For) 신뢰 범위 명시 |
| SUPER_ADMIN 추가 생성 (버그) | DB functional unique index + 애플리케이션 검증 |
| `system_config` 캐시 stale | 캐시 TTL 30~60초. 긴급 토글 시엔 관리자가 수동 새로고침 (rare) |
| V10 user_activity_log 파티션 누락 | 매월 새 파티션 생성 배치 + 예비 MAXVALUE 파티션 |
| `ApplicationReadyEventListener` 재부팅 시 seed 충돌 | 모든 initializer 이미 idempotent. V5도 placeholder 체크 idempotent. |
| PR 10 (V12 + 엔티티 이관) MySQL DDL 암시적 커밋으로 인한 부분 실패 | pre-launch라 실데이터 영향 없음. Step 2 실패 시 Step 1만 적용된 상태로 남으면 V11 보정으로 처리 |
| PR 10 JPA 재배선 누락 (`findByNameAndPairType` 호출부 잔존) | 컴파일러가 `PairType` 삭제로 빌드 실패 강제. 같은 PR에 포함되므로 놓칠 수 없음 |
| PR 11 GCS 업로드 중 DB INSERT 실패 → orphan 파일 | 즉시 delete 호출(§6.I-2). 삭제도 실패하면 orphan으로 남음. MVP 배치 청소 비포함(§8.3.4), 문제 발생 시 도입 |
| GCS 서비스 계정 키 유출 | Secret Manager/env 주입 원칙. 레포지토리 커밋 금지 (기존 security 가이드) |

### 9.4 Milestone

- **M0 (PR 0)**: 보안 구멍 봉쇄
- **M1 (PR 1-3)**: IAM 기반 + Administrator + 유지보수 모드 가능
- **M2 (PR 4-6)**: 어드민 로그인 + 어드민 CRUD — 최소 어드민 자체 관리 가능
- **M3 (PR 7-9)**: 파티룸 운영 도구 — 상태/flag/페널티 관리
- **M4 🆕 (PR 10-11)**: Avatar BC + 아바타 리소스 관리 — 과금 기반 스키마/운영 도구 완성 (실제 과금/엔타이틀먼트는 별 마일스톤)
- **M5 (PR 12-13) ✅**: 유저 관리 + 활동 로그 + 신고 시스템 — 완료
- **M6 (PR 14)**: pfplay-admin 프런트엔드 배포 → 실제 사용 가능한 어드민 플랫폼 완성

각 Milestone은 독립 가치 있으므로 중간 배포 가능.

## 10. Architecture Review Resolutions

외부 architecture-reviewer 에이전트의 지적 사항 반영 기록.

### 10.1 FIX BEFORE MERGE (반영 완료)

| # | 지적 | 반영 위치 | 상태 |
|---|---|---|---|
| 1 | `/api/v1/admin/**` permitAll 구멍 | **PR 0** 최우선 처리, §5.2.3 | ✅ 계획됨 |
| 2 | 크로스-서브도메인 쿠키 CSRF 문제 | §5.4 2-쿠키 분리 + CSRF 토큰 | ✅ 설계 반영 |
| 3 | 슈퍼어드민 유일성 DB 미강제 | §4.2.1 functional unique index | ✅ 설계 반영 |
| 4 | V4에서 `user_type` discriminator drop 누락 / `UserAccountData` abstract 제거 / `profileData` 위치 / `providerType` ordinal 저장 | §4.1.2 DDL + §4.1.3 엔티티 리팩토링 범위에 모두 포함 | ✅ 설계 반영 |
| 5 | V5 Administrator 시딩 ↔ 기존 `ApplicationReadyEventListener.addAdminUser()` 충돌 | §4.2.3 기존 addAdminUser 제거 | ✅ 설계 반영 |
| 6 | 어드민 로그인 하드닝 (rate limit, BCrypt cost, env 비번 처리) | §5.5 전체 | ✅ 설계 반영 |
| 7 | `ProviderType` / `AdminRole`을 MySQL ENUM 대신 VARCHAR 저장 | §4.1.2 (provider_type VARCHAR(16)), §4.2.1 (role VARCHAR(32)) | ✅ 설계 반영 |

### 10.2 REVISIT LATER (현재 수용 + 추후 재검토)

| # | 지적 | 현재 입장 | 재검토 조건 |
|---|---|---|---|
| A | 어드민 페널티 전용 엔드포인트 vs 기존 API 재사용 | 재사용 (MVP 속도 우선) | 어드민이 크루 아닌 상태로 페널티 부과하고 싶어질 때 → 별 엔드포인트 도입 |
| B | `admin_audit_log` (before/after snapshot) vs `user_activity_log` | user_activity_log에 통합 | SOC/규정 준수 요구, 또는 복잡 change-tracking 요구 발생 시 분리 |
| C | `user_activity_log` 파티셔닝 | Day 1부터 월별 파티셔닝 | (이미 반영: §4.7.1) |

### 10.3 추가로 반영한 개선점

reviewer 지적 외에도:

| 항목 | 반영 |
|---|---|
| Cross-context FK 전면 금지 | §3.2 원칙, §4.9 전 테이블 FK 정책 |
| 친구/DM 등 미래 기능 대비 | §2.2 Out of Scope. 현 설계가 막지 않음 확인. |
| 도메인 이벤트 기반 통합 강화 | §3.2.3, §8.2 |
| Orphan 탐지 배치 | §8.3 |
| ArchUnit 컨텍스트 경계 테스트 | §8.5 |
| Amplitude `authority_tier` 깨짐 방지 | §5.3.1 클레임 유지 |
| 권한 SpEL 중앙화 (`adminAuth` bean) | §5.2.4 |
| Avatar BC 분리 — 과금 기반 설계 대비 (2026-04-20 재검토) | §3.1 BC 표 재편, §3.3.5 Avatar aggregates, §4.11 V12 DDL, §6.I 어드민 CRUD, §9 PR 10-11 |
| BC 재편 — 실제 Gradle 모듈 구조와 정합 | §3.1 (4→7 BCs), `user` 내 IAM/Profile 패키지 분리, Realtime = Runtime-segregated |

### 10.4 유지되지 않은 지적 — 정당화

reviewer가 제시한 것 중 다르게 판단한 것:

| 지적 | 현 판단 | 이유 |
|---|---|---|
| V5가 schema + seed 묶음 → 분리 권장 (`V5` + `V5_1`) | **묶음 유지** | V3에서 이미 ALTER + INSERT 묶음 사용한 선례. 시드 코드가 Flyway placeholder + ApplicationReadyEvent 교체라 자체 idempotent. 분리 이득 제한적. |
| UserAccount → FK to user_account at shared-kernel | **FK 없음 (더 엄격)** | 사용자 명시 요구: "cross-context FK 반드시 막음". Shared Kernel로도 FK 안 둠. |
| `display_flag`를 별 테이블로 분리 | **같은 테이블에 유지 (Administration 쓰기 전용 규율)** | 분리 이득 미약, 조인 비용 증가. Package 레벨 규율 + 정적 검사로 경계 표현. |
| session revocation (강제 로그아웃) | **MVP 비포함** | 사용자 요구사항: "제재는 파티룸 단위". 전역 session 개념 도입 안 함. |

## 11. Open Decisions & Future Work

### 11.1 결정 후 미룬 것 (MVP 완료 후 재검토)

#### 11.1.1 admin_audit_log 분리

- 현재: `user_activity_log`에 어드민 행위도 함께 기록 (`ADMIN_ACTED_ON`, `partyroom_admin_action`)
- 미래: SOC/규정 준수 요구 발생 시 `admin_audit_log` 별 테이블로 분리 (before/after snapshot, 더 상세한 context)
- 전환 비용: 신규 테이블 + 리스너 + 조회 API. 기존 데이터 마이그레이션은 불필요 (append-only 테이블 분리)

#### 11.1.2 Polymorphic Report

- 현재: `partyroom_report`만 (파티룸 대상)
- 미래: DM, 프로필, 플레이리스트 등 타 대상 신고 필요 시
- 전환 방식: 새 테이블(`user_report`, `dm_report`) 각각 추가 또는 `report (target_type, target_id, ...)` 범용 테이블로 재설계
- 판단 기준: 2번째 대상 타입 등장 시점

#### 11.1.3 RBAC 세분화

- 현재: SUPER_ADMIN / ADMIN 2-role
- 미래: 유저 관리 전용, 모더레이션 전용, 공지 전용 어드민 등 세분화
- 전환: `administrator.role` 유지 + `admin_permission` 테이블 n:n, `@PreAuthorize`는 이미 중앙화된 SpEL bean 경유 → 체크 로직만 permission 기반으로 전환 (§5.2.4)
- 판단 기준: 어드민 인력 증가 + 책임 분리 필요성 생길 때

#### 11.1.4 세션/강제 로그아웃

- 현재: MVP 불포함 (제재는 파티룸 단위)
- 미래: JWT 블랙리스트 (Redis) + `jti` 클레임 + 필터에서 blacklist check
- 판단 기준: 어드민이 "유저 계정 자체 정지" 필요성 생길 때 — 그런데 사용자 결정상 이건 도입 안 할 것 같음

#### 11.1.5 Feature Flag 시스템

- 현재: `system_config` 범용 테이블에 최소 키만 (maintenance)
- 미래: 본격 feature flag UI, A/B 테스트, 롤아웃 %
- 전환: `system_config` 확장 or 외부 SaaS (LaunchDarkly 등) 도입

### 11.2 착수 전 재확인 (2026-04-20 확정)

#### 11.2.1 SUSPEND 정책 ✅

- SUSPEND 상태의 파티룸 동작 범위 (§6.B-4):
- **확정: (a) 입장만 막음, 내부(채팅/리액션/DJ)는 정상**
- "조사/경고 중" 의미로 가볍게 사용. 심각하면 TERMINATE.

#### 11.2.2 탈퇴 시 last_login_at 보존 여부 ✅

- **확정: 보존** (PII 아니며, 어드민이 "언제까지 활동했는지" 확인 가치 있음)

#### 11.2.3 신고자 중복 방지 수준 ✅

- **확정: (b) 차단** — UNIQUE 제약 `(partyroom_id, reporter_user_account_id, category, DATE(created_at))` 기반

#### 11.2.4 어드민 로그인 세션 TTL 정책 ✅

- **확정: (a) 슬라이딩 15분** — API 호출 시마다 AdminAccessToken 연장. 절대 최대 TTL 제약 없음(운영 중 필요 시 도입).

### 11.2B 착수 전 재확인 (Avatar BC 관련, 미확정)

#### 11.2.5 GCS 업로드 실패 시 재시도 정책

업로드 중 network error / GCS 5xx 등 일시 오류 시:
- (a) 서버 측 즉시 재시도 (3회, exponential backoff), 최종 실패 시 502
- (b) 즉시 502 반환, 클라이언트(어드민 UI)가 수동 재시도
- **추천: (b)** — 어드민이 UI로 수동 재시도 가능. 서버 측 재시도는 과설계.
- 착수 시점 확정.

#### 11.2.6 리소스 이미지 포맷/용량 정책

제안안:
- body: PNG/JPG, 최대 2MB
- icon: PNG, 최대 200KB
- face: body와 동일 제한
- 디자이너 제공 실제 파일 분포 확인 후 조정. 착수 시점 확정.

### 11.3 문서 자체에 대한 방향

- 본 문서 세트는 **design-first 단계 마무리**.
- 구현하면서 발생하는 작은 결정들은 **별도 ADR (Architecture Decision Record)** 로 기록 (`docs/adr/...`).
- 본 문서는 월 1회 정도 revisit, 현 MVP 완료 후 "retrospective + v2" 섹션 추가 예정.

### 11.4 리뷰 & 피드백 루프

이 설계 문서 머지 후:
- 팀/동료 리뷰 요청 (혹 있을 경우)
- 구현 시작 전 한 번 더 셀프 리뷰 — 특히 §4 DDL과 §5 security
- PR 0부터 실제 착수하며 설계 괴리 발견 시 본 문서 업데이트

---

**(끝)**

본 문서 세트:
1. `2026-04-19-admin-platform-design.md` — §0~§3 + 문서 인덱스
2. `2026-04-19-admin-platform-schema.md` — §4 Schema Design (V4~V13)
3. `2026-04-19-admin-platform-security.md` — §5 Security Design
4. `2026-04-19-admin-platform-features.md` — §6, §7 Features + Listing UI
5. `2026-04-19-admin-platform-integrity.md` — §8 Integrity Enforcement
6. `2026-04-19-admin-platform-roadmap.md` — §9, §10, §11 Roadmap + Review Resolutions + Open Decisions (본 문서)
