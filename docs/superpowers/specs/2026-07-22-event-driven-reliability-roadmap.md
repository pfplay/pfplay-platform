# 이벤트 기반 신뢰성 로드맵 — 파생 상태 드리프트와 테스트 플레이키니스의 구조적 해소

작성: 2026-07-22 · 상태: 딥 리서치 3회(주장별 3표 적대검증 + 3건 직접 원문확인) 기반 확정안
근거 라벨: **[✓]** = 1차 출처 검증 완료 (◇ 잔여 없음 — 전 축 검증됨)

## 0. 배경 — 왜 이 로드맵인가

2026-07-20~22 사이 발생한 사건들(crew 다중활성 wedge #349, 신호 유실 유령 crew #356,
crew_count 드리프트 #358/#360, RaceIT CI 플레이크)은 전부 하나의 구조에서 나왔다:

> **edge-triggered(이벤트 발생 시 반응)로 상태를 유지하면, 이벤트가 유실되는 순간
> 상태가 영구히 어긋난다. 그리고 이벤트는 반드시 유실된다.**

pfplay는 서비스 특성상(실시간 파티룸·presence·재생 동기화) 이벤트 기반일 수밖에 없다.
이 로드맵의 결론은 "이벤트를 버리라"가 아니라, 업계가 수렴한 원칙 —
**"이벤트는 최적화, 정확성은 reconcile"** — 을 사후 대응이 아닌 설계 원칙으로 승격하는 것이다.

## 1. 북극성 원칙 (전부 [✓] 1차 출처 검증)

| # | 원칙 | 출처(원문 인용 검증) |
|---|---|---|
| P1 | **Level-triggered reconcile**: "Functionality must be level-based… regardless of how many intermediate state updates may have been missed. Edge-triggered behavior must be just an optimization" | Kubernetes 설계원칙 문서, controller 공식 문서("no state is provided to Reconcile") |
| P2 | **Constant work**: 변경이 없어도 매 주기 전체 상태를 재적용 — "constantly starting from a clean slate… always operating in repair everything mode" | AWS Builders' Library (Route 53/Hyperplane) |
| P3 | **서버 권위**: "Clients can't reject changes from the server because the server is the ultimate authority" | Figma multiplayer |
| P4 | **재연결 = 스냅샷 재구축**(이벤트 리플레이 아님): "the client downloads a fresh copy… reapplies any offline edits on top" | Figma |
| P5 | **파생 상태 = rebuild 가능한 캐시**: "A materialized view is just a cached subset of the log… you could rebuild it at any time" / 이벤트소싱의 rebuild 정의 | Kleppmann, Fowler |
| P6 | **rebuild 동등성은 상시 검증**: 체크포인트+저널 리플레이가 byte-for-byte 동일함을 프로덕션에서 ~40만 회 연속 검증 후 롤아웃 | Figma reliability |
| P7 | **플레이키는 통계적 현실**: 테스트 실행의 1.5%가 플레이크, 테스트의 16%가 플레이키 성향, **pass→fail 전이의 84%가 플레이키 원인** — 신규 유입율 ≈ 수정율이라 근절 불가, 체계로 관리해야 | Google Testing Blog 2016 + Micco 발표자료 |
| P8 | **플레이크율은 테스트 크기의 연속 함수**: small 0.5% / medium 1.6% / large 14% (바이너리 크기 r²=0.82) — WebDriver류 10~25%는 도구가 아니라 크기가 주 교란변수 | Google Testing Blog 2017 |
| P9 | **PR별 green CI는 불충분**: 각자 green인 PR들이 순차 머지로 베이스를 깰 수 있어 GitHub은 merge queue(합성 브랜치 검증)를 공식 제공 | GitHub Docs |
| P10 | **격리(quarantine)는 기한과 함께**: GitLab은 fast(≤3일)/long-term(≤3개월, 초과 시 삭제 경고 후 삭제) 2단 격리를 운영 | GitLab Handbook |

## 2. 현재 위치 — 이미 구현된 것과 사례 매핑 (전부 [✓] 사후 인증됨)

| 우리 구현 | 대응 원칙/사례 |
|---|---|
| playback/dj reconcile 크론(#308) | P1 — K8s reconcile의 직접 적용 |
| presence liveness 스윕(#356, PR#357) | P1+P2 — 신호유실 유령의 level-triggered 치유 |
| 캐시 카운터 제거 → 라이브 COUNT(#358/#360) | P2+P5 — constant work, "파생물은 매번 진실에서" |
| 서버 권위 원칙(입퇴장 상태 서버 단독 책임) | P3 — Figma와 동일 문장 |
| WS 재연결 resync = tryEnter 전체 재수화(#402) | P4 — Figma "fresh copy" 패턴과 동형 |
| 1-파티룸 구독 invariant | Slack 구독형 presence(수신 이벤트 5배 감소)와 동형 |
| DB 유니크 불변식(uk_crew_active_user) | 애플리케이션 밖(스토리지)에서의 최종 방어선 |
| 파킹된 아웃박스 설계(AFTER_COMMIT+sweeper) | Richardson canonical 패턴과 정확히 일치 — 재개 시 설계 변경 불필요 [✓] |

## 3. Phase 1 — 테스트 신뢰성 체계 (1~2주)

목표: **"빨간불이면 진짜 문제"의 신뢰 복원** (P7의 84% 수치가 이 목표의 정량 근거)

1. **CI 이중 트리거 제거** — push+pull_request 중복 실행을 concurrency group/트리거 정리로
   1회화. 플레이크 노출면 절반. (실측: 동일 커밋 쌍둥이 잡 중 1개만 실패한 2026-07-22 사례)
2. **플레이키 운영 체계 도입** (Google+GitLab 축소 적용 [✓]):
   - 재시도로 통과한 테스트는 **플레이크 대장(ledger)에 자동 기록** — 현행 IT 3-retry
     하네스는 재시도 사실을 기록하지 않아 플레이크가 통계에서 사라진다 (Google은 10x 재실행
     검증 + DB/웹UI 유지)
   - **기한 있는 격리**: 반복 플레이크는 `@Tag("quarantine")`로 메인 게이트에서 분리하되
     GitLab식 기한(fast 3일/long 3개월) — 기한 없는 격리는 은폐다
3. **테스트 계층 하향** (P8 [✓]): large(e2e)는 구조적으로 14%급 플레이크 성향 —
   e2e로만 검증하던 단언 중 unit/IT로 내릴 수 있는 것을 내리고, e2e는 여정 검증에 집중.
   신규 e2e 추가 시 "이 단언이 정말 large여야 하는가"를 리뷰 질문으로 명문화
4. **비결정 단언 감사**: IT/e2e에서 파생 상태·타이밍 단언(sleep, 짧은 timeout, 이벤트 구동
   카운터류) 전수 grep → 라이브 진실 단언으로 전환 (RaceIT에서 이미 실행한 교정의 일반화)
5. **기지 플레이크 2건 근본 수정**: Carry 자정(→ Clock 주입 가상시간 [✓], Phase 3의 선발대),
   display-board IFrame(대기 조건 교정)
6. merge queue(P9 [✓])는 현 팀 규모(순차 머지 관행)에선 **보류** — 동시 머지가 늘어나는
   시점에 재검토

## 4. Phase 2 — 수렴 계층 완성 (3~6주)

목표: **"모든 파생 상태는 reconcile을 갖고, 드리프트는 지표로 보인다"**

1. **드리프트-0 지표** (P6의 축소 적용 [✓] — 본 로드맵의 최우선 신규 항목):
   모든 reconcile 크론(playback/dj, liveness 스윕, 향후 추가분)이 **"발견·치유한 드리프트
   건수"를 메트릭으로 노출**하고, 0이 아니면 알람. 철학: 스윕이 일하고 있다면 그건
   **이벤트 경로 어딘가의 버그 신호**다 — 치유는 안전망이지 면죄부가 아니다.
   (Figma가 저널 롤아웃 전 ~40만 회 동등성 검증으로 한 일의 상시 운영판)
2. **파생 상태 인벤토리**: 이벤트로 유지되는 모든 상태(프레즌스, DJ큐 위치, 세션 레지스트리,
   crew_count류 잔존물)를 표로: ①진실 소스 ②reconcile 존재 ③수렴 시간 SLA ④유실 지표.
   reconcile 없는 파생물 = 발견 즉시 백로그. exitRecordRate(#361)가 유실 지표 1호.
3. **이벤트 2분류 태깅** (Fowler [✓]): 모든 WS 브로드캐스트를 event-notification(수신자는
   재조회) vs event-carried state(페이로드 신뢰)로 명시 선언. carried로 선언한 이벤트만
   페이로드 기반 캐시 갱신 허용 — #402류(notification을 carried처럼 소비) 버그의 분류 프레임
4. **아웃박스 + 멱등 소비자 재개** ([✓] canonical 확정): 파킹된 도메인이벤트 아웃박스 설계
   그대로 재개. at-least-once 특성상 소비자 멱등화 동반 필수. 스케일아웃 전에 까는 것이
   "유실 이벤트→드리프트" 클래스의 구조적 축소
5. **presence ground truth 정의** (1차 조사 openQuestion): 진실이 휘발성(라이브 WS 세션)인
   파생물의 rebuild 기준점을 문서로 확정 — 현행 답: SimpUserRegistry(in-process) >
   Redis 레지스트리(스테일 가능) > DB(내구 기록). 스케일아웃 시 재정의 필요
6. **스케일아웃 대비 매핑** ([✓]): 룸=Discord 길드(룸당 단일 권위 프로세스/락 —
   vdj-reconcile B1/B2), presence 팬아웃=Slack 구독형(룸 스코프 유지), reconcile 크론=
   리더 선출(ShedLock=B2). 재연결 스톰은 Slack이 일급 장애모드로 설계한 시나리오 —
   파킹된 인프라 스케일 하네스의 테스트 케이스로 편입

## 5. Phase 3 — 결정론적 테스트 기반 (분기 단위) [✓ 3차 표적 조사로 검증 완료]

> 3차 조사(2026-07-22, 8개 대상 표적 검증)에서 결정론 시뮬레이션 계열·JVM 도구 전부
> 1차 출처 3-0 검증. 공통 원리: **비결정론 요소(시계·네트워크·스케줄링)를 통제 대상으로
> 옮겨야 재현이 가능하다** — "스레드 많이 띄워 오래 돌리기"는 jcstress 공식 문서조차
> 확률 게임으로 자인하는 모델이다.

1. **Clock 주입 전면화** [✓ 최우선]: `java.time.Clock` Javadoc 원문이 이것을 공식 설계
   의도로 명시 — *"Best practice for applications is to pass a Clock into any method
   that requires the current instant… A dependency injection framework is one way…
   This approach allows an alternative clock, such as fixed or offset to be used during
   testing."* 모든 DST 계열(FDB/TigerBeetle)이 시계 통제를 결정론의 첫 전제로 둔다
   (TigerBeetle: *"all non-deterministic parts… are stubbed out. This includes the
   clock"*). 잔존 `LocalDateTime.now()` 직호출 제거 → 가상시간으로 grace/TTL/자정 검증
   (Carry 자정 플레이크의 근본 해법)
2. **동시성 검증의 결정론화** [✓ 도구별 역할 확정]:
   - **Lincheck(모델 체킹 모드)**: 공유 메모리 접근점에 스레드 전환을 삽입해 **인터리빙을
     결정론적으로 탐색, 같은 입력이면 같은 결과 + 실패 시 실행 트레이스 제공** (kotlinlang
     공식 문서 검증). 상태머신/자료구조 수준 레이스(tryEnter 활성화 경쟁 등)의 정석 도구.
     한계 명시: 순차 일관성 가정이라 약한 메모리 모델 버그는 미커버
   - **jcstress**: 공식 자기정의가 "JVM·클래스라이브러리·하드웨어의 동시성 **정합성 연구**"
     하네스 — 애플리케이션 로직용이 아니며 실행 모델이 공식적으로 확률적(긴 실행 권고,
     단일 통과 무의미). 우리 용도로는 메모리모델 수준 의심 지점에만 한정
   - **100-스레드 실경쟁 IT의 물리적 한계** [✓]: GitHub hosted runner는 **private 레포
     ubuntu-latest 기준 2 vCPU/8GB** (공식 사양표) — 100 스레드 경쟁이 로컬 고사양과
     전혀 다른 스케줄링을 겪는 구조적 이유. 완화(비결정 단언 제거, 완료)를 넘어
     Lincheck 대체가 목표
3. **비동기 대기 표준화** [✓]: Awaitility 공식 목적 — *"a small Java DSL for
   synchronizing asynchronous operations"*, sleep 대신 조건 폴링. 테스트 내 잔존
   sleep/waitForTimeout류를 조건 대기로 전환하는 표준 도구로 채택
4. **인바리언트 프로퍼티 테스트**: "유저당 활성방 ≤1", "DJ ⇒ crew 활성" 등 불변식을
   무작위 조작 시퀀스로 검증 — DB 유니크(1호 방어) 위에 테스트(2호 방어)
5. (영감으로만 [✓ 원리 검증]) FDB식 풀 시뮬레이션(단일 스레드 결정론 클러스터, Flow)·
   TigerBeetle VOPR(시드+커밋 재현, 시간 임의 가속 "1분=며칠")·Antithesis(결정론
   하이퍼바이저)는 현 규모 대비 과잉 — Antithesis 문서 스스로 FDB식 전면 플러그화를
   *"generally impractical for systems already in production"*으로 평가. 1~4의 축소
   적용이 실리적 등가물. (캐비앗: FDB 공식 페이지에 'seed' 단어 부재 — 시드 재현 명문은
   TigerBeetle/Antithesis만. TigerBeetle 재현은 동일 커밋 조건부)

## 6. 실행 우선순위 요약

| 순위 | 항목 | 근거 | 크기 |
|---|---|---|---|
| 1 | CI 이중 트리거 제거 | 실측 재현 + P7 | XS |
| 2 | 드리프트-0 지표 (reconcile 메트릭+알람) | P6 [✓] | S |
| 3 | 플레이크 대장 + 기한부 격리 | P7/P10 [✓] | S |
| 4 | 비결정 단언 감사 + 기지 플레이크 2건 | P8 + 실측 | M |
| 5 | 파생 상태 인벤토리 + 이벤트 2분류 | P5/Fowler [✓] | M |
| 6 | 아웃박스+멱등 소비자 재개 | canonical [✓] | L |
| 7 | Clock 주입 전면화 → 가상시간 | JDK Javadoc 공식 의도 [✓] | M |
| 8 | RaceIT류 → Lincheck 모델체킹 대체 | 결정론 탐색+트레이스 [✓] + 러너 2vCPU 실측 | M |

## 7. 검증 출처 (전 주장 3-0 적대검증 통과)

- Kubernetes: kubernetes.io/docs/concepts/architecture/controller · design-proposals-archive/architecture/principles.md · kubebuilder book · controller-runtime godoc
- AWS: aws.amazon.com/builders-library/reliability-and-constant-work (Colm MacCárthaigh)
- Figma: figma.com/blog/how-figmas-multiplayer-technology-works · making-multiplayer-more-reliable
- Slack: slack.engineering/flannel-an-application-level-edge-cache · docs.slack.dev presence_sub
- Discord: discord.com/blog/how-discord-scaled-elixir-to-5-000-000-concurrent-users (+2023 후속 교차확인)
- Kleppmann: martin.kleppmann.com/2015/03/04/turning-the-database-inside-out
- Fowler: martinfowler.com/articles/201701-event-driven · eaaDev/EventSourcing
- Richardson: microservices.io/patterns/data/transactional-outbox
- Google Testing Blog: 2016/05 flaky-tests-at-google · 2017/04 where-do-our-flaky-tests-come-from · Micco 발표 PDF(research.google)
- GitHub Docs: merge queue 관리/사용 문서 2건
- GitLab: handbook quarantine-process · development/testing_guide/unhealthy_tests
- FoundationDB: apple.github.io/foundationdb/testing.html · Will Wilson Strange Loop 2014(공식 프로그램+녹화)
- TigerBeetle: github tigerbeetle docs/internals/vopr.md · 2023 blog(시간 가속)
- Antithesis: antithesis.com/docs/resources/deterministic_simulation_testing
- OpenJDK jcstress: github.com/openjdk/jcstress README · Shipilëv Hydra 2021 워크숍
- JetBrains Lincheck: kotlinlang.org/docs/lincheck-testing-strategies · getting-started
- JDK: java.time.Clock Javadoc(직접 원문확인 — "dependency injection… fixed or offset… during testing")
- Awaitility: github.com/awaitility/awaitility(직접 원문확인)
- GitHub runner 사양: docs.github.com runners 레퍼런스(직접 원문확인 — private ubuntu-latest 2vCPU/8GB, public 4vCPU/16GB)

캐비앗: 자사 블로그 특성상 실패 사례 과소보고 가능성 · Slack 현행 내부구조 미확인 ·
Figma "40만"은 사전 요건이 아닌 롤아웃 시점 관측치 · AWS 스스로 constant-work의 적용
한계(탄력 웹 플릿) 명시 · 3차 조사로 [◇] 전량 해소(잔여 캐비앗은 §5 본문에 명시).
