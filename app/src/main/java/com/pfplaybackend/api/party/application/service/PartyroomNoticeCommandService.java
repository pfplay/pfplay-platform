package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.command.UpdatePartyroomNoticeCommand;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.PartyroomNoticeUpdatedEvent;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartyroomNoticeCommandService {

    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomQueryService partyroomQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void updateNotice(PartyroomId partyroomId, UpdatePartyroomNoticeCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        partyroom.validateNotTerminated();
        CrewData updater = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        if (updater.isBelowGrade(GradeType.COMMUNITY_MANAGER)) {
            throw ExceptionCreator.create(GradeException.MANAGER_GRADE_REQUIRED);
        }

        String previousContent = partyroom.getNoticeContent();
        partyroom.updateNotice(command.content());
        if (command.content().equals(previousContent)) {
            return;
        }

        aggregatePort.savePartyroom(partyroom);
        eventPublisher.publishEvent(new PartyroomNoticeUpdatedEvent(partyroomId, partyroom.getNoticeContent()));
    }
}
