package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDate;

public record DailyAttendanceBucket(LocalDate date, int entered, int exited) {}
