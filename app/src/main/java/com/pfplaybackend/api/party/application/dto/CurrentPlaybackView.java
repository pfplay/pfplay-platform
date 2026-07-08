package com.pfplaybackend.api.party.application.dto;

import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;

/**
 * 현재 재생 상태의 최소 view — 재생 ID + 현재 DJ crewId.
 *
 * <p>가상 DJ 봇 반응 틱이 party BC 밖에서 "지금 재생 중인 곡"을 참조하기 위한 노출용 DTO.
 * {@code PartyroomAggregatePort}/엔티티를 직접 노출하지 않아 virtualdj 의 AggregatePort 의존 금지
 * 가드(ArchUnit)를 준수한다.
 */
public record CurrentPlaybackView(PlaybackId playbackId, CrewId currentDjCrewId) {}
