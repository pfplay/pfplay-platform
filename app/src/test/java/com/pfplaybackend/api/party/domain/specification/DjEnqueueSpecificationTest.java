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

class DjEnqueueSpecificationTest {

    private DjEnqueueSpecification spec;

    @BeforeEach
    void setUp() {
        spec = new DjEnqueueSpecification();
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
    @DisplayName("정상 DJ 등록 — 예외 없음")
    void validEnqueue() {
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
    @DisplayName("타인 소유 playlist — NOT_OWNED_PLAYLIST (DJ-005, 신규)")
    void notOwnedThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, false, false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("빈 플레이리스트 — EMPTY_PLAYLIST (DJ-003)")
    void emptyPlaylist() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, true, true))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("이미 등록된 DJ — ALREADY_REGISTERED (DJ-001)")
    void alreadyRegistered() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, true, false))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("평가 순서 잠금 — already-registered + not-owned 동시: NOT_OWNED_PLAYLIST 가 먼저 (보안 우선)")
    void ownershipBeatsAlreadyRegistered() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, false, false))
                .isInstanceOf(ForbiddenException.class);
    }
}
