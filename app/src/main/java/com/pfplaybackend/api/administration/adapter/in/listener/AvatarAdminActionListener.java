package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourcePublished;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceRetired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 아바타 publish/retire 감사 listener — {@link PartyroomAdminActionListener}와 동일한
 * sync atomic 패턴 (PR 8 결정). 누락 방지를 위해 listener INSERT 실패 시 caller TX rollback.
 *
 * <p>아바타 이벤트는 partyroom과 무관하므로 {@code partyroom_id=null}로 적재한다.
 * V7 스키마에서 해당 컬럼은 NULL 허용 (Spec §6.3 / V7 DDL).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AvatarAdminActionListener {

    private final PartyroomAdminActionRepository adminActionRepository;

    @EventListener
    public void on(AvatarResourcePublished event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.PUBLISH_AVATAR_RESOURCE,
                AdminActionTargetType.valueOf(event.getResourceType().name()),
                event.getResourceId(),
                null,                              // 아바타는 partyroom 무관
                null,                              // publish는 reason 없음
                JsonMetadata.of(Map.of(
                        "resource_uri", event.getResourceUri()
                )),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(AvatarResourceRetired event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.RETIRE_AVATAR_RESOURCE,
                AdminActionTargetType.valueOf(event.getResourceType().name()),
                event.getResourceId(),
                null,
                event.getReason(),
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    private void save(PartyroomAdminActionData action) {
        try {
            adminActionRepository.save(action);
        } catch (Exception e) {
            log.error("[AvatarAdminActionListener] admin_action INSERT 실패 — caller TX rollback. " +
                      "actionType={}, administratorId={}, targetType={}, targetId={}",
                      action.getActionType(), action.getAdministratorId(),
                      action.getTargetType(), action.getTargetId(), e);
            throw e;
        }
    }
}
