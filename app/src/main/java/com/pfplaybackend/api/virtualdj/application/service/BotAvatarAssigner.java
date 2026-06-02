package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.virtualdj.application.port.BotAvatarApplyPort;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import com.pfplaybackend.api.virtualdj.domain.exception.VirtualDjException;
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
            throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
        }
        String bodyUri = bodies.get(randomizer.nextIndex(bodies.size())).getResourceUri();
        assignOne(botUserId, bodyUri);
    }

    /** 단일 봇에 지정 바디 적용(개별 셋팅). 합성 규칙 적용. */
    public void assignOne(UserId botUserId, String bodyUri) {
        AvatarBodyDto body = catalog.findBodyByUri(bodyUri)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET));
        AvatarFaceUri faceUri = body.isCombinable()
                ? new AvatarFaceUri(defaultFaceUri())
                : new AvatarFaceUri();   // 빈 face = SINGLE_BODY
        applyPort.apply(botUserId, new AvatarBodyUri(bodyUri), faceUri);
    }

    /** 셋({@code bodyUris})에서 봇별 랜덤 1개를 배분(일괄). */
    public List<Assigned> distribute(List<UserId> botIds, List<String> bodyUris) {
        if (bodyUris == null || bodyUris.isEmpty() || botIds == null || botIds.isEmpty()) {
            throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
        }
        // 셋 무결성: 모든 bodyUri 가 카탈로그에 존재해야 한다.
        for (String uri : bodyUris) {
            if (catalog.findBodyByUri(uri).isEmpty()) {
                throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
            }
        }
        // 부분 성공(spec §2.3): 비-봇/미존재 userId 는 apply 단계에서 예외가 나도 배치 전체를 중단하지 않고
        // 해당 봇만 건너뛴다. 셋 무결성(위 검증)은 강제하되, 대상 목록의 오염은 격리한다.
        List<Assigned> assigned = new ArrayList<>();
        for (UserId botId : botIds) {
            String chosen = bodyUris.get(randomizer.nextIndex(bodyUris.size()));
            try {
                assignOne(botId, chosen);
                assigned.add(new Assigned(botId.getUid(), chosen));
            } catch (RuntimeException e) {
                log.warn("[BotAvatarAssigner.distribute] 봇 적용 실패로 건너뜀 userId={} bodyUri={} cause={}",
                        botId.getUid(), chosen, e.toString());
            }
        }
        log.info("[BotAvatarAssigner.distribute] bots={} setSize={} applied={}",
                botIds.size(), bodyUris.size(), assigned.size());
        return assigned;
    }

    private String defaultFaceUri() {
        List<AvatarFaceDto> faces = catalog.findPublishedFaces();
        if (faces.isEmpty()) {
            throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
        }
        return faces.get(0).resourceUri();   // 단일 basic face (AvatarFaceDto 접근자에 맞춰 조정)
    }
}
