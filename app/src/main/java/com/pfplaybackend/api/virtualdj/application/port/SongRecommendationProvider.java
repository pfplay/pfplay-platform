package com.pfplaybackend.api.virtualdj.application.port;

import com.pfplaybackend.api.virtualdj.application.port.RoomContextReader.RoomContext;
import java.util.List;

/** 고반응 우승 곡 + 방 컨셉 → 추천 곡명(검색 쿼리) 리스트. best-effort: 실패 시 빈 리스트. */
public interface SongRecommendationProvider {
    /**
     * @param roomContext  방 제목/소개/현재곡(컨셉)
     * @param winnerTitles 고반응 우승 곡 제목들(LLM 의 취향 단서)
     * @param count        원하는 추천 수 N
     * @return 추천 곡명 리스트(0~count). 실패 시 빈 리스트(예외 없음).
     */
    List<String> recommend(RoomContext roomContext, List<String> winnerTitles, int count);
}
