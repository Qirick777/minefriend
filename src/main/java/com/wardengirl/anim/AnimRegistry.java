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
     * <h2>It does not currently take effect. Measured.</h2>
     *
     * <p>The earlier note here claimed that playing an empty {@code idle} clip instead of returning
     * {@code PlayState.STOP} made the 6-tick transition "actually work". <b>That is wrong, and the
     * measurement is unambiguous:</b> {@code leg_right.xRot} goes {@code +15.6800 → +0.0000} in a
     * single frame and then holds exactly {@code 0.0000}, and {@code arm_right.xRot} lands on
     * exactly {@code +4.0000}, which is {@code offset_arm_right_x} alone. The walk's contribution
     * does not decay over six ticks — it disappears in one.
     *
     * <p>The empty clip does avoid the {@code STOP} branch, and the controller does enter
     * {@code TRANSITIONING}. The problem is one level down: the transition body iterates
     * <b>the target animation's</b> {@code boneAnimations()} (4.8.4 {@code process}, offset 352)
     * and only calls {@code addNextRotation} for bones found there. {@code saveSnapshotsForAnimation}
     * iterates the same array. An empty clip has none, so no snapshot is saved, no queue is filled,
     * {@code AnimationProcessor} polls {@code null} and skips the write entirely — and the bone is
     * left holding whatever {@link #BASE} wrote, which is 0.
     *
     * <p>So the transition length has nothing to interpolate. It is not a tuning problem.
     * <b>Investigation only so far; the fix is not implemented.</b> See Part 11.
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

    /**
     * 4.11 피격 움찔. 10틱.
     *
     * <p>바닐라는 {@code LivingEntity.hurt} 안에서 {@code walkAnimation.setSpeed(1.5F)} 로
     * {@code limbSwing} 을 인위적으로 밀어 걷기 사이클의 40% 를 8틱에 재생한다. 우리 위상은 렌더
     * 위치 델타에 묶여 있어 그 경로가 구조적으로 없으므로, 같은 인상을 C3 클립으로 만든다.
     * Part 11 참조.
     */
    public static final String IDLE_HURT = "idle_hurt";

    /**
     * 4.12 킁킁. 44틱. <b>T6 이고 폐기 가능하다</b> — {@code sniff_distance} 0 이면 발동하지 않는다.
     */
    public static final String IDLE_SNIFF = "idle_sniff";

    /** json {@code animation_length: 1.9} 초 × 20. */
    public static final double SNIFF_LENGTH_TICKS = 38.0D;
}
