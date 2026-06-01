package com.pfplaybackend.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 가상 DJ 아키텍처 의존성 가드 (Chunk 6 + 패키지 범위 확장).
 *
 * <h2>규칙 A — {@code *Orchestrator*} 한정 가드</h2>
 * <ul>
 *   <li>{@code ..administration..} / {@code ..admin..} (어드민 BC 역참조 금지)</li>
 *   <li>crew/dj/playback 의 도메인 {@code *AggregatePort} / persistence {@code *Repository}
 *       (이 경로로 엔티티를 직접 mutate 하면 tryEnter/enqueueDj/exit 가드를 건너뛴다)</li>
 *   <li>메시지 publisher / Redis publish 클래스 ({@code *Publisher}, {@code *MessagePublisher})</li>
 * </ul>
 *
 * <h2>규칙 B — {@code ..virtualdj..} 패키지 전체 가드</h2>
 * virtualdj 패키지의 어떤 클래스도 다음에 직접 의존해서는 안 된다:
 * <ul>
 *   <li>단순명이 {@code MessagePublisher} 로 끝나는 클래스 — 현재 구체: {@code RedisMessagePublisher}.
 *       봇은 도메인 메시지를 직접 발행하지 않는다. 이벤트는 party application service 가 발행한다.</li>
 *   <li>단순명이 {@code AggregatePort} 로 끝나는 인터페이스 — 현재 구체: {@code PartyroomAggregatePort}.
 *       봇이 party aggregate 를 직접 mutate 하면 tryEnter/enqueueDj/exit 도메인 가드를 우회한다.</li>
 * </ul>
 *
 * <p>오케스트레이터는 봇 신원({@link com.pfplaybackend.api.virtualdj.application.identity.BotIdentityExecutor})
 * 으로 실유저와 동일한 application 명령/query 서비스를 호출하는 경로(path A)만 사용해야 한다.
 * 허용 협력자: command/query application service, {@code BotIdentityExecutor},
 * {@code VirtualUserPoolService}, {@code SongPackApplier}, {@code ActiveDjSnapshotService},
 * {@code DistributedLockExecutor}, {@code FlapGuard}, {@code DesiredBotCalculator}, 그리고 자기
 * feature 의 config repo({@code PartyroomVirtualDjConfigRepository}, 단일 row 설정 읽기 — 허용).
 */
@DisplayName("가상 DJ 아키텍처 의존성 가드 (Chunk 6 ArchUnit)")
class VirtualDjArchitectureTest {

    static JavaClasses allClasses;

    @BeforeAll
    static void setUp() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.pfplaybackend.api");
    }

    @Test
    @DisplayName("*Orchestrator* 는 admin/administration BC 를 참조하지 않는다")
    void orchestratorMustNotDependOnAdmin() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..virtualdj..")
                .and().haveSimpleNameContaining("Orchestrator")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.admin..",
                        "com.pfplaybackend.api.administration..");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("*Orchestrator* 는 crew/dj/playback 도메인 AggregatePort/Repository 를 직접 주입하지 않는다 (자기 config repo 만 허용)")
    void orchestratorMustNotDependOnDomainPersistence() {
        // PartyroomVirtualDjConfigRepository(자기 feature 의 단일 row 설정 읽기)는 허용 —
        // 이름에 'VirtualDjConfig' 가 들어가므로 매처에서 제외한다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..virtualdj..")
                .and().haveSimpleNameContaining("Orchestrator")
                .should().dependOnClassesThat()
                .haveNameMatching(".*(AggregatePort|(?<!VirtualDjConfig)Repository)$");
        rule.check(allClasses);
    }

    @Test
    @DisplayName("*Orchestrator* 는 메시지 publisher / Redis publish 클래스를 참조하지 않는다")
    void orchestratorMustNotDependOnMessagePublisher() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..virtualdj..")
                .and().haveSimpleNameContaining("Orchestrator")
                .should().dependOnClassesThat()
                .haveNameMatching(".*(MessagePublisher|Publisher)$");
        rule.check(allClasses);
    }

    // ── 규칙 B: 패키지 전체 가드 ────────────────────────────────────────────────────────────────

    /**
     * {@code ..virtualdj..} 의 어떤 클래스도 {@code *MessagePublisher} 를 직접 의존하지 않는다.
     *
     * <p>현재 해당 타입: {@code RedisMessagePublisher} (패키지: {@code ..common.config.redis..}).
     * 봇이 도메인 메시지를 직접 publish 하는 것은 party BC 이벤트 경로를 우회한다.
     * 이벤트는 party application service 호출의 부작용으로만 발행돼야 한다.
     *
     * <p><b>예외 없음:</b> 현재 virtualdj 헬퍼({@code VirtualSongPackService}, {@code SongPackApplier},
     * {@code VirtualUserPoolService}, {@code ActiveDjSnapshotService}, {@code VirtualMemberProvisionAdapter},
     * {@code *RepositoryImpl} 등)는 모두 자체 virtualdj repo + playlist/track/user repo 만 사용한다.
     */
    @Test
    @DisplayName("virtualdj 패키지 전체: *MessagePublisher 를 직접 의존하지 않는다")
    void virtualDjPackageMustNotDependOnMessagePublisher() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..virtualdj..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("MessagePublisher");
        rule.check(allClasses);
    }

    /**
     * {@code ..virtualdj..} 의 어떤 클래스도 party {@code *AggregatePort} 를 직접 의존하지 않는다.
     *
     * <p>현재 해당 타입: {@code PartyroomAggregatePort} (패키지: {@code ..party.domain.port..}).
     * 봇이 aggregate port 를 직접 주입하면 {@code tryEnter}/{@code enqueueDj}/{@code exit} 도메인
     * 가드를 건너뛰어 파티룸 상태를 직접 mutate 할 수 있다.
     *
     * <p><b>예외 없음:</b> virtualdj 는 party BC 와 상호작용할 때 반드시 application service 를
     * 경유해야 한다. 봇은 {@code BotIdentityExecutor} 로 실유저와 동일한 명령 경로를 탄다.
     */
    @Test
    @DisplayName("virtualdj 패키지 전체: party *AggregatePort 를 직접 의존하지 않는다")
    void virtualDjPackageMustNotDependOnPartyAggregatePort() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..virtualdj..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("AggregatePort");
        rule.check(allClasses);
    }
}
