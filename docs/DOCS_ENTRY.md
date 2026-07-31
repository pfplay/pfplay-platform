# 문서 색인

> 이 레포의 문서가 **무엇이고, 언제 읽어야 하는지**를 한 장에 모은 지도.
> 최종 갱신: 2026-07-31.

문서는 성격에 따라 3층으로 나뉜다.

| 층 | 뜻 | 갱신 책임 |
|---|---|---|
| 🟢 **상시 문서** | 현재 코드를 설명한다. 틀리면 버그로 취급 | 관련 코드를 바꾼 PR 이 같이 갱신 |
| 🔵 **결정 기록(ADR)** | 왜 그렇게 했는지. 뒤집힐 때만 새 ADR 로 대체 | 결정이 바뀔 때만 |
| ⚪ **시점 산출물** | 특정 날짜의 스냅샷·계획·검수. 오래된 게 정상 | 갱신하지 않음(배너로 시점 표시) |

---

## 🟢 상시 문서

| 문서 | 언제 읽나 |
|---|---|
| [`../README.md`](../README.md) | 처음 들어왔을 때. 스택·아키텍처·로컬 실행·테스트·API/WS 계약 전반 |
| [`CONTEXT_MAP.md`](CONTEXT_MAP.md) | 어느 BC 에 코드를 놓을지, 어떤 포트를 쓸지 판단할 때 |
| [`OPERATIONS.md`](OPERATIONS.md) | 배포·장애 대응·스케줄러·점검 모드·기능 게이트를 다룰 때 |
| [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) | 디버깅 시작 전. 이미 알려진 함정을 다시 밟지 않기 위해 |
| [`REFACTORING_ROADMAP.md`](REFACTORING_ROADMAP.md) | 다음에 뭘 할지 고를 때 (1부 = 현행, 2부 = 과거 기록) |
| [`NAMING_CONVENTION.md`](NAMING_CONVENTION.md) | DTO/패키지 이름을 정할 때 |
| [`asyncapi/asyncapi.yml`](asyncapi/asyncapi.yml) | WebSocket 계약의 기계 판독본. 프론트 연동 시 |
| 모듈 README — [`app`](../app/README.md) · [`common`](../common/README.md) · [`realtime`](../realtime/README.md) · [`user`](../user/README.md) · [`playlist`](../playlist/README.md) · [`avatar`](../avatar/README.md) | 해당 모듈에 처음 손댈 때 |

## 🔵 결정 기록 (ADR)

| ADR | 결정 |
|---|---|
| [001](adr/001-unified-entity-model.md) | 통합 엔티티 모델 (`*Data`) |
| [002](adr/002-aggregate-repository-port-facade.md) | Aggregate 리포지토리 포트 파사드 |
| [003](adr/003-id-reference-migration.md) | 객체 참조 → ID 참조 전환 |
| [004](adr/004-hybrid-domain-event-strategy.md) | 하이브리드 도메인 이벤트 전략 |
| [005](adr/005-cross-domain-port-adapter.md) | cross-domain 포트/어댑터 |
| [006](adr/006-admin-csrf-token.md) | 어드민 CSRF 토큰 방어 |
| [007](adr/007-integration-test-flyway-harness.md) | 통합 테스트는 실 Flyway 스키마로 부팅 |
| [008](adr/008-self-healing-reconcile-cron.md) | 시간 기반 상태는 reconcile 크론이 복구 |
| [009](adr/009-presence-liveness-sweep.md) | 유령 presence 는 실 WS 세션과 대조해 판정 |

## ⚪ 시점 산출물

| 문서 | 시점 | 성격 |
|---|---|---|
| [`MATURITY_ASSESSMENT.md`](MATURITY_ASSESSMENT.md) | 2026-02-22 | DDD 성숙도 평가. **채점 기준은 유효, 점수는 스냅샷** |
| [`API_CHANGE_REPORT.md`](API_CHANGE_REPORT.md) | 2026-03-09 | 프론트 연동용 API 변경 보고서 |
| [`TEST_SPEED_ANALYSIS.md`](TEST_SPEED_ANALYSIS.md) | 2026-03-15 | 테스트 속도 측정. 방법론만 재사용 |
| [`asyncapi/REVIEW.md`](asyncapi/REVIEW.md) | 2026-03-09 | WS 이벤트 설계 검수 리포트 |
| [`reviews/`](reviews/) | 각 문서 참조 | 회귀·브랜치 리뷰 기록 |
| [`archive/`](archive/) | 2026 상반기 | 완료된 리팩토링 계획·ERD 변경·DTO 정책 등 |

### `superpowers/specs` · `superpowers/plans`

기능별 **설계서(specs)와 실행 계획(plans)** 이 날짜 접두어로 쌓여 있다(40여 건). 각각은 그 작업
시점의 산출물이며 상시 갱신되지 않는다. 특정 기능의 "왜 이렇게 만들었나" 를 추적할 때 파일명의
날짜와 주제로 찾는다. 최근 것 중 자주 참조되는 것들:

| 문서 | 내용 |
|---|---|
| `specs/2026-07-22-event-driven-reliability-roadmap.md` | 현행 신뢰성 로드맵 (원문) |
| `specs/2026-07-09-integration-test-harness-flyway-validate-design.md` | IT 하네스 설계 |
| `specs/2026-06-19-playback-reconcile-cron-design.md` | 재생 reconcile 크론 설계 |
| `specs/2026-05-18-cluster-a-realtime-subscription-presence-design.md` | 구독·presence 통합 설계 |
| `specs/2026-05-17-announcement-maintenance-lifecycle-design.md` | 공지·점검 라이프사이클 |
| `specs/2026-07-08-virtual-dj-bot-model-overhaul-design.md` | 가상 크루 봇 모델 개편 |

---

## 문서를 고칠 때

- 🟢 상시 문서는 **코드를 바꾼 PR 안에서 같이** 고친다. 별도 "문서 정리" 이슈로 미루면 드리프트가
  누적된다(이 색인이 만들어진 이유).
- 새 결정을 내렸으면 ADR 을 추가한다. 기존 ADR 은 수정하지 않고 새 ADR 로 대체(supersede)한다.
- ⚪ 시점 산출물은 **고치지 않는다.** 내용이 낡았으면 배너로 시점을 명시하고 그대로 둔다.
