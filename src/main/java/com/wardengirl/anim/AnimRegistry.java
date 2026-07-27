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

    /** C2 controller name. Design doc Part 4.2 / 4.4. */
    public static final String CONTROLLER_LOCOMOTION = "locomotion";

    /** C2 walk cycle, 26 ticks. Design doc 4.4.2. */
    public static final String WALK = "walk";

    public static final RawAnimation WALK_LOOP = RawAnimation.begin().thenLoop(WALK);

    /** 4.4.1 idle — an empty clip, not {@link software.bernie.geckolib.core.object.PlayState#STOP}. */
    public static final String IDLE = "idle";

    public static final RawAnimation IDLE_LOOP = RawAnimation.begin().thenLoop(IDLE);

    /**
     * C2 transition length, ticks. Design doc 4.4.1.
     *
     * <p>It only takes effect because idle is an <em>empty clip</em> rather than
     * {@code PlayState.STOP}. Measured in the 4.8.4 bytecode: {@code AnimationController.process}
     * checks {@code playState == STOP} and, if so, sets {@code State.STOPPED}, sets
     * {@code justStopped} and returns immediately — the {@code TRANSITIONING} branch sits above
     * that check and is never reached. The bone therefore loses the walk's contribution in a
     * single frame, and {@code justStopped} makes the next start call {@code adjustTick} so the
     * clip restarts from tick 0.
     */
    public static final int TRANSITION_TICKS = 6;

    /**
     * T5 임시 — json 키프레임 경로의 축별 부호 실측용. Deleted once the convention is settled.
     *
     * <p>T1 verified the sign convention by calling {@code GeoBone.setRot*} from Java. That says
     * nothing about the json keyframe path, and the walk cycle is json keyframes. The two are
     * separate code paths and nothing guaranteed they agreed.
     */
    public static final String SIGN_TEST = "signtest";

    public static final RawAnimation SIGN_TEST_LOOP = RawAnimation.begin().thenLoop(SIGN_TEST);
}
