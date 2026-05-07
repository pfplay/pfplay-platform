package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EasyUserManagementControllerTest extends AbstractUserWebMvcTest {

    @Test
    @DisplayName("createAssociateMember — 200 OK")
    void createAssociateMemberReturns200() throws Exception {
        // given
        MemberData member = mock(MemberData.class);
        UserAccountData userAccount = mock(UserAccountData.class);
        when(member.getUserAccountId()).thenReturn(1L);
        when(member.getAuthorityTier()).thenReturn(AuthorityTier.AM);
        when(userAccount.getUserId()).thenReturn(new UserId(1L));
        when(userAccount.getEmail()).thenReturn("test@gmail.com");
        when(userAccount.getProviderType()).thenReturn(ProviderType.GOOGLE);
        when(temporaryUserInitializeService.addAssociateMember(any(UserId.class), anyString())).thenReturn(member);
        when(userAccountRepository.findById(any(UserId.class))).thenReturn(Optional.of(userAccount));
        when(jwtService.mintSharedSessionToken(any())).thenReturn("mock-token");

        // when & then
        mockMvc.perform(post("/api/v1/users/members/sign/temporary/associate-member")
                        .with(jwt())
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("createFullMember — 200 OK")
    void createFullMemberReturns200() throws Exception {
        // given
        MemberData member = mock(MemberData.class);
        UserAccountData userAccount = mock(UserAccountData.class);
        when(member.getUserAccountId()).thenReturn(1L);
        when(member.getAuthorityTier()).thenReturn(AuthorityTier.AM);
        when(userAccount.getUserId()).thenReturn(new UserId(1L));
        when(userAccount.getEmail()).thenReturn("test@gmail.com");
        when(userAccount.getProviderType()).thenReturn(ProviderType.GOOGLE);
        when(temporaryUserInitializeService.addAssociateMember(any(UserId.class), anyString())).thenReturn(member);
        when(temporaryUserInitializeService.upgradeMember(any(MemberData.class))).thenReturn(member);
        when(userAccountRepository.findById(any(UserId.class))).thenReturn(Optional.of(userAccount));
        when(jwtService.mintSharedSessionToken(any())).thenReturn("mock-token");

        // when & then
        mockMvc.perform(post("/api/v1/users/members/sign/temporary/full-member")
                        .with(jwt())
                        .with(csrf()))
                .andExpect(status().isOk());
    }
}
