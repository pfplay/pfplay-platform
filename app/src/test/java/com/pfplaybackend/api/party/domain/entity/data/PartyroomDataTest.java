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
    @DisplayName("setDisplayFlagFeatured/Hidden/Normal()")
    class SetDisplayFlag {
        @Test @DisplayName("ACTIVE 룸 — FEATURED 설정")
        void featured_active() {
            PartyroomData p = newPartyroom();
            p.setDisplayFlagFeatured();
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.FEATURED);
        }

        @Test @DisplayName("SUSPENDED 룸 — FEATURED 설정 가능 (운영 정책)")
        void featured_suspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            p.setDisplayFlagFeatured();
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.FEATURED);
        }

        @Test @DisplayName("TERMINATED 룸 — ConflictException")
        void featured_terminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::setDisplayFlagFeatured).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("HIDDEN 설정")
        void hidden() {
            PartyroomData p = newPartyroom();
            p.setDisplayFlagHidden();
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.HIDDEN);
        }

        @Test @DisplayName("NORMAL 설정")
        void normal() {
            PartyroomData p = newPartyroom();
            p.setDisplayFlagFeatured();
            p.setDisplayFlagNormal();
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
        }

        @Test @DisplayName("이미 같은 flag — 변경 없이 통과 (idempotent)")
        void idempotent() {
            PartyroomData p = newPartyroom();
            assertThatNoException().isThrownBy(p::setDisplayFlagNormal);
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
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
    @DisplayName("isReportable() — 신고 가능 여부 (PR 13 G2)")
    class IsReportable {
        @Test @DisplayName("ACTIVE → 신고 가능 (true)")
        void active() {
            assertThat(newPartyroom().isReportable()).isTrue();
        }

        @Test @DisplayName("SUSPENDED → 신고 불가 (false)")
        void suspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThat(p.isReportable()).isFalse();
        }

        @Test @DisplayName("TERMINATED → 신고 불가 (false)")
        void terminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThat(p.isReportable()).isFalse();
        }
    }

    @Nested
    @DisplayName("validateNotMainStage() — MAIN stage 보호 가드 (#280 root-cause fix)")
    class ValidateNotMainStage {
        // 모든 termination 경로 (host self-delete / cron / admin) 가 MAIN 까지 잡지 않도록
        // 도메인 측 가드. 일반 파티룸은 그대로 통과.

        private PartyroomData mainStage() {
            return PartyroomData.builder()
                    .id(1L)
                    .hostId(new UserId(1L))
                    .stageType(StageType.MAIN)
                    .title("Main Stage")
                    .introduction("Welcome")
                    .linkDomain(LinkDomain.of("main"))
                    .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                    .noticeContent("")
                    .status(PartyroomStatus.ACTIVE)
                    .build();
        }

        @Test @DisplayName("MAIN stage → ConflictException (MAIN_STAGE_PROTECTED)")
        void rejectsMainStage() {
            PartyroomData p = mainStage();
            assertThatThrownBy(p::validateNotMainStage).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("GENERAL stage → 통과 (예외 없음)")
        void acceptsGeneralStage() {
            assertThatNoException().isThrownBy(() -> newPartyroom().validateNotMainStage());
        }
    }

    @Nested
    @DisplayName("reactivateAsMainStage() — MAIN 시스템 stage 전용 lifecycle 복원 (#280 안전망)")
    class ReactivateAsMainStage {
        // 일반 파티룸은 TERMINATED 가 terminal invariant 이지만, MAIN 은 시스템 stage 라
        // ApplicationReadyEventListener.initializeMainStage 가 부팅마다 자동 복원해야 한다.

        private PartyroomData mainWithStatus(PartyroomStatus status) {
            return PartyroomData.builder()
                    .id(1L)
                    .hostId(new UserId(1L))
                    .stageType(StageType.MAIN)
                    .title("Main Stage")
                    .introduction("Welcome")
                    .linkDomain(LinkDomain.of("main"))
                    .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                    .noticeContent("")
                    .status(status)
                    .build();
        }

        @Test @DisplayName("MAIN + TERMINATED → ACTIVE 복원")
        void mainTerminatedToActive() {
            PartyroomData p = mainWithStatus(PartyroomStatus.TERMINATED);
            p.reactivateAsMainStage();
            assertThat(p.isActive()).isTrue();
            assertThat(p.isTerminated()).isFalse();
        }

        @Test @DisplayName("MAIN + SUSPENDED → ACTIVE 복원")
        void mainSuspendedToActive() {
            PartyroomData p = mainWithStatus(PartyroomStatus.SUSPENDED);
            p.reactivateAsMainStage();
            assertThat(p.isActive()).isTrue();
        }

        @Test @DisplayName("MAIN + ACTIVE → no-op (idempotent)")
        void mainActiveIdempotent() {
            PartyroomData p = mainWithStatus(PartyroomStatus.ACTIVE);
            assertThatNoException().isThrownBy(p::reactivateAsMainStage);
            assertThat(p.isActive()).isTrue();
        }

        @Test @DisplayName("GENERAL stage → ConflictException (일반 파티룸 terminal invariant 보호)")
        void rejectsGeneralStage() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::reactivateAsMainStage).isInstanceOf(ConflictException.class);
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
