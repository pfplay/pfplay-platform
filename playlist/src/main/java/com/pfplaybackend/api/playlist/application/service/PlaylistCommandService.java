package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.exception.PlaylistException;
import com.pfplaybackend.api.playlist.domain.policy.PlaylistCreationPolicy;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlaylistCommandService {

    private final PlaylistAggregatePort aggregatePort;

    /**
     * 멤버 생성 시 기본적으로 생성되는 grab 용도의 '플레이리스트 레코드'를 생성키 위함이다.
     */
    @Transactional
    public void createDefaultPlaylist(UserId userId) {
        PlaylistData playlist = PlaylistData.create(0, "그랩한 곡", PlaylistType.GRABLIST, userId);
        aggregatePort.savePlaylist(playlist);
    }

    /**
     * 신규 가입자 온보딩용 기본 DJ 플레이리스트(#329).
     * GRABLIST 생성({@link #createDefaultPlaylist})과 동일하게 시스템 생성이라 생성 상한 정책을 거치지
     * 않는다(가입 시점 1회). AM 상한(1)은 이 플리가 소모하며, 유저는 rename/삭제가 자유롭고 삭제 시
     * 슬롯이 해제된다(정책은 현존 행 수 기준).
     *
     * <p>⚠️ 호출처는 <b>사람 가입 경로 한정</b>(MemberSignService·TemporaryUserInitializeService).
     * 봇/가상 멤버({@code AdminUserService.createVirtualMember} 경유 — 데모 멤버 포함)에는 배선하지
     * 않는다: 봇은 provision 이 자체 PLAYLIST 를 만들며, 송팩 적용이
     * {@code findByOwnerIdAndType(PLAYLIST)} 단건 조회에 의존하므로 두 번째 PLAYLIST 는 이를 파손한다.
     */
    @Transactional
    public void createDefaultDjPlaylist(UserId userId) {
        PlaylistData playlist = PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, userId);
        aggregatePort.savePlaylist(playlist);
    }

    @Transactional
    public PlaylistData createPlaylist(String playlistName) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        List<PlaylistData> playlistDataList = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST);

        // 권한 계층별 '생성 제약' 조건에 대한 위반 여부를 검증한다.
        new PlaylistCreationPolicy().enforce(authContext.getAuthorityTier(), playlistDataList.size());

        // Save
        int nextOrderNumber = playlistDataList.isEmpty() ? 1 : playlistDataList.size();
        PlaylistData playlist = PlaylistData.create(nextOrderNumber, playlistName, PlaylistType.PLAYLIST, userId);
        return aggregatePort.savePlaylist(playlist);
    }

    @Transactional
    public PlaylistData renamePlaylist(Long playlistId, String name) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();

        PlaylistData playlistData = aggregatePort.findPlaylistByIdAndOwnerAndType(playlistId, userId, PlaylistType.PLAYLIST)
                .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));

        playlistData.rename(name);
        aggregatePort.savePlaylist(playlistData);
        return playlistData;
    }

    @Transactional
    public void deletePlaylist(List<Long> playlistIds) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        List<PlaylistData> playlistDataList = aggregatePort.findAllPlaylistsByOwner(userId);

        Set<Long> userPlaylistIdSet = playlistDataList.stream().map(PlaylistData::getId).collect(Collectors.toSet());
        if (!userPlaylistIdSet.containsAll(playlistIds)) {
            throw ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST);
        }

        Long count = aggregatePort.deletePlaylistsByIds(playlistIds);
        if (count != playlistIds.size()) {
            throw ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST);
        }
    }
}
