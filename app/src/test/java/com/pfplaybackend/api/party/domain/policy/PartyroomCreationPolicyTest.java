package com.pfplaybackend.api.party.domain.policy;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartyroomCreationPolicyTest {

    private PartyroomCreationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PartyroomCreationPolicy();
    }

    @Test
    @DisplayName("FM 회원은 파티룸 생성 가능")
    void fmCanCreate() {
        assertThatNoException().isThrownBy(() -> policy.enforce(AuthorityTier.FM));
    }

    @Test
    @DisplayName("AM 회원도 파티룸 생성 가능")
    void amCanCreate() {
        assertThatNoException().isThrownBy(() -> policy.enforce(AuthorityTier.AM));
    }

    @Test
    @DisplayName("게스트는 파티룸 생성 불가")
    void guestCannotCreate() {
        assertThatThrownBy(() -> policy.enforce(AuthorityTier.GT))
                .isInstanceOf(ForbiddenException.class);
    }
}
