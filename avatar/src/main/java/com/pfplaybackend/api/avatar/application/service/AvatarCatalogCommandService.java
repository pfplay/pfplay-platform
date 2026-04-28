package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.storage.AvatarStorageProperties;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarFaceView;
import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarBodyCommand;
import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarFaceCommand;
import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarBodyCommand;
import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarFaceCommand;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogCommandUseCase;
import com.pfplaybackend.api.avatar.application.port.out.AvatarStoragePort;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourcePublished;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceRetired;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 어드민 아바타 카탈로그 명령 서비스. Spec §6.I-2~I-5.
 *
 * <p>업로드 흐름은 백엔드 프록시 — bytes를 GCS에 직접 PUT 후 URI를 DB에 저장.
 * 실패 시 이미 업로드된 GCS 객체는 best-effort delete (§6.I-2 처리 순서).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AvatarCatalogCommandService implements AvatarCatalogCommandUseCase {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AvatarBodyResourceRepository bodyRepo;
    private final AvatarFaceResourceRepository faceRepo;
    private final AvatarStoragePort storagePort;
    private final AvatarStorageProperties storageProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();

    // ---------------------------------------------------------------- create

    @Override
    public AdminAvatarBodyView createBody(CreateAvatarBodyCommand cmd) {
        validateContent(cmd.bodyImage(), cmd.bodyContentType());
        if (cmd.iconImage() != null) {
            validateContent(cmd.iconImage(), cmd.iconContentType());
        }
        if (bodyRepo.existsByName(cmd.name())) {
            throw ExceptionCreator.create(AvatarException.AVATAR_NAME_ALREADY_EXISTS);
        }
        // Body create는 isDefaultSetting=false로만 — DRAFT는 PUBLISHED 아님 (§6.I-3 invariant)
        if (cmd.isDefaultSetting()) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_DEFAULT_SETTING);
        }
        if (cmd.obtainableType() == com.pfplaybackend.api.avatar.domain.enums.ObtainmentType.BASIC
                && cmd.obtainableScore() != 0) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_DEFAULT_SETTING);
        }

        String bodyUri = uploadOrThrow("ava_body", cmd.bodyImage(), cmd.bodyContentType());
        String iconUri = null;
        try {
            if (cmd.iconImage() != null) {
                iconUri = uploadOrThrow("ava_icon", cmd.iconImage(), cmd.iconContentType());
            }
            AvatarBodyResourceData data = AvatarBodyResourceData.draft(
                    cmd.name(), bodyUri, iconUri,
                    cmd.obtainableType(), cmd.obtainableScore(),
                    cmd.isCombinable(), cmd.isDefaultSetting(),
                    cmd.combinePositionX(), cmd.combinePositionY(),
                    cmd.administratorId());
            return AdminAvatarBodyView.from(bodyRepo.save(data));
        } catch (RuntimeException e) {
            // 5단계 실패 — 이미 올라간 GCS 객체 즉시 정리
            storagePort.deleteByPublicUrl(bodyUri);
            if (iconUri != null) {
                storagePort.deleteByPublicUrl(iconUri);
            }
            throw e;
        }
    }

    @Override
    public AdminAvatarFaceView createFace(CreateAvatarFaceCommand cmd) {
        validateContent(cmd.faceImage(), cmd.faceContentType());
        if (cmd.iconImage() != null) {
            validateContent(cmd.iconImage(), cmd.iconContentType());
        }
        if (faceRepo.existsByName(cmd.name())) {
            throw ExceptionCreator.create(AvatarException.AVATAR_NAME_ALREADY_EXISTS);
        }

        String faceUri = uploadOrThrow("ava_face", cmd.faceImage(), cmd.faceContentType());
        String iconUri = null;
        try {
            if (cmd.iconImage() != null) {
                iconUri = uploadOrThrow("ava_icon", cmd.iconImage(), cmd.iconContentType());
            }
            AvatarFaceResourceData data = AvatarFaceResourceData.draft(
                    cmd.name(), faceUri, iconUri, cmd.administratorId());
            return AdminAvatarFaceView.from(faceRepo.save(data));
        } catch (RuntimeException e) {
            storagePort.deleteByPublicUrl(faceUri);
            if (iconUri != null) {
                storagePort.deleteByPublicUrl(iconUri);
            }
            throw e;
        }
    }

    // ----------------------------------------------------------------- patch

    @Override
    public AdminAvatarBodyView patchBody(PatchAvatarBodyCommand cmd) {
        AvatarBodyResourceData body = loadBody(cmd.resourceId());
        // 메타데이터는 항상 업데이트 (DRAFT/PUBLISHED 모두 허용 — entity가 RETIRED 거부)
        body.updateMetadata(
                cmd.obtainableType(), cmd.obtainableScore(),
                cmd.isCombinable(), cmd.isDefaultSetting(),
                cmd.combinePositionX(), cmd.combinePositionY(),
                cmd.administratorId());

        // 이미지는 제공된 경우에만 (DRAFT-only 제약은 entity가 검증)
        if (cmd.bodyImage() != null) {
            validateContent(cmd.bodyImage(), cmd.bodyContentType());
            String oldUri = body.getResourceUri();
            String newUri = uploadOrThrow("ava_body", cmd.bodyImage(), cmd.bodyContentType());
            try {
                body.replaceResourceUri(newUri, cmd.administratorId());
            } catch (RuntimeException e) {
                storagePort.deleteByPublicUrl(newUri);
                throw e;
            }
            storagePort.deleteByPublicUrl(oldUri);
        }
        if (cmd.iconImage() != null) {
            validateContent(cmd.iconImage(), cmd.iconContentType());
            String oldIcon = body.getIconUri();
            String newIcon = uploadOrThrow("ava_icon", cmd.iconImage(), cmd.iconContentType());
            try {
                body.replaceIconUri(newIcon, cmd.administratorId());
            } catch (RuntimeException e) {
                storagePort.deleteByPublicUrl(newIcon);
                throw e;
            }
            if (oldIcon != null) {
                storagePort.deleteByPublicUrl(oldIcon);
            }
        }
        return AdminAvatarBodyView.from(body);
    }

    @Override
    public AdminAvatarFaceView patchFace(PatchAvatarFaceCommand cmd) {
        AvatarFaceResourceData face = loadFace(cmd.resourceId());

        if (cmd.faceImage() != null) {
            validateContent(cmd.faceImage(), cmd.faceContentType());
            String oldUri = face.getResourceUri();
            String newUri = uploadOrThrow("ava_face", cmd.faceImage(), cmd.faceContentType());
            try {
                face.replaceResourceUri(newUri, cmd.administratorId());
            } catch (RuntimeException e) {
                storagePort.deleteByPublicUrl(newUri);
                throw e;
            }
            storagePort.deleteByPublicUrl(oldUri);
        }
        if (cmd.iconImage() != null) {
            validateContent(cmd.iconImage(), cmd.iconContentType());
            String oldIcon = face.getIconUri();
            String newIcon = uploadOrThrow("ava_icon", cmd.iconImage(), cmd.iconContentType());
            try {
                face.replaceIconUri(newIcon, cmd.administratorId());
            } catch (RuntimeException e) {
                storagePort.deleteByPublicUrl(newIcon);
                throw e;
            }
            if (oldIcon != null) {
                storagePort.deleteByPublicUrl(oldIcon);
            }
        }
        return AdminAvatarFaceView.from(face);
    }

    // -------------------------------------------------- icon-only re-upload

    @Override
    public void replaceBodyIcon(Long resourceId, byte[] bytes, String contentType, Long adminId) {
        validateContent(bytes, contentType);
        AvatarBodyResourceData body = loadBody(resourceId);
        String oldIcon = body.getIconUri();
        String newIcon = uploadOrThrow("ava_icon", bytes, contentType);
        try {
            body.replaceIconUri(newIcon, adminId);
        } catch (RuntimeException e) {
            storagePort.deleteByPublicUrl(newIcon);
            throw e;
        }
        if (oldIcon != null) {
            storagePort.deleteByPublicUrl(oldIcon);
        }
    }

    @Override
    public void replaceFaceIcon(Long resourceId, byte[] bytes, String contentType, Long adminId) {
        validateContent(bytes, contentType);
        AvatarFaceResourceData face = loadFace(resourceId);
        String oldIcon = face.getIconUri();
        String newIcon = uploadOrThrow("ava_icon", bytes, contentType);
        try {
            face.replaceIconUri(newIcon, adminId);
        } catch (RuntimeException e) {
            storagePort.deleteByPublicUrl(newIcon);
            throw e;
        }
        if (oldIcon != null) {
            storagePort.deleteByPublicUrl(oldIcon);
        }
    }

    // -------------------------------------------- 라이프사이클 전이 + 이벤트

    @Override
    public void publishBody(Long resourceId, Long adminId) {
        AvatarBodyResourceData body = loadBody(resourceId);
        body.publish(adminId);
        eventPublisher.publishEvent(new AvatarResourcePublished(
                AvatarResourceType.AVATAR_BODY, body.getId(), body.getResourceUri(), adminId));
    }

    @Override
    public void publishFace(Long resourceId, Long adminId) {
        AvatarFaceResourceData face = loadFace(resourceId);
        face.publish(adminId);
        eventPublisher.publishEvent(new AvatarResourcePublished(
                AvatarResourceType.AVATAR_FACE, face.getId(), face.getResourceUri(), adminId));
    }

    @Override
    public void retireBody(Long resourceId, String reason, Long adminId) {
        AvatarBodyResourceData body = loadBody(resourceId);
        body.retire(adminId);
        eventPublisher.publishEvent(new AvatarResourceRetired(
                AvatarResourceType.AVATAR_BODY, body.getId(), reason, adminId));
    }

    @Override
    public void retireFace(Long resourceId, String reason, Long adminId) {
        AvatarFaceResourceData face = loadFace(resourceId);
        face.retire(adminId);
        eventPublisher.publishEvent(new AvatarResourceRetired(
                AvatarResourceType.AVATAR_FACE, face.getId(), reason, adminId));
    }

    // -------------------------------------------------------------- internal

    private AvatarBodyResourceData loadBody(Long id) {
        return bodyRepo.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AvatarException.AVATAR_RESOURCE_NOT_FOUND));
    }

    private AvatarFaceResourceData loadFace(Long id) {
        return faceRepo.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AvatarException.AVATAR_RESOURCE_NOT_FOUND));
    }

    private void validateContent(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_FILE_FORMAT);
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_FILE_FORMAT);
        }
        if (bytes.length > storageProperties.getMaxFileSizeBytes()) {
            throw ExceptionCreator.create(AvatarException.AVATAR_FILE_TOO_LARGE);
        }
    }

    private String uploadOrThrow(String category, byte[] bytes, String contentType) {
        String filename = generateFilename(contentType);
        return storagePort.upload(category, filename, contentType, bytes);
    }

    private String generateFilename(String contentType) {
        String ext = "image/jpeg".equalsIgnoreCase(contentType) ? "jpg" : "png";
        byte[] rand = new byte[6];
        random.nextBytes(rand);
        StringBuilder sb = new StringBuilder();
        for (byte b : rand) sb.append(String.format("%02x", b));
        return LocalDate.now().format(DATE_FMT) + "_" + sb + "." + ext;
    }
}
