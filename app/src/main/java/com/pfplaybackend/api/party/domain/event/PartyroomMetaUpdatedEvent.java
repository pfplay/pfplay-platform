package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

import java.util.Map;

/**
 * Admin이 partyroom 메타 정보(title/introduction/playbackTimeLimit)를 변경한 이벤트.
 *
 * diff wire format: {"title": {"old": "A", "new": "B"}, "introduction": {"old": "...", "new": "..."}}
 * inner Map의 key는 literal "old" / "new" 문자열. (Java 키워드 'new' 충돌 회피 — record 안 쓰고 Map<String, Object>.)
 * publisher가 호출 전에 직접 Map 구성: Map.of("old", oldValue, "new", newValue)
 */
@Getter
public class PartyroomMetaUpdatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final Map<String, Map<String, Object>> diff;

    public PartyroomMetaUpdatedEvent(PartyroomId partyroomId, Long administratorId,
                                     Map<String, Map<String, Object>> diff) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.diff = diff;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
