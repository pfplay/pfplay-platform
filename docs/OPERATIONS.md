# Operations

> 이 백엔드를 **운영·디버깅**할 때 필요한 사실만 모은 문서. 기능 설계는 `CONTEXT_MAP.md`,
> 밟기 쉬운 함정은 `KNOWN_ISSUES.md` 를 본다.
> 최종 실사: 2026-07-31 (`origin/develop`).

## 1. 환경 · 브랜치 · 배포

| 환경 | 브랜치 | 트리거 | 이미지 태그 | compose 프로젝트 | 호스트 포트 |
|---|---|---|---|---|---|
| dev | `develop` | `develop` 로 향한 PR **머지(closed)** | `ghcr.io/pfplay/pfplay-api:dev` | `pfplay-dev` | 9090 → 8080 |
| staging | `release` | `release` 로 향한 PR 머지 | `…:stg` | `pfplay-stg` | 8080 |
| prod | `main` | `main` push | `…:prod` | `pfplay-prod` | 8080 |

배포 파이프라인(`.github/workflows/deploy-*.yml`)은 3단계다.

1. `./gradlew :app:bootJar -x test` 로 jar 빌드 → `app/Dockerfile` 로 이미지 빌드 → GHCR push
2. WIF 로 GCP 인증 후, **레포가 단일 진실원천**이라는 원칙에 따라
   `.env.{env}` · `docker-compose.{env}.yml` · `scripts/deploy.sh` 를 매 배포마다 VM 으로 SCP
3. IAP 터널로 `deploy.sh {env}` 실행 → **app 컨테이너만** 재기동(mysql·redis·pytube 는 유지)

`.env.{env}` 는 GitHub Secret `DOT_ENV` 를 그대로 파일로 떨어뜨린 것이다.
과거에 이 시크릿 **첫 줄의 공백** 하나 때문에 배포가 실패한 적이 있다 — 값 편집 시 앞뒤 공백 주의.

### readiness 판정의 한계

`deploy.sh` 는 2초 간격 30회 동안 `GET /` 을 때려 `2xx/3xx/401/403` 중 하나가 오면 성공으로 본다
(Actuator 가 없어서 이렇게 한다). 문제는 **타임아웃이어도 `exit 0`** 이라는 점이다. 즉 부팅 실패가
워크플로 성공으로 보고될 수 있다. 배포 후에는 워크플로 색깔만 믿지 말고 한 번 더 확인한다:

```bash
# 앱이 실제로 라우팅 중인지 (오답 크리덴셜 → 401 이면 컨텍스트 로딩까지 확정)
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<host>/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' -d '{"loginId":"x","password":"y"}'
```

개선안은 [#342](https://github.com/pfplay/pfplay-platform/issues/342).

### 수동 재배포 / 롤백

```bash
# VM 안에서 — 이미지 pull 후 app 만 재기동
./scripts/deploy.sh prod

# 로컬 코드 변경분으로 다시 빌드해 올리고 싶을 때 (deploy.sh 대신)
docker compose -f docker-compose.prod.yml -p pfplay-prod --env-file .env.prod up -d --build app
```

## 2. 스케줄러 · 자가치유 크론

시간 기반 동작은 Redis 키 만료 이벤트에 의존하는데, 이 이벤트는 **구독자가 없으면 영구 유실**된다.
그래서 각 메커니즘에는 주기 reconciler 가 붙어 있다. 아래 표가 현재 도는 전부다.

| 클래스 | 주기 | 하는 일 | 관련 |
|---|---|---|---|
| `PartyroomPlaybackReconcileService.reconcile` | `fixedDelay` 60s | ① 비활성 crew 의 잔존 DJ 제거(필요 시 skip) ② `is_activated=1` 인데 `end_time < now-90s` 인 고착 재생 회수 | #308 · [ADR 008](adr/008-self-healing-reconcile-cron.md) |
| `PartyroomPresenceService.reconcileStalePending` | `fixedDelay` 60s | grace 창을 넘긴 PENDING_EXIT crew 를 강제 OFFLINE. 부팅 직후 복구도 겸함 | #195 |
| `PartyroomPresenceService` liveness 스윕 | `fixedDelay` 5m (+부팅 유예) | DB 상 활성인데 실 WS 세션이 없는 "유령 online" 을 `SimpUserRegistry` 대조로 잡아 PENDING_EXIT 전이 | #356 · [ADR 009](adr/009-presence-liveness-sweep.md) |
| `VirtualCrewReconcileScheduler.reconcileManagedRooms` | `fixedDelay` 60s | MANAGED 룸의 봇 인원 정합, 봇 채팅/반응 틱 | — |
| `MaintenanceSchedulerService` | `cron 0 * * * * *` ×2 | 점검 시작/자동 완료 전이 | — |
| `PartyroomCommandService` | `cron 0 0 3 * * *` | 일 1회(03:00 KST) 파티룸 정리 | — |

운영 시 유의:

- **단일 인스턴스 전제가 남아 있다.** liveness 판정에 쓰는 `SimpUserRegistry` 는 in-process 다.
  수평 확장 시 이 판정은 다른 인스턴스의 연결을 못 본다(스케일아웃 전 반드시 재설계).
- reconcile 크론은 룸별 분산락 + 트랜잭션 내부 재검으로 **멱등**하다. 두 번 돌아도 이중 진행하지
  않는다.
- 새 시간 기반 기능을 넣는다면 reconciler 도 같이 넣는다. 이 레포의 불문율이다.

## 3. 점검(maintenance) 모드

점검 상태의 단일 진실원천은 **`system_announcement` 의 ACTIVE 점검 행**이다
(`maintenance_started_at != null && cancelled_at == null && completed_at == null`).
구 `system_config.maintenance.enabled` 키는 writer 가 없어 죽은 키였고 대체됐다(#267).

전파 경로는 3개이고 각각 역할이 다르다.

| 경로 | 대상 | 전파 속도 |
|---|---|---|
| `ActiveMaintenanceGate` (백엔드 필터) | API 요청 차단 | 30초 스냅샷 캐시 → 최대 30s staleness |
| Vercel Edge Config 키 (`EdgeConfigPort`) | 웹 프론트 점검 화면 rewrite | 즉시 |
| WS `/sub/system/announcements` | 접속 중인 클라이언트 오버레이 | 즉시 |

> 세 경로가 **동시에** 정리돼야 점검이 끝난다. 백엔드만 풀고 Edge Config 키를 안 지우면
> 프론트는 계속 점검 화면을 띄운다(반대도 마찬가지).

## 4. 로그와 조사

- 앱은 구조화 JSON 로그(logstash-logback-encoder) + MDC `requestId` 를 출력한다.
- **다만 VM stdout → Cloud Logging 수집 경로가 끊겨 있다**([#275](https://github.com/pfplay/pfplay-platform/issues/275)).
  현재 1차 수단은 VM 에서의 `docker logs` 다.
- `docker logs --since` 의 인자는 **UTC** 로 해석되는데 컨테이너 TZ 는 `Asia/Seoul` 이라 로그
  타임스탬프는 KST 다. 9시간 어긋난 창을 조회하고 "로그가 없다" 고 오판하기 쉽다.

```bash
docker logs --since "2026-07-31T01:00:00Z" -f pfplay-prod-app-1   # UTC 기준 창
```

## 5. 운영 mutation 원칙

운영 데이터 변경은 **가장 위쪽 게이트부터** 시도한다.

```
어드민 콘솔 UI  >  어드민 API 엔드포인트  >  SQL 직접  >  curl 수동 호출
```

- UI 로 되는 일을 SQL 로 하지 않는다(감사 로그·이벤트 발행이 통째로 빠진다).
- SQL 을 써야 한다면 대상 행을 먼저 SELECT 로 확인하고, 되돌릴 방법을 정한 뒤 실행한다.
- 테이블명은 **소문자**다(`crew`, `partyroom`, `user_profile` …).

## 6. 기능 게이트 (환경변수로 꺼져 있는 것들)

| 기능 | 게이트 | 기본값 |
|---|---|---|
| Web Push 발송 | `WEB_PUSH_ENABLED` + VAPID 키 3종 | `false` — 키 없이는 fail-closed |
| 봇 LLM 채팅/선곡 | `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` (`LLM_PROVIDER` 로 선택) | 미설정 시 LLM 응답 skip |
| 봇 '좋아요' 반응 | `system_config` 의 `vcrew.reaction.enabled` | `false`, **어드민 토글 없음** — 현재 SQL 로만 ON ([#343](https://github.com/pfplay/pfplay-platform/issues/343)) |
| Swagger UI | prod 프로필에서 비활성 | — |

## 7. 로컬 풀스택

```bash
./gradlew :app:bootJar   # 컨테이너는 호스트가 빌드한 jar 를 담는다 — 생략 금지
docker compose -f docker-compose.local.yml -p pfplay-local --env-file .env.local up -d --build
```

- 앱: `http://localhost:8080`, MySQL/Redis/pytube 는 같은 compose 프로젝트 안에 있다.
- `up -d app` 만 하면 **pytube 사이드카가 안 뜬다** → 음악 검색만 실패한다.
- dev 로 머지하기 전 로컬 풀부팅(fresh DB) 한 번이 가장 싼 방어다. JPQL 오타 같은 부류는
  단위 테스트를 통과하고 부팅에서만 터진다.

---

**관련 문서**: [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) · [`CONTEXT_MAP.md`](CONTEXT_MAP.md) · [`adr/`](adr/)
