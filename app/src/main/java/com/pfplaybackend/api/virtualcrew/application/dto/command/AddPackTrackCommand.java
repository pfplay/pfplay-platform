package com.pfplaybackend.api.virtualcrew.application.dto.command;

/**
 * 송 팩에 트랙을 추가할 때 사용하는 커맨드 DTO.
 *
 * @param name           곡 이름
 * @param linkId         YouTube videoId
 * @param duration       MM:SS 또는 H:MM:SS 형식 재생 시간
 * @param thumbnailImage 썸네일 이미지 URL (nullable)
 */
public record AddPackTrackCommand(
        String name,
        String linkId,
        String duration,
        String thumbnailImage
) {}
