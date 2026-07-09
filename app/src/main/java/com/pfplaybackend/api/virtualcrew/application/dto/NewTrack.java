package com.pfplaybackend.api.virtualcrew.application.dto;

import com.pfplaybackend.api.common.domain.value.Duration;

/** 봇 playlist 에 새로 추가할 곡(해소 결과·송팩 폴백의 공용 입력). */
public record NewTrack(String name, String linkId, Duration duration, String thumbnailImage) {}
