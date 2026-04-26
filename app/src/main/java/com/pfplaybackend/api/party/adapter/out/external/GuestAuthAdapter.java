package com.pfplaybackend.api.party.adapter.out.external;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.application.port.out.GuestAuthPort;
import com.pfplaybackend.api.user.application.service.GuestSignService;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GuestAuthAdapter implements GuestAuthPort {

    private final GuestSignService guestSignService;
    private final JwtService jwtService;

    @Override
    public String getOrCreateGuestToken() {
        GuestData guest = guestSignService.getGuestOrCreate();
        return jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                guest.getUserAccountId().toString(),
                "N/A",
                List.of(AccessLevel.ROLE_GUEST),
                AuthorityTier.GT
        ));
    }
}
