package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrewDataTest {

    @Test
    @DisplayName("create — 팩토리 메서드로 생성 시 활성 상태이고 밴 상태가 아니다")
    void createActiveAndNotBanned() {
        // given
        UserId userId = new UserId(10L);

        // when
        CrewData crew = CrewData.create(new PartyroomId(1L), userId, GradeType.CLUBBER, null);

        // then
        assertThat(crew.isActive()).isTrue();
        assertThat(crew.isBanned()).isFalse();
        assertThat(crew.getEnteredAt()).isNotNull();
        assertThat(crew.getGradeType()).isEqualTo(GradeType.CLUBBER);
    }

    @Test
    @DisplayName("create — 팩토리 메서드로 생성 시 partyroomId가 설정된다")
    void createPartyroomIdAssigned() {
        // when
        CrewData crew = CrewData.create(new PartyroomId(99L), new UserId(10L), GradeType.CLUBBER, null);

        // then
        assertThat(crew.getPartyroomId()).isEqualTo(new PartyroomId(99L));
    }

    @Test
    @DisplayName("create — countryCode가 주어지면 저장된다")
    void createWithCountryCode() {
        // when
        CrewData crew = CrewData.create(new PartyroomId(1L), new UserId(10L), GradeType.CLUBBER, CountryCode.of("KR"));

        // then
        assertThat(crew.getCountryCode()).isEqualTo(CountryCode.of("KR"));
    }

    @Test
    @DisplayName("create — countryCode가 null이면 null로 저장된다")
    void createWithoutCountryCode() {
        // when
        CrewData crew = CrewData.create(new PartyroomId(1L), new UserId(10L), GradeType.CLUBBER, null);

        // then
        assertThat(crew.getCountryCode()).isNull();
    }

    @Test
    @DisplayName("deactivatePresence — 퇴장 시 isActive가 false이고 exitedAt이 설정된다")
    void deactivatePresence() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L),new UserId(10L), GradeType.CLUBBER, null);

        // when
        crew.deactivatePresence();

        // then
        assertThat(crew.isActive()).isFalse();
        assertThat(crew.getExitedAt()).isNotNull();
    }

    @Test
    @DisplayName("activatePresence — 재입장 시 isActive가 true이고 enteredAt이 갱신된다")
    void activatePresence() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L),new UserId(10L), GradeType.CLUBBER, null);
        crew.deactivatePresence();

        // when
        crew.activatePresence();

        // then
        assertThat(crew.isActive()).isTrue();
        assertThat(crew.getEnteredAt()).isNotNull();
    }

    @Test
    @DisplayName("updateGrade — 등급 변경 시 gradeType이 업데이트된다")
    void updateGrade() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L),new UserId(10L), GradeType.CLUBBER, null);

        // when
        crew.updateGrade(GradeType.MODERATOR);

        // then
        assertThat(crew.getGradeType()).isEqualTo(GradeType.MODERATOR);
    }

    @Test
    @DisplayName("updateCountryCode — 재입장 시 최신 countryCode로 갱신된다")
    void updateCountryCode() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L), new UserId(10L), GradeType.CLUBBER, CountryCode.of("KR"));

        // when
        crew.updateCountryCode(CountryCode.of("JP"));

        // then
        assertThat(crew.getCountryCode()).isEqualTo(CountryCode.of("JP"));
    }

    @Test
    @DisplayName("updateCountryCode — null로 갱신하면 countryCode가 비워진다")
    void updateCountryCodeToNull() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L), new UserId(10L), GradeType.CLUBBER, CountryCode.of("KR"));

        // when
        crew.updateCountryCode(null);

        // then
        assertThat(crew.getCountryCode()).isNull();
    }

    @Test
    @DisplayName("enforceBan — 밴 부과 시 isBanned이 true가 된다")
    void enforceBan() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L),new UserId(10L), GradeType.CLUBBER, null);

        // when
        crew.enforceBan();

        // then
        assertThat(crew.isBanned()).isTrue();
    }

    @Test
    @DisplayName("releaseBan — 밴 해제 시 isBanned이 false가 된다")
    void releaseBan() {
        // given
        CrewData crew = CrewData.create(new PartyroomId(1L),new UserId(10L), GradeType.CLUBBER, null);
        crew.enforceBan();

        // when
        crew.releaseBan();

        // then
        assertThat(crew.isBanned()).isFalse();
    }
}
