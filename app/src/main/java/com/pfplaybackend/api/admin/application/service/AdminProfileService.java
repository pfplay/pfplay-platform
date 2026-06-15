package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.port.out.AdminAvatarResourcePort;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for creating and managing profiles for virtual (admin-created) members
 */
@Service
@RequiredArgsConstructor
public class AdminProfileService {

    private final AdminAvatarResourcePort adminAvatarResourcePort;

    /**
     * Create profile for virtual member with auto-generated nickname and default avatar
     * Similar to Guest profile creation but with "Virtual_" prefix
     *
     * @param userId User ID
     * @return Initialized profile with default values
     */
    public ProfileData createProfileForVirtualMember(UserId userId) {
        return createProfileForVirtualMember(userId, null, null, null);
    }

    /**
     * Create profile for virtual member with specified nickname and/or custom avatar
     *
     * @param userId        User ID
     * @param nickname      Optional nickname (auto-generated if null)
     * @param avatarBodyUri Optional avatar body URI (default if null)
     * @param avatarFaceUri Optional avatar face URI (default if null)
     * @return Initialized profile
     */
    public ProfileData createProfileForVirtualMember(
            UserId userId,
            String nickname,
            AvatarBodyUri avatarBodyUri,
            AvatarFaceUri avatarFaceUri) {

        // Generate nickname if not provided
        String finalNickname = (nickname != null && !nickname.isBlank())
                ? nickname
                : generateRandomNickname();

        // Resolve composition/icon/transform from the body+face inputs (single source of truth).
        ResolvedAvatar resolved = resolveAvatar(avatarBodyUri, avatarFaceUri);

        // Build ProfileData directly (CREATE path — a brand-new transient profile is correct here).
        ProfileData profileData = ProfileData.builder()
                .userId(userId)
                .nickname(new Nickname(finalNickname))
                .introduction("")
                .avatarCompositionType(resolved.compositionType())
                .faceSourceType(resolved.faceSourceType())
                .avatarBodyUri(resolved.bodyUri())
                .avatarFaceUri(resolved.faceUri())
                .avatarIconUri(resolved.iconUri())
                .combinePositionX(resolved.combinePositionX())
                .combinePositionY(resolved.combinePositionY())
                .offsetX(0)
                .offsetY(0)
                .scale(1.0)
                .build();

        return profileData;
    }

    /**
     * Apply an avatar (body + optional face) to an <b>already-persisted</b> virtual member by
     * MUTATING its existing {@link ProfileData} row — NOT by replacing the {@code @OneToOne}
     * profile reference.
     *
     * <p>Replacing the reference (the old behaviour of the UPDATE path) made the cascade persist
     * a SECOND transient {@code user_profile} row with the same user_id/nickname, which hits the
     * Flyway V15 unique constraint {@code uk_user_profile_nickname} on the real {@code validate}
     * profile (HTTP 500). The {@code create-drop} test schema lacks that constraint, so the
     * double-insert was silent in tests — see {@code reference_ddl_auto_create_drop_hides_migration_drift}.
     *
     * <p>This mirrors the canonical real-member avatar path
     * ({@code UserAvatarCommandService.setUserAvatar}): it calls the member's mutation delegates
     * on the existing profile, keeping the SAME id (an UPDATE, not an INSERT).
     *
     * @param member        existing virtual member (its profile is mutated in place)
     * @param avatarBodyUri optional body URI (default if null)
     * @param avatarFaceUri optional face URI (empty/SINGLE_BODY if null)
     */
    public void applyAvatarToExistingMember(
            MemberData member,
            AvatarBodyUri avatarBodyUri,
            AvatarFaceUri avatarFaceUri) {

        ResolvedAvatar resolved = resolveAvatar(avatarBodyUri, avatarFaceUri);

        member.updateAvatarBody(
                resolved.bodyUri(), resolved.combinePositionX(), resolved.combinePositionY());

        if (resolved.compositionType() == AvatarCompositionType.SINGLE_BODY) {
            member.updateAvatarFace(resolved.faceUri());   // SINGLE_BODY + empty face
        } else {
            member.updateAvatarFace(resolved.faceUri(), resolved.faceSourceType(), 0, 0, 1.0);
        }
        member.updateAvatarIcon(resolved.iconUri());
    }

    /**
     * Resolved avatar attributes (composition/face-source/icon/position) derived from a
     * body+face pair. Shared by the CREATE path (builds a new ProfileData) and the UPDATE path
     * (mutates an existing one) so the combinable/NFT/icon rules live in ONE place.
     */
    private record ResolvedAvatar(
            AvatarCompositionType compositionType,
            FaceSourceType faceSourceType,
            AvatarBodyUri bodyUri,
            AvatarFaceUri faceUri,
            AvatarIconUri iconUri,
            int combinePositionX,
            int combinePositionY) {}

    private ResolvedAvatar resolveAvatar(AvatarBodyUri avatarBodyUri, AvatarFaceUri avatarFaceUri) {
        // Get avatar URIs (use provided or default)
        AvatarBodyUri finalBodyUri = (avatarBodyUri != null)
                ? avatarBodyUri
                : getDefaultAvatarBodyUri();

        AvatarFaceUri finalFaceUri = (avatarFaceUri != null)
                ? avatarFaceUri
                : new AvatarFaceUri();  // Empty for SINGLE_BODY type

        // Get avatar body info for position values
        AvatarBodyDto avatarBodyDto = adminAvatarResourcePort.findAvatarBodyByUri(finalBodyUri);

        // Auto-detect composition type and face source type from face URI pattern
        AvatarCompositionType compositionType;
        FaceSourceType faceSourceType;

        if (finalFaceUri.getValue() != null && finalFaceUri.getValue().contains("ava_nft_tmp")) {
            // NFT face pattern detected -> BODY_WITH_FACE
            compositionType = AvatarCompositionType.BODY_WITH_FACE;
            faceSourceType = FaceSourceType.NFT_URI;
        } else if (finalFaceUri.getValue() == null || finalFaceUri.getValue().isEmpty()) {
            // Empty face URI -> SINGLE_BODY
            compositionType = AvatarCompositionType.SINGLE_BODY;
            faceSourceType = FaceSourceType.INTERNAL_IMAGE;
        } else {
            // Internal face image from DB -> BODY_WITH_FACE
            compositionType = AvatarCompositionType.BODY_WITH_FACE;
            faceSourceType = FaceSourceType.INTERNAL_IMAGE;
        }

        // Determine icon based on composition type
        AvatarIconUri iconUri;
        if (compositionType == AvatarCompositionType.SINGLE_BODY) {
            // SINGLE_BODY: Use body-paired icon
            iconUri = adminAvatarResourcePort.findAvatarIconPairWithSingleBody(avatarBodyDto);
        } else if (faceSourceType == FaceSourceType.NFT_URI) {
            // BODY_WITH_FACE with NFT: NFT face URI becomes icon URI
            iconUri = new AvatarIconUri(finalFaceUri.getValue());
        } else {
            // BODY_WITH_FACE with INTERNAL_IMAGE: Use face-paired icon
            iconUri = new AvatarIconUri(
                    adminAvatarResourcePort.findPairAvatarIconByFaceUri(finalFaceUri).resourceUri()
            );
        }

        return new ResolvedAvatar(
                compositionType, faceSourceType, finalBodyUri, finalFaceUri, iconUri,
                avatarBodyDto.getCombinePositionX(), avatarBodyDto.getCombinePositionY());
    }

    /**
     * Generate random nickname for virtual member
     * Pattern: Virtual_{6-char-hex}
     *
     * @return Generated nickname
     */
    private String generateRandomNickname() {
        String randomHex = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 6)
                .toUpperCase();

        return "Virtual_" + randomHex;
    }

    /**
     * Get default avatar body URI
     * Uses the first available avatar body resource
     *
     * @return Default avatar body URI
     */
    private AvatarBodyUri getDefaultAvatarBodyUri() {
        return new AvatarBodyUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media");
    }
}
