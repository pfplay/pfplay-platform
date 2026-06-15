package com.pfplaybackend.api.virtualdj.application.dto;

/**
 * 봇 자기 plays 를 link_id 로 group 한 반응 집계 (P3-B score 입력).
 *
 * @param linkId        곡 링크 식별자
 * @param likeSum       Σ like_count
 * @param dislikeSum    Σ dislike_count
 * @param grabSum       Σ grab_count
 */
public record LinkReactionScore(String linkId, long likeSum, long dislikeSum, long grabSum) {}
