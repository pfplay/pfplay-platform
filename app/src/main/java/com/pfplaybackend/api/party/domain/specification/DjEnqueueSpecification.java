package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.exception.DjException;

public class DjEnqueueSpecification {

    public void validate(DjQueueData djQueue, boolean isAlreadyRegistered,
                         boolean isOwned, boolean isEmptyPlaylist) {
        djQueue.validateOpen();
        if (!isOwned)        throw ExceptionCreator.create(DjException.NOT_OWNED_PLAYLIST);
        if (isEmptyPlaylist) throw ExceptionCreator.create(DjException.EMPTY_PLAYLIST);
        if (isAlreadyRegistered) throw ExceptionCreator.create(DjException.ALREADY_REGISTERED);
    }
}
