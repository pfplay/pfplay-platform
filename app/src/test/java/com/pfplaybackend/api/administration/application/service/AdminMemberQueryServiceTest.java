package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMemberQueryServiceTest {

    @Mock AdminMemberQueryRepository memberRepo;
    @Mock UserActivityLogRepository ualRepo;
    AdminMemberQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminMemberQueryService(memberRepo, ualRepo);
    }

    @Test
    @DisplayName("getDetail: member + recentActivityLog 합산 응답")
    void getDetail_combines_two_sources() {
        AdminMemberDetailRow row = new AdminMemberDetailRow(
                50L, 100L, "u@x", ProviderType.GOOGLE,
                LocalDateTime.of(2026, 4, 28, 10, 0), null,
                "Nick", "intro", AuthorityTier.FM,
                LocalDateTime.of(2025, 12, 1, 0, 0));
        when(memberRepo.findDetail(50L)).thenReturn(Optional.of(row));

        UserActivityLogData log1 = UserActivityLogData.of(
                100L, UserActivityEventType.PARTYROOM_ENTERED, 1L,
                JsonMetadata.empty(), LocalDateTime.of(2026, 4, 28, 12, 0));
        when(ualRepo.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(100L))
                .thenReturn(List.of(log1));

        AdminMemberDetailResponse response = service.getDetail(50L);

        assertThat(response.memberId()).isEqualTo(50L);
        assertThat(response.userAccount().userAccountId()).isEqualTo(100L);
        assertThat(response.userAccount().email()).isEqualTo("u@x");
        assertThat(response.userAccount().providerType()).isEqualTo(ProviderType.GOOGLE);
        assertThat(response.profile().nickname()).isEqualTo("Nick");
        assertThat(response.profile().introduction()).isEqualTo("intro");
        assertThat(response.authorityTier()).isEqualTo(AuthorityTier.FM);
        assertThat(response.recentActivityLog()).hasSize(1);
        assertThat(response.recentActivityLog().get(0).eventType()).isEqualTo("PARTYROOM_ENTERED");
        assertThat(response.recentActivityLog().get(0).partyroomId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getDetail: memberId 없으면 MEMBER_NOT_FOUND")
    void getDetail_throws_when_missing() {
        when(memberRepo.findDetail(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L))
                .isInstanceOf(NotFoundException.class);
    }
}
