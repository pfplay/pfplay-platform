package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.party.adapter.in.web.payload.request.management.UpdateNoticeRequest;
import com.pfplaybackend.api.party.application.dto.command.UpdatePartyroomNoticeCommand;
import com.pfplaybackend.api.party.application.service.PartyroomNoticeCommandService;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Partyroom API")
@RequestMapping("/api/v1/partyrooms")
@RestController
@RequiredArgsConstructor
public class PartyroomNoticeCommandController {

    private final PartyroomNoticeCommandService partyroomNoticeCommandService;

    @Operation(summary = "공지사항 등록/수정", description = "파티룸의 공지사항을 등록하거나 수정합니다. 해당 파티룸의 활성 COMMUNITY_MANAGER 이상 크루만 호출 가능합니다.")
    @ApiResponse(responseCode = "204", description = "공지사항 등록/수정 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class, CrewException.class, GradeException.class})
    @PutMapping("/{partyroomId}/notice")
    public ResponseEntity<Void> registerNotice(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
            @Valid @RequestBody UpdateNoticeRequest updateNoticeRequest) {
        partyroomNoticeCommandService.updateNotice(
                new PartyroomId(partyroomId),
                new UpdatePartyroomNoticeCommand(updateNoticeRequest.getContent()));
        return ResponseEntity.noContent().build();
    }
}
