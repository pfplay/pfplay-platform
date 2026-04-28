package com.pfplaybackend.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Cross-BC 단방향 의존성 가드 (PR 8).
 *
 * <p>원칙: Administration BC는 ops view integrator 역할이므로 다른 BC를 read 가능하지만,
 * 그 역방향(Party → Administration, User → Administration, Auth → Administration 등)은
 * 금지. Party/User/Auth/Administration 간의 횡적 결합을 막아 BC 간 단방향 흐름을 강제.
 *
 * <p>예외 — 다음 두 패키지는 의도된 cross-BC 통합 경계로 가드에서 제외:
 * <ul>
 *   <li>{@code ..adapter.out.external..} — 헥사고널 cross-BC port adapter
 *       (Phase F refactor에서 도입된 GuestAuthAdapter, PartyCleanupAdapter 등).
 *       다른 BC의 application service를 의존성으로 받지만, 이는 단지 outbound port의
 *       구현이며 자기 BC의 domain/application은 cross-BC 타입을 직접 보지 않음.</li>
 *   <li>{@code ..auth.application..*Admin*} 및 {@code ..auth.adapter.in.web..*Admin*}
 *       — Administration BC의 admin login 플로우 통합 경계 (PR 4/6 IAM 작업에서 도입).
 *       Auth가 AdminRole/AdministratorRepository를 조회해 인증 성립 여부 판정.
 *       이는 spec상 admin 인증 integrator로서 auth가 administration을 read하는 의도된
 *       단방향 의존이며, administration → auth 역방향은 여전히 금지.</li>
 * </ul>
 */
@DisplayName("Cross-BC 의존성 단방향 가드 (PR 8 admin → 다른 BC만 허용)")
class CrossContextDependencyTest {

    static JavaClasses allClasses;

    @BeforeAll
    static void setUp() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.pfplaybackend.api");
    }

    @Test
    @DisplayName("Party 모듈은 user/auth/administration 참조 금지 (cross-BC adapter 제외)")
    void partyMustNotReferenceOthers() {
        // adapter.out.external 은 cross-BC port adapter 의 합법적 통합 경계 (e.g. GuestAuthAdapter)
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.party..")
                .and().resideOutsideOfPackage("com.pfplaybackend.api.party.adapter.out.external..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.user.domain..",
                        "com.pfplaybackend.api.auth..",
                        "com.pfplaybackend.api.administration.."
                );
        rule.check(allClasses);
    }

    @Test
    @DisplayName("User 모듈은 party/administration 참조 금지")
    void userMustNotReferenceOthers() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.user..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.party.domain..",
                        "com.pfplaybackend.api.administration.."
                );
        rule.check(allClasses);
    }

    @Test
    @DisplayName("Auth 모듈은 party/administration 참조 금지 (cross-BC adapter + admin login 제외)")
    void authMustNotReferenceOthers() {
        // 제외 1) adapter.out.external — cross-BC port adapter (e.g. PartyCleanupAdapter)
        // 제외 2) admin login 플로우 (AdminLoginService/AdminAuthResult/AdminLoginResponse) —
        //         auth 가 administration 의 AdminRole/AdministratorRepository 를 read 하는
        //         의도된 단방향 의존. PR 4/6 IAM 에서 도입.
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.auth..")
                .and().resideOutsideOfPackage("com.pfplaybackend.api.auth.adapter.out.external..")
                .and().haveSimpleNameNotContaining("AdminLogin")
                .and().haveSimpleNameNotContaining("AdminAuth")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.party..",
                        "com.pfplaybackend.api.administration.."
                );
        rule.check(allClasses);
    }

    @Test
    @DisplayName("UserActivityLogListener의 모든 on(...) public 메서드는 @TransactionalEventListener + @Async 보유 (PR 12a)")
    void userActivityLogListenerMethodsHaveRequiredAnnotations() {
        // PR 12a §4.3 / §8.3: listener 핸들러 누락 시 sync 동작이라 일관성 깨짐 → annotation 강제.
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().haveSimpleName("UserActivityLogListener")
                .and().arePublic()
                .and().haveNameStartingWith("on")
                .should().beAnnotatedWith(org.springframework.transaction.event.TransactionalEventListener.class)
                .andShould().beAnnotatedWith(org.springframework.scheduling.annotation.Async.class);
        rule.check(allClasses);
    }

    @Test
    @DisplayName("auth.domain.event는 administration에 의존하지 않음 (단방향, PR 12a)")
    void authEventDoesNotDependOnAdministration() {
        // PR 12a §8.3: auth domain event(UserAccountSignedInEvent 등)는 administration BC를
        // 알면 안 됨. administration 이 auth event 를 listener 로 소비하는 단방향 흐름 유지.
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.auth.domain.event..")
                .should().dependOnClassesThat().resideInAPackage("com.pfplaybackend.api.administration..");
        rule.check(allClasses);
    }
}
