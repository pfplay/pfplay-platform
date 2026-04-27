package com.pfplaybackend.api.admin.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.payload.request.CreateVirtualMemberRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.request.UpdateVirtualMemberAvatarRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.QueryVirtualMemberResponse;
import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin API Controller for managing virtual members
 * Only accessible by Full Members (FM authority tier)
 */
@io.swagger.v3.oas.annotations.Hidden
@Tag(name = "Admin User API", description = "가상 멤버 관리를 위한 관리자 API")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final UserAccountRepository userAccountRepository;

    @Operation(summary = "가상 멤버 생성", description = "테스트 및 데모용 가상 멤버를 생성합니다. 닉네임과 아바타를 선택적으로 지정할 수 있습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "가상 멤버 생성 성공",
                    content = @Content(schema = @Schema(implementation = QueryVirtualMemberResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/virtual")
    @PreAuthorize("@adminAuth.canChangeMemberTier()")
    public ResponseEntity<QueryVirtualMemberResponse> createVirtualMember(
            @Valid @RequestBody(required = false) CreateVirtualMemberRequest request) {

        String nickname = request != null ? request.getNickname() : null;
        AvatarBodyUri avatarBodyUri = request != null && request.getAvatarBodyUri() != null
                ? new AvatarBodyUri(request.getAvatarBodyUri())
                : null;
        AvatarFaceUri avatarFaceUri = request != null && request.getAvatarFaceUri() != null
                ? new AvatarFaceUri(request.getAvatarFaceUri())
                : null;

        MemberData member = adminUserService.createVirtualMember(nickname, avatarBodyUri, avatarFaceUri);
        QueryVirtualMemberResponse response = buildResponse(member);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "가상 멤버 조회", description = "지정된 userId의 가상 멤버 정보를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "가상 멤버 조회 성공",
                    content = @Content(schema = @Schema(implementation = QueryVirtualMemberResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "해당 가상 멤버를 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/virtual/{userId}")
    @PreAuthorize("@adminAuth.canChangeMemberTier()")
    public ResponseEntity<QueryVirtualMemberResponse> getVirtualMember(
            @Parameter(description = "조회할 가상 멤버의 사용자 ID") @PathVariable String userId) {
        UserId userIdObj = UserId.fromString(userId);
        MemberData member = adminUserService.getVirtualMember(userIdObj);
        QueryVirtualMemberResponse response = buildResponse(member);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "가상 멤버 아바타 수정", description = "지정된 가상 멤버의 아바타(바디, 페이스)를 업데이트합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "아바타 수정 성공",
                    content = @Content(schema = @Schema(implementation = QueryVirtualMemberResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 아바타 URI"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "해당 가상 멤버를 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PutMapping("/virtual/{userId}/avatar")
    @PreAuthorize("@adminAuth.canChangeMemberTier()")
    public ResponseEntity<QueryVirtualMemberResponse> updateVirtualMemberAvatar(
            @Parameter(description = "아바타를 수정할 가상 멤버의 사용자 ID") @PathVariable String userId,
            @Valid @RequestBody UpdateVirtualMemberAvatarRequest request) {

        UserId userIdObj = UserId.fromString(userId);
        AvatarBodyUri avatarBodyUri = new AvatarBodyUri(request.getAvatarBodyUri());
        AvatarFaceUri avatarFaceUri = request.getAvatarFaceUri() != null
                ? new AvatarFaceUri(request.getAvatarFaceUri())
                : new AvatarFaceUri();

        MemberData updatedMember = adminUserService.updateVirtualMemberAvatar(
                userIdObj,
                avatarBodyUri,
                avatarFaceUri
        );

        QueryVirtualMemberResponse response = buildResponse(updatedMember);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "가상 멤버 삭제", description = "지정된 가상 멤버를 영구적으로 삭제합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "가상 멤버 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "해당 가상 멤버를 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @DeleteMapping("/virtual/{userId}")
    @PreAuthorize("@adminAuth.canChangeMemberTier()")
    public ResponseEntity<Void> deleteVirtualMember(
            @Parameter(description = "삭제할 가상 멤버의 사용자 ID") @PathVariable String userId) {
        UserId userIdObj = UserId.fromString(userId);
        adminUserService.deleteVirtualMember(userIdObj);

        return ResponseEntity.noContent().build();
    }

    private QueryVirtualMemberResponse buildResponse(MemberData member) {
        var avatar = member.getProfileData().getAvatarSetting();

        // Email + providerType moved from Member to UserAccount in Task 5/7.
        // Fetch the bound UserAccount once for response shaping.
        UserAccountData userAccount = userAccountRepository.findById(new UserId(member.getUserAccountId()))
                .orElseThrow(() -> new IllegalStateException(
                        "UserAccount missing for member " + member.getMemberId()));

        return QueryVirtualMemberResponse.builder()
                .userId(userAccount.getUserId().getUid().toString())
                .email(userAccount.getEmail())
                .nickname(member.getProfileData().getNicknameValue())
                .introduction(member.getProfileData().getIntroduction())
                .providerType(userAccount.getProviderType())
                .authorityTier(member.getAuthorityTier())
                .avatarBodyUri(avatar.getAvatarBodyUri().getValue())
                .avatarFaceUri(avatar.getAvatarFaceUri().getValue())
                .avatarIconUri(avatar.getAvatarIconUri().getValue())
                .avatarCompositionType(avatar.getAvatarCompositionType())
                .combinePositionX(avatar.getCombinePositionX())
                .combinePositionY(avatar.getCombinePositionY())
                .offsetX(avatar.getOffsetX())
                .offsetY(avatar.getOffsetY())
                .scale(avatar.getScale())
                .createdAt(member.getCreatedAt())
                .updatedAt(member.getUpdatedAt())
                .build();
    }
}
