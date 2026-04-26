package com.pfplaybackend.api.administration.domain.value;

import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdministratorId {
    private Long aid;

    public AdministratorId(Long aid) {
        this.aid = aid;
    }
}
