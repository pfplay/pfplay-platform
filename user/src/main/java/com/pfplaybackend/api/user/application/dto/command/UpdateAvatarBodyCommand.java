package com.pfplaybackend.api.user.application.dto.command;

import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;

public record UpdateAvatarBodyCommand(AvatarBodyUri avatarBodyUri) {}
