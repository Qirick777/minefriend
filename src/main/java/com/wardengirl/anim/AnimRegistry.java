package com.wardengirl.anim;

import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.RawAnimation;

/** RawAnimation definitions. Design doc Part 3.5. */
public final class AnimRegistry {

    private AnimRegistry() {
    }

    /**
     * The always-on controller. Design doc Part 4.2.
     *
     * <p>Still named {@code vital} because that is the controller C1 used to live on, and the name
     * is what GeckoLib keys its per-controller state by. What it <em>plays</em> changed in T5: C1's
     * Molang moved to {@link com.wardengirl.client.VitalMotion} and this controller now plays
     * {@link #BASE}, a clip of constant zeros whose only job is to overwrite every channel Java
     * adds to. See {@link #BASE}.
     */
    public static final String CONTROLLER_VITAL = "vital";

    /** The pre-migration C1 clip, kept for the A/B comparison. See {@link #BASE}. */
    public static final String VITAL = "vital";

    /**
     * C1 — breathing, bounce, micro-sway, as Molang. Design doc Part 4.3.
     *
     * <p>Selected by {@code c1_source = 0}. The live path is {@code c1_source = 1}, which plays
     * {@link #BASE_LOOP} and adds C1 in Java instead — see {@link com.wardengirl.client.VitalMotion}
     * for why.
     */
    public static final RawAnimation VITAL_LOOP =
            RawAnimation.begin().thenLoop(VITAL);

    /**
     * The zero baseline. Constant 0 on every channel Java writes with {@code +=}.
     *
     * <p>Not decoration and not a placeholder. {@code GeoBone}'s setters mark the bone as changed
     * and {@code AnimationProcessor} then skips its reset-to-initial-snapshot step for that bone
     * forever, so a bone that Java adds to and no clip assigns to accumulates one offset per frame.
     * {@code idle} is an empty clip and {@code walk} covers only some channels, so neither can
     * serve; this clip does. The json file carries the full reasoning.
     */
    public static final String BASE = "base";

    public static final RawAnimation BASE_LOOP = RawAnimation.begin().thenLoop(BASE);

    /** Guards against a typo in the names above silently producing a still model. */
    public static final Animation.LoopType ALWAYS_ON_LOOP = Animation.LoopType.LOOP;

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

    /**
     * T5 2라운드 임시 — C3 직접 평가 경로 검증용 클립.
     *
     * <p><b>There is no controller for this and there must not be one.</b> C3 is evaluated by
     * {@link ClipSampler} and added in {@code setCustomAnimations}, which is the only place a
     * second layer survives alongside C2 (Part 4.2). The name is here because
     * {@code GeoModel.getAnimation} resolves clips by name.
     *
     * <p>Replaced by the real 4.8 attack clip in T7. Kept afterwards for the same reason
     * {@link #SIGN_TEST} is kept — it is the procedure for re-verifying the direct-evaluation path
     * after a GeckoLib version bump.
     */
    public static final String ACTION_TEST = "actiontest";
}
