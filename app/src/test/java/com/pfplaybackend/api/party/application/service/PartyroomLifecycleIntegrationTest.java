package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.DjQueueRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.dto.command.CreatePartyroomCommand;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class PartyroomLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PartyroomCommandService partyroomCommandService;
    @Autowired
    private PartyroomRepository partyroomRepository;
    @Autowired
    private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired
    private DjQueueRepository djQueueRepository;

    /**
     * createGeneralPartyRoom → enterByHost → assertHasProfile(PA-7.1) 게이트만 무력화 —
     * 본 테스트의 aggregate 영속 검증과 직교. (실제 ProfileData 행 구성은 fragile —
     * ClusterAPresenceIntegrationTest / CreateGeneralPartyRoomInvariantIntegrationTest 와 동일 패턴.)
     */
    @MockBean
    private UserProfileQueryPort userProfileQueryPort;

    private UserId hostId;

    @BeforeEach
    void setUp() {
        hostId = new UserId(1L);
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(hostId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);

        // 어떤 userId 든 프로필 보유 상태로 — assertHasProfile 통과.
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new java.util.HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("createGeneralPartyRoom — 파티룸, 재생 상태, DJ 큐가 모두 DB에 저장된다")
    void createGeneralPartyRoomSavesAllAggregateEntities() {
        // given
        CreatePartyroomCommand command = new CreatePartyroomCommand(
                "라이프사이클 테스트", "통합 테스트 파티룸", "lifecycle", 10);

        // when
        PartyroomData created = partyroomCommandService.createGeneralPartyRoom(command);

        // then — PartyroomData
        Optional<PartyroomData> foundPartyroom = partyroomRepository.findById(created.getId());
        assertThat(foundPartyroom).isPresent();
        assertThat(foundPartyroom.get().getTitle()).isEqualTo("라이프사이클 테스트");

        // then — PartyroomPlaybackData
        Optional<PartyroomPlaybackData> foundPlayback = partyroomPlaybackRepository.findById(created.getPartyroomId());
        assertThat(foundPlayback).isPresent();
        assertThat(foundPlayback.get().isActivated()).isFalse();

        // then — DjQueueData
        Optional<DjQueueData> foundDjQueue = djQueueRepository.findById(created.getPartyroomId());
        assertThat(foundDjQueue).isPresent();
        assertThat(foundDjQueue.get().isClosed()).isFalse();
    }
}
