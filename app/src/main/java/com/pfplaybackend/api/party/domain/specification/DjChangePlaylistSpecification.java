package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.exception.DjException;

public class DjChangePlaylistSpecification {

    public void validate(DjQueueData djQueue, boolean isCurrentDj,
                         boolean isOwned, boolean isEmptyPlaylist) {
        djQueue.validateOpen();
        if (isCurrentDj)     throw ExceptionCreator.create(DjException.CURRENT_DJ_CANNOT_CHANGE_PLAYLIST);
        if (!isOwned)        throw ExceptionCreator.create(DjException.NOT_OWNED_PLAYLIST);
        if (isEmptyPlaylist) throw ExceptionCreator.create(DjException.EMPTY_PLAYLIST);
    }
}
