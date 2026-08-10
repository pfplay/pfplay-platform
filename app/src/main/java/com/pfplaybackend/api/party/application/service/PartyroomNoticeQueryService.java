package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class PartyroomNoticeQueryService {

    private final PartyroomAggregatePort aggregatePort;

    @Transactional(readOnly = true)
    public String getNotice(PartyroomId partyroomId) {
        PartyroomData partyroomData = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        return partyroomData.getNoticeContent();
    }
}
