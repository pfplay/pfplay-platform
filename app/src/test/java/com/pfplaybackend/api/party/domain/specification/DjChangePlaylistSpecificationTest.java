package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DjChangePlaylistSpecificationTest {

    private DjChangePlaylistSpecification spec;

    @BeforeEach
    void setUp() {
        spec = new DjChangePlaylistSpecification();
    }

    private DjQueueData openQueue() {
        return DjQueueData.createFor(new PartyroomId(1L));
    }

    private DjQueueData closedQueue() {
        DjQueueData queue = DjQueueData.createFor(new PartyroomId(1L));
        queue.close();
        return queue;
    }

    @Test
    @DisplayName("정상 변경 — 예외 없음 (queue open, not current, owned, non-empty)")
    void validChange() {
        assertThatNoException().isThrownBy(() ->
                spec.validate(openQueue(), false, true, false));
    }

    @Test
    @DisplayName("큐 닫힘 — QUEUE_CLOSED (DJ-002)")
    void queueClosed() {
        assertThatThrownBy(() -> spec.validate(closedQueue(), false, true, false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("재생 중 DJ — CURRENT_DJ_CANNOT_CHANGE_PLAYLIST (DJ-006)")
    void currentDjThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, true, false))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("타인 소유 playlist — NOT_OWNED_PLAYLIST (DJ-005)")
    void notOwnedThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, false, false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("빈 playlist — EMPTY_PLAYLIST (DJ-003)")
    void emptyPlaylistThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, true, true))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("평가 순서 잠금 — currentDj + not-owned 동시: CURRENT_DJ_CANNOT_CHANGE_PLAYLIST 가 먼저(ConflictException)")
    void currentDjBeatsOwnership() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, false, true))
                .isInstanceOf(ConflictException.class);
    }
}
