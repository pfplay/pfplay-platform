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
        List<UserId> ids = botIds.stream().map(UserId::new).toList();
        return assigner.distribute(ids, bodyUris);
    }

    private static CatalogItem toCatalogItem(AvatarBodyDto b) {
        return new CatalogItem(b.getResourceUri(), b.getName(), b.getResourceUri(),
                b.isCombinable(), b.getObtainableType() == null ? null : b.getObtainableType().name());
    }
}
