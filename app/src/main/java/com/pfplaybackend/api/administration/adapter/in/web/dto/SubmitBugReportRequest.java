package com.pfplaybackend.api.administration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class SubmitBugReportRequest {
    @NotBlank
    @Size(min = 5, max = 2000)
    private String content;

    @Positive
    private Long partyroomId;
}
