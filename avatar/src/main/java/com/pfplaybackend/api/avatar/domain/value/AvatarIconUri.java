package com.pfplaybackend.api.avatar.domain.value;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

@Embeddable
@Getter
public class AvatarIconUri {

    @Column(name = "avatar_icon_uri")
    private String value;

    public AvatarIconUri() {
        this.value = "";
    }
    public AvatarIconUri(String value) {
        this.value = value;
    }
}
