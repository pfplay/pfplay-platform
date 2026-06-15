package com.pfplaybackend.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * {@code @Modifying} 쿼리 컨벤션 규칙 (#299 재발 방지).
 *
 * <p>{@code clearAutomatically=true}는 벌크 쿼리 후 stale 1차 캐시를 방어하지만,
 * {@code flushAutomatically=true} 없이 단독 사용하면 같은 트랜잭션에서 아직 flush되지 않은
 * managed 엔티티 변경을 {@code em.clear()}가 조용히 폐기한다 — 2026-06-12 prod 빈 룸
 * 유령 재생 사고(#299)의 root cause. Hibernate auto-flush는 벌크 쿼리의 query space와
 * 겹치는 변경만 보호하므로 다른 테이블의 pending 변경은 구제받지 못한다.
 */
@DisplayName("@Modifying 쿼리 컨벤션 규칙")
class ModifyingQueryConventionTest {

    static JavaClasses allClasses;

    @BeforeAll
    static void setUp() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.pfplaybackend.api");
    }

    @Test
    @DisplayName("clearAutomatically=true인 @Modifying은 flushAutomatically=true를 동반해야 한다 (#299)")
    void modifying_with_clear_must_also_flush() {
        methods().that().areAnnotatedWith(Modifying.class)
                .should(new ArchCondition<>("clearAutomatically=true이면 flushAutomatically=true 동반") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        Modifying modifying = method.getAnnotationOfType(Modifying.class);
                        if (modifying.clearAutomatically() && !modifying.flushAutomatically()) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName()
                                    + " — @Modifying(clearAutomatically=true)에 flushAutomatically=true 누락: "
                                    + "미flush 엔티티 변경이 em.clear()에 폐기될 수 있다 (#299 유령 재생 클래스)"));
                        }
                    }
                })
                .because("clear 단독은 같은 트랜잭션의 미flush 변경을 폐기한다 (#299)")
                .check(allClasses);
    }
}
