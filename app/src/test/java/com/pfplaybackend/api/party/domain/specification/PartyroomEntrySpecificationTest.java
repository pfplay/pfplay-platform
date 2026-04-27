package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartyroomEntrySpecificationTest {

    private PartyroomEntrySpecification spec;

    @BeforeEach
    void setUp() {
        spec = new PartyroomEntrySpecification();
    }

    private PartyroomData activePartyroom() {
        return PartyroomData.builder().status(PartyroomStatus.ACTIVE).build();
    }

    private PartyroomData terminatedPartyroom() {
        return PartyroomData.builder().status(PartyroomStatus.TERMINATED).build();
    }

    private PartyroomData suspendedPartyroom() {
        return PartyroomData.builder().status(PartyroomStatus.SUSPENDED).build();
    }

    @Test
    @DisplayName("정상 입장 — 예외 없음")
    void validEntry() {
        PartyroomData partyroom = activePartyroom();
        Optional<CrewData> noExistingCrew = Optional.empty();
        assertThatNoException().isThrownBy(() ->
                spec.validate(partyroom, 10, noExistingCrew));
    }

    @Test
    @DisplayName("종료된 파티룸 입장 — ALREADY_TERMINATED")
    void terminatedRoom() {
        PartyroomData partyroom = terminatedPartyroom();
        Optional<CrewData> noExistingCrew = Optional.empty();
        assertThatThrownBy(() -> spec.validate(partyroom, 10, noExistingCrew))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("정원 초과 — EXCEEDED_LIMIT")
    void exceededCapacity() {
        PartyroomData partyroom = activePartyroom();
        Optional<CrewData> noExistingCrew = Optional.empty();
        assertThatThrownBy(() -> spec.validate(partyroom, 50, noExistingCrew))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("밴 당한 유저 재입장 — PERMANENT_EXPULSION")
    void bannedUser() {
        CrewData bannedCrew = CrewData.builder().isBanned(true).build();
        PartyroomData partyroom = activePartyroom();
        Optional<CrewData> banned = Optional.of(bannedCrew);
        assertThatThrownBy(() -> spec.validate(partyroom, 10, banned))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("밴 해제된 유저 입장 — 예외 없음")
    void unbannedUser() {
        CrewData unbannedCrew = CrewData.builder().isBanned(false).build();
        PartyroomData partyroom = activePartyroom();
        Optional<CrewData> unbanned = Optional.of(unbannedCrew);
        assertThatNoException().isThrownBy(() ->
                spec.validate(partyroom, 10, unbanned));
    }

    @Nested
    @DisplayName("status 가드")
    class StatusGuard {

        @Test
        @DisplayName("ACTIVE 입장 허용")
        void active() {
            PartyroomData room = activePartyroom();
            assertThatNoException().isThrownBy(() -> spec.validate(room, 0L, Optional.empty()));
        }

        @Test
        @DisplayName("SUSPENDED 거부 — ConflictException (어드민이 정지한 룸)")
        void suspended() {
            PartyroomData room = suspendedPartyroom();
            assertThatThrownBy(() -> spec.validate(room, 0L, Optional.empty()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("TERMINATED 거부 — ForbiddenException (기존 ALREADY_TERMINATED 코드)")
        void terminated() {
            PartyroomData room = terminatedPartyroom();
            assertThatThrownBy(() -> spec.validate(room, 0L, Optional.empty()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
