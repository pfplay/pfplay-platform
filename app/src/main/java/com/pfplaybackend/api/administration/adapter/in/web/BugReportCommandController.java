package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.SubmitBugReportRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.SubmitBugReportResponse;
import com.pfplaybackend.api.administration.application.service.BugReportCommandService;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "VOC — Bug Report API")
@RequestMapping("/api/v1/voc/bug-reports")
@RestController
@RequiredArgsConstructor
public class BugReportCommandController {

    private final BugReportCommandService bugReportCommandService;

    @Operation(summary = "버그 리포트 제출",
            description = "사용자가 겪은 버그를 자유텍스트로 제출합니다. 분당 1회 제한. 멤버·게스트 모두 허용.")
    @ApiResponse(responseCode = "201", description = "제출 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({BugReportException.class})
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiCommonResponse<SubmitBugReportResponse>> submit(
            @Valid @RequestBody SubmitBugReportRequest request,
            @RequestHeader(value = "Referer", required = false) String referer,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        Long id = bugReportCommandService.submit(
                request.getContent(),
                referer,
                userAgent,
                request.getPartyroomId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new SubmitBugReportResponse(id)));
    }
}
