package com.wardengirl.anim;

import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.RawAnimation;

/** RawAnimation definitions. Design doc Part 3.5. */
public final class AnimRegistry {

    private AnimRegistry() {
    }

    /** C1 controller name. Design doc Part 4.2. */
    public static final String CONTROLLER_VITAL = "vital";

    /**
     * C1 — breathing, bounce, micro-sway. Design doc Part 4.3.
     *
     * <p>Loops forever and is never stopped: Part 4.1 원칙 6 and Part 10.5 both forbid it. The only
     * exception the doc allows is amplitude 0 during ticks 0–3 of the sonic boom, which is T8.
     */
    public static final RawAnimation VITAL =
            RawAnimation.begin().thenLoop(CONTROLLER_VITAL);

    /** Guards against a typo in the name above silently producing a still model. */
    public static final Animation.LoopType VITAL_LOOP = Animation.LoopType.LOOP;
}
