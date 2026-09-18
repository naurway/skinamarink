package com.naurway.skinamarink.content;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

import java.util.Optional;

/**
 * The table spawn_effect picks from - small, positioned particle bursts sent
 * only to the targeted player (never a big visible spectacle; count/spread
 * are kept low so these read as "something in a corner" rather than a
 * showy effect).
 */
public enum EffectTable {
    CORNER_SHADOW(ParticleTypes.SMOKE, 6, 0.15),
    STATIC_SHIMMER(ParticleTypes.PORTAL, 10, 0.3),
    DUST_MOTE(ParticleTypes.CLOUD, 3, 0.1),
    WRONG_GLIMMER(ParticleTypes.WITCH, 5, 0.2);

    public final ParticleOptions particle;
    public final int count;
    public final double spread;

    EffectTable(ParticleOptions particle, int count, double spread) {
        this.particle = particle;
        this.count = count;
        this.spread = spread;
    }

    public static Optional<EffectTable> byId(String id) {
        try {
            return Optional.of(valueOf(id.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
