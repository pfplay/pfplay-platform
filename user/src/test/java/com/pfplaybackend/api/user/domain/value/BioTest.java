package com.pfplaybackend.api.user.domain.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BioTest {

    private static final String PLAYER1 = "Player1";
    private static final String NEW_INTRO = "new intro";

    @Test
    @DisplayName("닉네임과 소개글로 Bio 생성")
    void createWithNicknameAndIntroduction() {
        // given
        Nickname nickname = new Nickname(PLAYER1);

        // when
        Bio bio = new Bio(nickname, "Hello world");

        // then
        assertThat(bio.getNicknameValue()).isEqualTo(PLAYER1);
        assertThat(bio.getIntroduction()).isEqualTo("Hello world");
    }

    @Test
    @DisplayName("nickname이 null이면 getNicknameValue는 null 반환")
    void getNicknameValueWhenNicknameIsNullReturnsNull() {
        // given
        Bio bio = new Bio(null, "intro");

        // when / then
        assertThat(bio.getNicknameValue()).isNull();
    }

    @Test
    @DisplayName("introduction이 null이어도 Bio 생성 가능")
    void createWithNullIntroduction() {
        // given / when
        Bio bio = new Bio(new Nickname(PLAYER1), null);

        // then
        assertThat(bio.getNicknameValue()).isEqualTo(PLAYER1);
        assertThat(bio.getIntroduction()).isNull();
    }

    @Test
    @DisplayName("update 호출 시 닉네임과 소개글 변경")
    void updateChangesNicknameAndIntroduction() {
        // given
        Bio bio = new Bio(new Nickname("OldName"), "old intro");

        // when
        bio.update("NewName", NEW_INTRO);

        // then
        assertThat(bio.getNicknameValue()).isEqualTo("NewName");
        assertThat(bio.getIntroduction()).isEqualTo(NEW_INTRO);
    }

    @Test
    @DisplayName("update 시 유효하지 않은 닉네임은 예외 발생")
    void updateWithInvalidNicknameThrowsException() {
        // given
        Bio bio = new Bio(new Nickname("ValidName"), "intro");

        // when / then
        assertThatThrownBy(() -> bio.update("", NEW_INTRO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateNickname_preservesIntroduction() {
        Bio bio = new Bio(new Nickname("old"), "intro-text");
        bio.updateNickname("new");
        assertThat(bio.getNicknameValue()).isEqualTo("new");
        assertThat(bio.getIntroduction()).isEqualTo("intro-text");
    }

    @Test
    void updateNickname_replacesExistingNickname() {
        Bio bio = new Bio(new Nickname("old"), null);
        bio.updateNickname("renamed");
        assertThat(bio.getNicknameValue()).isEqualTo("renamed");
    }
}
