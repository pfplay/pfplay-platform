package com.pfplaybackend.api.administration.adapter.in.web.dto;

/**
 * A-2 detail response — profile sub-payload (nickname + introduction).
 * Both fields are nullable: a member may exist without a fully populated bio.
 */
public record MemberProfileSummary(
        String nickname,
        String introduction
) {}
