package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApplyPenaltyRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }
    @AfterAll static void close() { factory.close(); }

    private static AdminApplyPenaltyRequest of(Long crewId, AdminPenaltyType type, String reason) throws Exception {
        AdminApplyPenaltyRequest req = new AdminApplyPenaltyRequest();
        Field f1 = AdminApplyPenaltyRequest.class.getDeclaredField("crewId");
        Field f2 = AdminApplyPenaltyRequest.class.getDeclaredField("penaltyType");
        Field f3 = AdminApplyPenaltyRequest.class.getDeclaredField("reason");
        f1.setAccessible(true); f2.setAccessible(true); f3.setAccessible(true);
        f1.set(req, crewId); f2.set(req, type); f3.set(req, reason);
        return req;
    }

    @Test @DisplayName("정상 입력")
    void valid() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        assertThat(v).isEmpty();
    }

    @Test @DisplayName("crewId null → 위반")
    void crewId_null() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(null, AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        assertThat(v).hasSize(1);
    }

    @Test @DisplayName("penaltyType null → 위반")
    void penaltyType_null() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, null, "abuse"));
        assertThat(v).hasSize(1);
    }

    @Test @DisplayName("reason blank → 위반")
    void reason_blank() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, AdminPenaltyType.ONE_TIME_EXPULSION, "  "));
        assertThat(v).isNotEmpty();
    }

    @Test @DisplayName("reason 256자 → 위반")
    void reason_too_long() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, AdminPenaltyType.ONE_TIME_EXPULSION, "x".repeat(256)));
        assertThat(v).isNotEmpty();
    }
}
