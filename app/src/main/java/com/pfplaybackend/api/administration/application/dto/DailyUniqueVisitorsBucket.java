package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDate;

/** #361 전역 일별 순 방문자(ENTERED distinct user) 버킷. */
public record DailyUniqueVisitorsBucket(LocalDate date, long uniqueVisitors) {}
