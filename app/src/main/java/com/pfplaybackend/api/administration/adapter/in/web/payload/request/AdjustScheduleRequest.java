package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record AdjustScheduleRequest(@NotNull LocalDateTime scheduledEndAt) {}
