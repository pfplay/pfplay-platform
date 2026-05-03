package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.dto.command.UpdateWalletCommand;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserWalletCommandService {

    private final MemberRepository memberRepository;

    @Transactional
    public MemberData updateMyWalletAddress(UpdateWalletCommand updateWalletCommand) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        MemberData member = memberRepository.findByUserAccountId(authContext.getUserId().getUid()).orElseThrow();
        // TODO 지갑 주소 서명 검증 실패 시 예외 발생!
        member.updateWalletAddress(new WalletAddress(updateWalletCommand.walletAddress()));
        memberRepository.save(member);
        return member;
    }
}
