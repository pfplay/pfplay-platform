package com.pfplaybackend.api.avatar.domain.value;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

@Embeddable
@Getter
public class AvatarFaceUri {

    @Column(name = "avatar_face_uri")
    private String value;

    public AvatarFaceUri() {
        this.value = "";
    }

    public AvatarFaceUri(String value) {
        this.value = value;
    }
}
