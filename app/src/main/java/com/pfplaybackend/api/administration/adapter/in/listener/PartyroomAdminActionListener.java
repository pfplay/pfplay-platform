package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.party.domain.event.PartyroomDisplayFlagChangedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomMetaUpdatedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomRestoredEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomSuspendedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Admin 액션의 atomic audit listener.
 *
 * - @EventListener (NOT @TransactionalEventListener) — synchronous + same TX
 * - listener INSERT 실패 시 ERROR + rethrow → caller TX rollback (Q2 atomic 보장)
 * - administratorId는 이벤트 페이로드에서 — SecurityContext 의존 없음
 *
 * PR 7 PartyroomCounterListener와 phase 다름 (의도적 분기):
 * counter는 side-effect (AFTER_COMMIT, swallow); audit는 parallel record (sync, rethrow).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartyroomAdminActionListener {

    private final PartyroomAdminActionRepository adminActionRepository;

    @EventListener
    public void on(PartyroomTerminatedEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.TERMINATE_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                event.getReason(),
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomSuspendedEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.SUSPEND_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                event.getReason(),
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomRestoredEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.RESTORE_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                null,
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomMetaUpdatedEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.UPDATE_PARTYROOM_META,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                null,
                JsonMetadata.of(Map.of("changes", event.getDiff())),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomDisplayFlagChangedEvent event) {
        PartyroomAdminActionType type = switch (event.getNewFlag()) {
            case FEATURED -> PartyroomAdminActionType.SET_FEATURED;
            case HIDDEN   -> PartyroomAdminActionType.SET_HIDDEN;
            case NORMAL   -> PartyroomAdminActionType.SET_NORMAL;
        };
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(), type,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                null,
                JsonMetadata.of(Map.of(
                        "old_flag", event.getOldFlag().name(),
                        "new_flag", event.getNewFlag().name()
                )),
                event.getOccurredAt()
        ));
    }

    private void save(PartyroomAdminActionData action) {
        try {
            adminActionRepository.save(action);
        } catch (Exception e) {
            log.error("[PartyroomAdminActionListener] Failed to insert admin_action — caller TX will rollback. " +
                      "actionType={}, administratorId={}, partyroomId={}",
                      action.getActionType(), action.getAdministratorId(), action.getPartyroomId(), e);
            throw e;
        }
    }
}
