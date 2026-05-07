package com.pfplaybackend.api.avatar.domain.value;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

@Embeddable
@Getter
public class AvatarBodyUri {

    @Column(name = "avatar_body_uri")
    private String value;

    public AvatarBodyUri() {
        this.value = "";
    }
    public AvatarBodyUri(String value) {
        this.value = value;
    }
}
