package com.pfplaybackend.api.user.domain.value;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import lombok.Getter;

@Embeddable
@Getter
public class Bio {

    @Convert(converter = NicknameConverter.class)
    @Column(name = "nickname", length = 20)
    private Nickname nickname;

    @Column(length = 50)
    private String introduction;

    protected Bio() {}

    public Bio(Nickname nickname, String introduction) {
        this.nickname = nickname;
        this.introduction = introduction;
    }

    public String getNicknameValue() {
        return nickname == null ? null : nickname.value();
    }

    public void update(String nickname, String introduction) {
        this.nickname = new Nickname(nickname);
        this.introduction = introduction;
    }

    public void updateNickname(String nickname) {
        this.nickname = new Nickname(nickname);
    }
}
