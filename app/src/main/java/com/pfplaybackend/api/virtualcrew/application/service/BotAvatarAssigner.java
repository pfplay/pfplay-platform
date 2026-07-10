package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.virtualcrew.application.port.BotAvatarApplyPort;
import com.pfplaybackend.api.virtualcrew.application.port.Randomizer;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 봇 아바타 변별 배분의 단일 소스 — 합성 규칙(combinable→공통 face / standalone→자체 아이콘)을
 * 여기 한 곳에만 두고, 개별 적용 / 셋 랜덤 배분 / provision 자동부여가 모두 이 헬퍼를 거친다.
 *
 * <p>합성 규칙(spec §1.2/§1.3): combinable 바디는 icon_uri 가 NULL 이라 face 없이는 아이콘이 깨진다.
 * 따라서 기본 basic face 를 합성해 BODY_WITH_FACE(face 페어 아이콘)로 만들고, standalone 바디는
 * face="" 로 SINGLE_BODY(자체 아이콘)로 둔다. 합성/아이콘의 실제 결정은 admin 쪽 update 로직이 수행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotAvatarAssigner {

    private final AvatarCatalogQueryUseCase catalog;
    private final BotAvatarApplyPort applyPort;
    private final Randomizer randomizer;

    public record Assigned(Long userId, String avatarBodyUri) {}

    /** 전체 published 카탈로그에서 랜덤 1개를 골라 적용(provision 자동부여 + 단발 변별용). */
    public void assignRandomFromCatalog(UserId botUserId) {
        List<AvatarBodyDto> bodies = catalog.findPublishedBodies();
        if (bodies.isEmpty()) {
            throw ExceptionCreator.create(VirtualCrewException.INVALID_AVATAR_SET);
        }
        String bodyUri = bodies.get(randomizer.nextIndex(bodies.size())).getResourceUri();
        assignOne(botUserId, bodyUri);
    }

    /** 단일 봇에 지정 바디 적용(개별 셋팅). 합성 규칙 적용. */
    public void assignOne(UserId botUserId, String bodyUri) {
        AvatarBodyDto body = catalog.findBodyByUri(bodyUri)
                .orElseThrow(() -> ExceptionCreator.create(VirtualCrewException.INVALID_AVATAR_SET));
        AvatarFaceUri faceUri = body.isCombinable()
                ? new AvatarFaceUri(defaultFaceUri())
                : new AvatarFaceUri();   // 빈 face = SINGLE_BODY
        applyPort.apply(botUserId, new AvatarBodyUri(bodyUri), faceUri);
    }

    /** 셋({@code bodyUris})에서 봇별 랜덤 1개를 배분(일괄). */
    public List<Assigned> distribute(List<UserId> botIds, List<String> bodyUris) {
        if (bodyUris == null || bodyUris.isEmpty() || botIds == null || botIds.isEmpty()) {
            throw ExceptionCreator.create(VirtualCrewException.INVALID_AVATAR_SET);
        }
        // 셋 무결성: 모든 bodyUri 가 카탈로그에 존재해야 한다.
        for (String uri : bodyUris) {
            if (catalog.findBodyByUri(uri).isEmpty()) {
                throw ExceptionCreator.create(VirtualCrewException.INVALID_AVATAR_SET);
            }
        }
        // 부분 성공(spec §2.3)의 "비-봇/미존재 격리"는 호출자(BotAvatarAdminService)가 사전 필터로 처리한다.
        // 여기서는 넘어온 봇이 모두 유효하다고 보고 전수 적용한다. apply 가 예외를 던지면(진짜 버그/인프라 장애)
        // 공유 트랜잭션이 어차피 rollback-only 가 되므로 삼키지 않고 그대로 전파해 배치를 실패시킨다.
        List<Assigned> assigned = new ArrayList<>();
        for (UserId botId : botIds) {
            String chosen = bodyUris.get(randomizer.nextIndex(bodyUris.size()));
            assignOne(botId, chosen);
            assigned.add(new Assigned(botId.getUid(), chosen));
        }
        log.info("[BotAvatarAssigner.distribute] bots={} setSize={} applied={}",
                botIds.size(), bodyUris.size(), assigned.size());
        return assigned;
    }

    private String defaultFaceUri() {
        List<AvatarFaceDto> faces = catalog.findPublishedFaces();
        if (faces.isEmpty()) {
            throw ExceptionCreator.create(VirtualCrewException.INVALID_AVATAR_SET);
        }
        return faces.get(0).resourceUri();   // 단일 basic face (AvatarFaceDto 접근자에 맞춰 조정)
    }
}
