# PFPlay Backend (pfplay-platform)

PFPlay 백엔드. PFP NFT 기반 라이브 뮤직 파티룸 플랫폼의 API·실시간 서비스.

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen?logo=spring&logoColor=white)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.0.30-blue?logo=mysql&logoColor=white)]()
[![Redis](https://img.shields.io/badge/Redis-7-red?logo=redis&logoColor=white)]()
[![Gradle](https://img.shields.io/badge/Gradle-multi--module-02303A?logo=gradle&logoColor=white)]()

- Notion 백엔드 위키: `<TODO notion-backend-wiki>`
- Notion KanBan: `<TODO notion-kanban>`
- Slack 채널: `<TODO slack-channel>`
- 관련 리포: [pfplay-web](https://github.com/pfplay/pfplay-web) (사용자 프론트), [pfplay-admin](https://github.com/pfplay/pfplay-admin) (운영 어드민), `<TODO pfplay-streaming-url>` (pytube)

## Table of Contents

1. [빠른 시작](#빠른-시작)
2. [도메인 & 모듈 개요](#도메인--모듈-개요)
3. [아키텍처](#아키텍처)
4. [인증 & 보안](#인증--보안)
5. [실시간 통신 (STOMP)](#실시간-통신-stomp)
6. [API 문서](#api-문서)
7. [빌드 & 실행](#빌드--실행)
8. [데이터베이스 & 마이그레이션](#데이터베이스--마이그레이션)
9. [테스트](#테스트)
10. [CI/CD 및 배포](#cicd-및-배포)
11. [외부 링크 & 참고](#외부-링크--참고)

> **운영 정책·함정 모음은 [docs/OPERATIONS.md](docs/OPERATIONS.md)로 분리되어 있습니다. 코드 변경 전 반드시 한 번 훑어보세요.**

## 빠른 시작

### Prerequisites
- **JDK 21** (Microsoft / Eclipse Temurin 권장)
- **Docker / Docker Compose**
- 로컬은 `docker-compose.local.yml` + `.env.local` 패턴 (둘 다 gitignored)

### Windows JDK 환경
시스템 PATH에 다른 JDK가 잡혀 있을 수 있어 Gradle 호출 시 `JAVA_HOME` prefix를 권장합니다:

```powershell
# PowerShell
$env:JAVA_HOME = "C:/Users/Eisen/.jdks/ms-21.0.7"
./gradlew :app:bootRun
```

```bash
# Bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:bootRun
```

자세한 사항은 `docs/OPERATIONS.md`의 "JDK 21 빌드 환경" 참고.

### 로컬 인프라 + 앱 동시 기동
프로젝트 루트에 `.env.local`을 만들고 (구체값은 Notion 환경변수 페이지 참고), 다음을 실행:

```bash
docker compose -f docker-compose.local.yml -p pfplay-local --env-file .env.local up -d --build
```

- `local` profile이 강제됩니다
- MySQL 8.0.30 + Redis 7 + pytube(스트리밍) + app(8080)이 동시 기동됩니다
- 시간대는 KST 강제 (MySQL `--default-time-zone=+09:00`, app `ClockConfig`)
- super-admin이 자동 시드됩니다: `admin@pfplay.local` / `local-test-only-rotate-in-prod`

### Gradle 직접 실행 (인프라만 컨테이너로)
IDE에서 앱을 띄우고 싶을 때 인프라만 띄우는 흐름도 가능합니다:

```bash
docker compose -f docker-compose.local.yml -p pfplay-local --env-file .env.local up -d mysql redis pytube
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

### 검증
```bash
curl http://localhost:8080/actuator/health
# 브라우저로 Swagger UI:
open http://localhost:8080/spec/api
```

## 도메인 & 모듈 개요

### 6 Gradle 모듈
| 모듈 | 의존 | 책임 |
|---|---|---|
| `common` | (없음) | Shared Kernel + 인프라 config (Security, JPA, Redis, JWT, Cache, Swagger, 예외 처리), 공유 VO(`UserId`, `Duration`) |
| `realtime` | `common` | WebSocket/STOMP 인프라. **도메인 import 없음** — port 인터페이스만 노출 (`WebSocketAuthPort`, `SessionCachePort`) |
| `playlist` | `common` | 플레이리스트·트랙 도메인 |
| `avatar` | `common` | 아바타 카탈로그 (body/face/combinable/icons, lifecycle, `AvatarBodyUri`/`FaceUri`/`IconUri`) |
| `user` | `common`, `avatar` | 회원·게스트·프로필 도메인 |
| `app` | (전체) | Auth, Party(룸/크루/DJ/재생/채팅), Admin, Reports(V13), Announcements(V14), Bootstrap |

의존 방향: `app → user → avatar → common`, `app → playlist → common`, `app → realtime → common`. 순환 의존 없음.

### 도메인 ↔ 프론트 콘솔 매핑
| 백엔드 도메인 | pfplay-admin | pfplay-web |
|---|---|---|
| 회원 / 게스트 | `/members` | `(auth)`, `settings` |
| 파티룸 / 크루 / DJ / 재생 / 채팅 | `/partyrooms` | `parties/(lobby)`, `parties/(room)` |
| 신고 (V13) | `/reports` | features/partyroom 내 신고 UI |
| 아바타 카탈로그 | `/avatars/:resourceType` | `settings/avatar` |
| 시스템 공지 (V14) | `/announcements` | features/system-announcement |
| Super-admin / 어드민 사용자 | `/administrators` | — |

## 아키텍처

### Hexagonal (Ports & Adapters) + DDD
```
[ Inbound Adapters ]
  adapter/in/web/        — REST Controllers
  adapter/in/listener/   — Redis Topic Listeners
  adapter/in/stomp/      — WebSocket STOMP Controllers
        ↓
[ Application Layer ]
  application/service/   — Use Case Orchestration
  application/port/out/  — Outbound Port Interfaces
        ↓
[ Domain Layer ]
  domain/entity/data/    — JPA Entities (*Data.java) + Business Logic
  domain/service/        — Domain Services
  domain/value/          — Value Objects
        ↑
[ Outbound Adapters ]
  adapter/out/persistence/  — JPA + QueryDSL
  adapter/out/external/     — Cross-domain Port Adapters
```

핵심 원칙:
- 명확한 도메인 경계 (Party, User, Avatar, Playlist 등)
- 통합 데이터 엔티티 `*Data.java`에 비즈니스 로직 동거
- Value Object로 타입 안전성 (`UserId`, `PartyroomId`, ...)
- 도메인 간 의존은 **Port/Adapter 통과** (직접 import 금지)

### Event-Driven (Redis pub/sub)
- 인스턴스 간 통신은 Redis pub/sub
- 15+ 도메인 이벤트 (재생/입퇴장/등급변경/공지 등)
- 메시지 패싱으로 컴포넌트 디커플 — stateless API 설계

### 데이터 계층
| 컴포넌트 | 도구 | 비고 |
|---|---|---|
| RDBMS | MySQL 8.0.30 | utf8mb4, KST(+09:00) |
| In-memory | Redis 7 | 캐시 + pub/sub + 분산 락 + 세션 |
| ORM | JPA / Hibernate | |
| Type-safe Query | QueryDSL 5 | |
| Migration | Flyway | `out-of-order=false` — 정책은 `docs/OPERATIONS.md` |
| SQL 로깅 | P6Spy | local/dev 활성 |

## 인증 & 보안

### OAuth2 + JWT cookie (pfplay-web)
- **Google / Twitter OAuth2** 진입
- Access token 24h / Refresh token 7d — 쿠키로 관리
- 게스트 모드 별도 (익명 진입)

### 패스워드 + JWT cookie (pfplay-admin)
- super-admin이 발급한 패스워드 로그인. OAuth2 미사용
- `mustChange` 강제 흐름 (첫 로그인 비밀번호 변경)
- super-admin V5 placeholder seed 라이프사이클은 `docs/OPERATIONS.md`

### Cookie 도메인 분리 (의도된 설계)
- `ADMIN_COOKIE_DOMAIN=admin.pfplay.xyz` — SameSite=**Strict**
- shared `COOKIE_DOMAIN=.pfplay.xyz` — SameSite=**Lax**

**통일 금지.** 자세한 배경은 `docs/OPERATIONS.md`의 "Cookie 도메인 분리".

### CSRF — echo + OAuth2 wrapping workaround
- `XSRF-TOKEN` cookie 발급 → 클라이언트가 `X-XSRF-TOKEN` header로 echo
- `SecurityConfig` post-processor에서 `setRequireCsrfProtectionMatcher`를 다시 호출 (framework wrapping override)
- upstream: spring-security#17959 / #8668. upstream fix 시 제거 가능

### admin-origin-guard
- `application.yml`에 admin 허용 origin이 하드코딩되어 있습니다 (env 분리 pending)
- 새 admin 도메인을 띄울 때 `application.yml`도 함께 갱신해야 합니다 — `docs/OPERATIONS.md` 참고

## 실시간 통신 (STOMP)

### 연결
- 엔드포인트: `/ws`
- 인증: handshake header의 JWT 검증
- 모듈: `realtime` (zero domain imports — port 인터페이스만)

### Publish 토픽
- `/pub/groups/{chatroomId}/send` — 그룹 채팅 메시지
- `/pub/heartbeat`

### Subscribe 토픽 (요약)
- `/sub/groups/{chatroomId}` — 그룹 채팅
- `/sub/events/{partyroomId}/chat-message`
- `/sub/events/{partyroomId}/partyroom-access` (입·퇴장)
- `/sub/events/{partyroomId}/playback-start` / `playback-skip`
- `/sub/events/{partyroomId}/playback-reaction` / `playback-reaction-motion`
- `/sub/events/{partyroomId}/crew-grade` / `crew-penalty`
- `/sub/events/{partyroomId}/profile-update` / `notice-update`
- `/sub/events/{partyroomId}/partyroom-deactivation`
- `DjQueueChanged`

AsyncAPI 스펙: `docs/asyncapi/`

## API 문서

### Swagger UI
```
http://localhost:8080/spec/api    # 로컬
```

엔드포인트의 신뢰 가능한 소스는 항상 Swagger입니다. README는 분류만 둡니다.

### 주요 카테고리
- `/api/v1/auth/oauth/*` — OAuth 흐름
- `/api/v1/users/*` — 회원·게스트·프로필·아바타
- `/api/v1/partyrooms/*` — 파티룸 CRUD, 입퇴장, 공지, 재생, 반응
- `/api/v1/partyrooms/{id}/dj-queue/*` — DJ 큐
- `/api/v1/partyrooms/{id}/crews/*` — 크루 관리, 제재
- `/api/v1/playlists/*` — 플레이리스트 + 트랙
- `/api/v1/music-search` — YouTube 음악 검색
- `/api/v1/reports/*` — 신고 (V13)
- `/api/v1/announcements/*` — 시스템 공지 (V14)
- `/api/v1/admin/*` — 어드민 콘솔 전용

## 빌드 & 실행

### Gradle
```bash
./gradlew :app:build                            # 빌드 (테스트 포함)
./gradlew :app:bootRun --args='--spring.profiles.active=local'
./gradlew :app:bootJar                          # 단독 실행 가능한 jar
./gradlew test                                  # 모듈 전체 테스트
./gradlew :app:test                             # app 모듈만
./gradlew :user:test                            # user 모듈만
```

### Profiles
- `local` — 로컬 개발 (docker compose 또는 IDE)
- `dev` — dev 환경 (GCE)
- `stg` — staging
- `prod` — production

### 핵심 환경 변수
| 변수 | 설명 |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL |
| `REDIS_HOST`, `REDIS_PORT` | Redis |
| `OAUTH_GOOGLE_CLIENT_ID`, `OAUTH_GOOGLE_CLIENT_SECRET` | Google OAuth |
| `OAUTH_TWITTER_CLIENT_ID`, `OAUTH_TWITTER_CLIENT_SECRET` | Twitter OAuth |
| `JWT_ACCESS_TOKEN_SECRET`, `JWT_REFRESH_TOKEN_SECRET` | JWT secret |
| `YOUTUBE_API_KEY` | YouTube 검색 |
| `PYTUBE_URI`, `PYTUBE_API_KEY`, `PYTUBE_API_SECRET` | pfplay-streaming(pytube) |
| `COOKIE_DOMAIN`, `ADMIN_COOKIE_DOMAIN` | 쿠키 도메인 (`docs/OPERATIONS.md`) |
| `ADMIN_SEED_EMAIL`, `ADMIN_SEED_PASSWORD` | 첫 부팅 super-admin seed (이후 제거 권장) |

전체 키는 Notion 환경변수 페이지 참고.

## 데이터베이스 & 마이그레이션

### Flyway
- 위치: `app/src/main/resources/db/migration`
- 네이밍: `V{버전}__{설명}.sql`
- 정책: **`spring.flyway.out-of-order=false`**
- 부팅 시 자동 적용

### dev/stg DB reset 정책
- dev/stg는 schema breaking change 시 reset 허용 (개발 효율 우선)
- prod는 reset 금지. 데이터 보존 마이그레이션 필수
- 자세한 사항은 `docs/OPERATIONS.md` "Flyway 정책"

### 슬롯 점프 회복 패턴
PR이 머지 순서대로 들어오지 않으면 마이그레이션 버전 슬롯이 점프할 수 있습니다 (예: `V12` 다음 바로 `V14`). 회복 절차는 `docs/OPERATIONS.md`의 "슬롯 점프 회복 패턴" 참고.

## 테스트

- **JUnit 5** + **Spring Boot Test**
- 모듈별 단위 테스트, app 모듈에 통합 테스트
- 테스트 속도 분석: `docs/TEST_SPEED_ANALYSIS.md`

```bash
./gradlew test                # 전체 모듈
./gradlew :app:test           # app 모듈만
./gradlew :user:test          # user 모듈만
```

## CI/CD 및 배포

### GitHub Actions
`.github/workflows/`:
- **ci-test.yml** — 모든 브랜치 push + main/develop/release PR 시 `./gradlew test`
- **deploy-dev.yml** — develop PR merge → dev 배포
- **deploy-stg.yml** — release PR merge → stg 배포
- **deploy-prod.yml** — main push → prod 배포 (GCP VM via IAP)
- **registry-cleanup.yml** — 수동 트리거. floating-tag 재할당으로 누적되는 untagged 이미지 정리 (비상 롤백용 buffer 보존)

### 환경 ↔ 브랜치 매핑
| 환경 | 브랜치 | 비고 |
|---|---|---|
| prod | `main` | GCE VM via IAP |
| stg | `release` (영구 브랜치) | `develop`을 PR로 받음 |
| dev | `develop` | |

### 이미지
- 베이스: `ghcr.io/pfplay/pfplay-api` + floating tag(`local`, `dev`, `stg`, `latest` 등)
- Dockerfile: `app/Dockerfile`
- JVM TZ KST 고정 (`ENV TZ=Asia/Seoul` — `docs/OPERATIONS.md` 참고)

### prod 배포 요약
1. `release` → `main` 머지
2. `deploy-prod.yml` 자동 실행
3. GCP IAP를 통해 VM에 ssh, 새 이미지 pull + restart
4. **Cloud Run 아님** — GCE VM 단일 인스턴스 + DOT_ENV append 방식

### 문서/README만 바꿔도 빌드 발생
현재 워크플로에 path filter가 없어 README 변경만으로도 모든 ci-test / deploy 워크플로가 트리거됩니다.

## 외부 링크 & 참고

### PFPlay 프로젝트 링크
- Notion 백엔드 위키: `<TODO notion-backend-wiki>`
- Notion KanBan: `<TODO notion-kanban>`
- Notion 환경변수 페이지: `<TODO notion-env-page>`
- Slack 채널: `<TODO slack-channel>`

### 관련 리포
- [pfplay-web](https://github.com/pfplay/pfplay-web) — 사용자 프론트 (Next.js)
- [pfplay-admin](https://github.com/pfplay/pfplay-admin) — 운영 어드민 (React + Vite, Cloudflare Pages)
- `<TODO pfplay-streaming-url>` — pytube 스트리밍 서비스

### 내부 문서
- **[docs/OPERATIONS.md](docs/OPERATIONS.md)** — 운영 함정·정책 모음 (필독)
- [docs/CONTEXT_MAP.md](docs/CONTEXT_MAP.md) — Bounded context 매핑
- [docs/NAMING_CONVENTION.md](docs/NAMING_CONVENTION.md) — 명명 규칙
- [docs/MATURITY_ASSESSMENT.md](docs/MATURITY_ASSESSMENT.md) — DDD 성숙도 평가
- [docs/REFACTORING_ROADMAP.md](docs/REFACTORING_ROADMAP.md) — 리팩터링 로드맵
- [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) — 알려진 이슈
- [docs/TEST_SPEED_ANALYSIS.md](docs/TEST_SPEED_ANALYSIS.md) — 테스트 속도 분석
- [docs/adr/](docs/adr/) — Architecture Decision Records
- [docs/asyncapi/](docs/asyncapi/) — WebSocket AsyncAPI 스펙

### 기술 레퍼런스
- [Spring Boot](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring Security](https://docs.spring.io/spring-security/reference/)
- [Spring WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket.html)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [QueryDSL](http://querydsl.com/)
- [Flyway](https://flywaydb.org/documentation/)
- [STOMP over WebSocket](https://stomp.github.io/)

---

Built with Spring Boot 3.2 + Java 21 + MySQL 8.0 + Redis 7.
