package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartyroomDataTest {

    private PartyroomData newPartyroom() {
        return PartyroomData.create(
                "Test Room", "intro",
                LinkDomain.of("youtube.com"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(1L)
        );
    }

    @Nested
    @DisplayName("팩토리 — 신규 생성 시 기본 상태")
    class FactoryDefaults {
        @Test
        @DisplayName("status=ACTIVE, displayFlag=NORMAL, crewCount=0, lastActivityAt=null")
        void defaults() {
            PartyroomData p = newPartyroom();
            assertThat(p.getStatus()).isEqualTo(PartyroomStatus.ACTIVE);
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
            assertThat(p.getCrewCount()).isZero();
            assertThat(p.getLastActivityAt()).isNull();
            assertThat(p.isActive()).isTrue();
            assertThat(p.isSuspended()).isFalse();
            assertThat(p.isTerminated()).isFalse();
            assertThat(p.getNoticeContent()).isEmpty();
            assertThat(p.getTitle()).isEqualTo("Test Room");
            assertThat(p.getStageType()).isEqualTo(StageType.GENERAL);
        }
    }

    @Nested
    @DisplayName("suspend()")
    class Suspend {
        @Test @DisplayName("ACTIVE → SUSPENDED 성공")
        void fromActive() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThat(p.getStatus()).isEqualTo(PartyroomStatus.SUSPENDED);
            assertThat(p.isSuspended()).isTrue();
            assertThat(p.isActive()).isFalse();
        }

        @Test @DisplayName("이미 SUSPENDED → ConflictException")
        void fromSuspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThatThrownBy(p::suspend).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("TERMINATED → ConflictException")
        void fromTerminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::suspend).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("restore()")
    class Restore {
        @Test @DisplayName("SUSPENDED → ACTIVE 성공")
        void fromSuspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            p.restore();
            assertThat(p.isActive()).isTrue();
        }

        @Test @DisplayName("ACTIVE에서 호출 → ConflictException")
        void fromActive() {
            PartyroomData p = newPartyroom();
            assertThatThrownBy(p::restore).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("TERMINATED → ConflictException")
        void fromTerminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::restore).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("terminate()")
    class Terminate {
        @Test @DisplayName("ACTIVE → TERMINATED 성공")
        void fromActive() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThat(p.isTerminated()).isTrue();
        }

        @Test @DisplayName("SUSPENDED → TERMINATED 성공")
        void fromSuspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            p.terminate();
            assertThat(p.isTerminated()).isTrue();
        }

        @Test @DisplayName("이중 terminate → ConflictException (TERMINATED는 terminal)")
        void fromTerminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::terminate).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("종료 시 PartyroomClosedEvent가 도메인 이벤트로 등록된다")
        void registersPartyroomClosedEvent() {
            PartyroomData p = newPartyroom();
            p.terminate();
            List<DomainEvent> events = p.pollDomainEvents();
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(PartyroomClosedEvent.class);
            PartyroomClosedEvent event = (PartyroomClosedEvent) events.get(0);
            assertThat(event.getHostId()).isEqualTo(new UserId(1L));
            assertThat(event.getTitle()).isEqualTo("Test Room");
        }
    }

    @Nested
    @DisplayName("pollDomainEvents()")
    class PollDomainEvents {
        @Test @DisplayName("호출 후 이벤트 목록이 비워진다")
        void clearsAfterPoll() {
            PartyroomData p = newPartyroom();
            p.terminate();
            p.pollDomainEvents();
            assertThat(p.pollDomainEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("validateHost()")
    class ValidateHost {
        @Test @DisplayName("호스트가 아닌 사용자 → ForbiddenException")
        void notHost() {
            PartyroomData p = newPartyroom();
            assertThatThrownBy(() -> p.validateHost(new UserId(999L)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test @DisplayName("호스트 본인 → 예외 없음")
        void host() {
            PartyroomData p = newPartyroom();
            assertThatNoException().isThrownBy(() -> p.validateHost(p.getHostId()));
        }
    }

    @Nested
    @DisplayName("validateNotTerminated() — 기존 시맨틱 유지 (TERMINATED만 거부)")
    class ValidateNotTerminated {
        @Test @DisplayName("ACTIVE 통과")
        void active() {
            assertThatNoException().isThrownBy(() -> newPartyroom().validateNotTerminated());
        }

        @Test @DisplayName("SUSPENDED도 통과 (TERMINATED만 거부)")
        void suspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThatNoException().isThrownBy(p::validateNotTerminated);
        }

        @Test @DisplayName("TERMINATED → ForbiddenException (기존 ALREADY_TERMINATED 코드)")
        void terminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::validateNotTerminated).isInstanceOf(ForbiddenException.class);
        }
    }
}
