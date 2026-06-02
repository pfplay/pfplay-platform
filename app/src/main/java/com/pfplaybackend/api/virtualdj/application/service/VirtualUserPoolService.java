package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualMemberProvisionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 봇(가상 사용자) 풀 — 봇 계정 프로비저닝 + idle 봇 조회 + 봇 playlist 조회.
 *
 * <p>봇은 실제 정식 회원과 동일한 경로({@link VirtualMemberProvisionPort#createVirtualMember})로 만들어진다:
 * LOCAL provider 계정 + 프로필(닉네임·기본 아바타) + activity 행 + GRABLIST + FM 승격. 그 위에
 * 두 가지만 더한다 — (1) {@code is_dummy=true} 표시, (2) DJ 가 사용할 {@link PlaylistType#PLAYLIST}
 * 타입 playlist 1개 생성(Chunk 3 의 송팩 복사 대상, Chunk 4 의 enqueueDj 대상).
 *
 * <p>오케스트레이션 로직(reconcile/투입/제거)은 여기 두지 않는다 — Chunk 4 책임.
 */
@Service
@RequiredArgsConstructor
public class VirtualUserPoolService {

    private static final String BOT_PLAYLIST_NAME = "Bot DJ Playlist";

    private final VirtualMemberProvisionPort virtualMemberProvisionPort;
    private final UserAccountRepository userAccountRepository;
    private final PlaylistRepository playlistRepository;
    private final BotPoolQueryRepository botPoolQueryRepository;
    private final BotAvatarAssigner botAvatarAssigner;

    /**
     * 봇 {@code n} 명을 생성한다. 각 봇은 실 {@code user_account}(is_dummy=true, LOCAL),
     * 고유 닉네임·기본 아바타 프로필, 그리고 DJ 용 {@link PlaylistType#PLAYLIST} playlist 를 가진다.
     */
    @Transactional
    public List<UserId> provision(int n) {
        List<UserId> created = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 1) 실유저와 동일한 회원 생성 경로 재사용 (FM, 프로필+아바타+activity+GRABLIST).
            //    닉네임은 V15 UNIQUE(user_profile.nickname) 충돌을 피하도록 고엔트로피 UUID 로 명시.
            MemberData member = virtualMemberProvisionPort.createVirtualMember(generateBotNickname());
            UserId botUserId = new UserId(member.getUserAccountId());

            // 2) is_dummy 표시 (가산적).
            UserAccountData account = userAccountRepository.findById(botUserId).orElseThrow();
            account.markAsDummy();

            // 3) DJ 가 enqueue 할 PLAYLIST 타입 playlist 1개 생성 (송팩 복사 대상).
            playlistRepository.save(
                    PlaylistData.create(1, BOT_PLAYLIST_NAME, PlaylistType.PLAYLIST, botUserId));

            // 4) P1-D5: 생성 즉시 카탈로그에서 랜덤 변별 아바타를 부여한다. createVirtualMember 의
            //    디폴트 바디(ava_basic_001, combinable·icon NULL)는 채팅 아이콘이 깨지므로 합성 규칙
            //    (combinable→공통 face / standalone→자체 아이콘)으로 non-blank 아이콘을 보장한다.
            botAvatarAssigner.assignRandomFromCatalog(botUserId);

            created.add(botUserId);
        }
        return created;
    }

    /**
     * 활성 crew 가 없는(=어느 방에도 없는) 봇 최대 {@code n} 명을 반환한다.
     */
    @Transactional(readOnly = true)
    public List<UserId> findIdleBots(int n) {
        return botPoolQueryRepository.findIdleBotUserIds(n);
    }

    /**
     * 봇의 DJ 용 {@link PlaylistType#PLAYLIST} playlist id 를 반환한다(Chunk 4 enqueueDj 에서 사용).
     */
    @Transactional(readOnly = true)
    public Long playlistIdOf(UserId botUserId) {
        PlaylistData playlist = playlistRepository.findByOwnerIdAndType(botUserId, PlaylistType.PLAYLIST);
        if (playlist == null) {
            throw new IllegalStateException("Bot playlist not found for userId=" + botUserId.getUid() + " — bot not provisioned correctly");
        }
        return playlist.getId();
    }

    private String generateBotNickname() {
        return "DJ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
