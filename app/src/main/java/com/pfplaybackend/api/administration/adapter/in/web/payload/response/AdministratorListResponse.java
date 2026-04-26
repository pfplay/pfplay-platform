package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import lombok.Builder;

import java.util.List;

@Builder
public record AdministratorListResponse(
        int totalCount,
        List<AdministratorView> items
) {}
