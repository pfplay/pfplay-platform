package com.pfplaybackend.api.administration.domain.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;

public record AnnouncementPublishedEvent(SystemAnnouncementData entity) {}
