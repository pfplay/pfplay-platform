package com.pfplaybackend.api.virtualcrew.adapter.out.random;

import com.pfplaybackend.api.virtualcrew.application.port.Randomizer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class ThreadLocalRandomizer implements Randomizer {
    @Override
    public int nextIndex(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be > 0, got " + bound);
        return ThreadLocalRandom.current().nextInt(bound);
    }
}
