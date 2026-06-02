package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotRosterRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 봇 아바타 어드민 운영 — 카탈로그 조회 / 로스터 조회 / 개별·일괄 변별 배분. */
@Service
@RequiredArgsConstructor
public class BotAvatarAdminService {

    private final BotPoolQueryRepository botPoolQueryRepository;
    private final AvatarCatalogQueryUseCase catalog;
    private final BotAvatarAssigner assigner;

    public record CatalogItem(String bodyUri, String name, String thumbnailUri,
                              boolean combinable, String obtainableType) {}

    @Transactional(readOnly = true)
    public List<CatalogItem> catalog() {
        return catalog.findPublishedBodies().stream()
                .map(BotAvatarAdminService::toCatalogItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BotRosterRow> roster() {
        return botPoolQueryRepository.findRoster();
    }

    @Transactional
    public void setIndividual(UserId botUserId, String bodyUri) {
        assigner.assignOne(botUserId, bodyUri);
    }

    @Transactional
    public List<BotAvatarAssigner.Assigned> distribute(List<Long> botIds, List<String> bodyUris) {
        // 부분 성공(spec §2.3)을 트랜잭션 안전하게 처리한다. apply 단계에서 비-봇 예외를 잡아 격리하면
        // 공유 REQUIRED 트랜잭션이 rollback-only 로 마킹돼 성공분까지 통째로 롤백(UnexpectedRollbackException)
        // 되므로, 예외-제어흐름 대신 사전 필터로 실제 봇만 추려 넘긴다. 셋 무결성(빈 셋/빈 봇목록/미존재 bodyUri)
        // 검증은 assigner 가 그대로 수행(→ 400).
        List<Long> validBotIds = botPoolQueryRepository.filterBotUserIds(botIds);
        List<UserId> ids = validBotIds.stream().map(UserId::new).toList();
        return assigner.distribute(ids, bodyUris);
    }

    private static CatalogItem toCatalogItem(AvatarBodyDto b) {
        return new CatalogItem(b.getResourceUri(), b.getName(), b.getResourceUri(),
                b.isCombinable(), b.getObtainableType() == null ? null : b.getObtainableType().name());
    }
}
