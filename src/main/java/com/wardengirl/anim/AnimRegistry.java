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

    // ---- T30 gap dive. 서버 playAction 채널로 재생되는 3구간 한 세트다 -----------------------

    public static final String GAP_DIVE_AIR = "gap_dive_air";
    public static final String GAP_DIVE_LAND = "gap_dive_land";

    /**
     * {@code gap_dive_land} 의 json {@code animation_length} 초.
     *
     * <p>여기 있는 이유는 서버가 애니메이션 리소스를 읽지 않기 때문이다. 착지 액션의 종료
     * 예정 시각은 이 <b>클립 길이</b>에서 계산하며, 임의의 매직 넘버를 쓰지 않는다.
     * json 을 고치면 이 값도 같이 고쳐야 한다 — 두 값의 유일한 연결점이다.
     */
    public static final double GAP_DIVE_LAND_SECONDS = 0.32D;

    /** 위 길이를 틱으로 올림한 값. 서버가 액션 슬롯을 비울 시점을 정하는 데 쓴다. */
    public static final int GAP_DIVE_LAND_TICKS =
            (int) Math.ceil(GAP_DIVE_LAND_SECONDS * 20.0D);

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

    /**
     * 4.8 기본 공격 — 양팔 내려찍기. 18틱. <b>T7 첫 후보이며 사람 승인 전이다.</b>
     *
     * <p>4.12 킁킁과 같은 경로를 쓴다 — 서버 Goal 이 발동을 결정하고 표준 엔티티 사건으로 알리며,
     * 클립은 {@code ClipSampler} 로 직접 평가해 {@code ActionMotion} 슬롯에 올린다.
     */
    public static final String ATTACK = "attack";

    /** json {@code animation_length: 0.9} 초 × 20. 저자가 그린 클립 길이다. */
    public static final double ATTACK_LENGTH_TICKS = 18.0D;

    /**
     * {@code attack} 클립 <b>전용</b> 재생 배속. 사용자가 화면과 전투를 직접 확인해 확정한 값이며
     * 코드 상수로 고정한다 — 명령·패킷·설정·NBT 로 바꿀 수 없고 모든 클라이언트가 이 숫자를 쓴다.
     *
     * <p>소닉·피격·킁킁·걷기·대기·기타 액션에는 적용하지 않는다. 적용 지점은
     * {@code WardenGirlModel} 의 {@code attack} 분기 한 곳뿐이며, 거기서
     * {@code ActionMotion} 의 시간축 배율로 넘긴다.
     */
    public static final double ATTACK_SPEED = 1.2D;

    /**
     * 화면에서 {@code attack} 클립이 실제로 차지하는 틱. {@code 18 / 1.2 = 15} 로 나누어떨어진다.
     *
     * <p><b>같은 숫자를 여러 파일에 적지 않기 위해 여기서만 파생한다.</b> 서버의 공격 모션 상태
     * 길이와 클라이언트 {@code attackTime} 카운터가 모두 이 값을 쓰므로, 서버 상태·동기화
     * 카운터·화면 클립이 같은 틱에 끝난다. 공격 시작 간격
     * {@code WardenGirlAttackGoal.MELEE_COOLDOWN} 은 19틱 그대로이므로 모션이 끝난 뒤 다음
     * 공격까지 4틱이 남는다 — 배속이 서버 간격이나 피해 시점을 바꾸지 않는다.
     */
    public static final double ATTACK_PLAY_TICKS = ATTACK_LENGTH_TICKS / ATTACK_SPEED;

    /**
     * 4.9 소닉붐. 60틱. <b>T8 첫 후보이며 사람 승인 전이다.</b>
     *
     * <p>길이 60 과 방출 34 는 바닐라 {@code SonicBoom} 의 {@code DURATION} /
     * {@code TICKS_BEFORE_PLAYING_SOUND} 와 같은 값이다.
     */
    public static final String SONIC_BOOM = "sonic_boom";

    /** json {@code animation_length: 3.0} 초 × 20. */
    public static final double SONIC_LENGTH_TICKS = 60.0D;

    /** 시작 후 이 틱에 팔이 완전히 펼쳐지고 파티클·사운드가 나간다. */
    public static final int SONIC_EMIT_TICK = 34;
}
