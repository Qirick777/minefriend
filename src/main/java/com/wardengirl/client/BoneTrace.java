package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AnimRegistry;
import com.wardengirl.anim.Bones;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-frame trace of the whole rig, with automatic range checking.
 *
 * <h2>Why every bone, every axis, always</h2>
 *
 * A leg spun continuously for a whole task cycle without being caught, because the verification
 * dump only listed the bones the layer was <em>believed</em> to touch — and the legs were not among
 * them. The bug was precisely a bone moving that nobody expected to move, so the one dump that
 * could have caught it was the one that had been narrowed away.
 *
 * <p>So: <b>10 bones × 3 axes, unconditionally.</b> "This motion only uses these bones" is an
 * assumption, and assumptions are where bugs live. Narrowing the dump to match the assumption
 * guarantees the dump can never contradict it. The same now applies to positions, which had been
 * narrowed to root and body for exactly the reasoning this paragraph forbids.
 *
 * <h2>Automatic verdicts</h2>
 *
 * Reading a wall of numbers by eye does not scale and does not survive repetition. Each axis
 * carries a declared expectation and is judged against it:
 * <ul>
 *   <li><b>CONST</b> — a bone with only a static offset must not move at all;</li>
 *   <li><b>RANGE</b> — an oscillating bone must stay inside centre ± amplitude;</li>
 *   <li><b>DRIFT</b> — a value that only ever increases (or only decreases) across the whole
 *       window is accumulating, which is the signature of the bug above. Detected separately from
 *       RANGE because accumulation is caught at the first frame it leaves the band, but drift
 *       inside a wide band would otherwise pass.</li>
 * </ul>
 *
 * <h2>Foot displacement</h2>
 *
 * Angles do not show a grounding problem. hip zRot of 0.8° sits comfortably inside every band the
 * checks above can express, and still swings the sole through 0.17px because the foot is 12px from
 * the hip pivot. So the sole's own travel is measured directly — see
 * {@link #accumulateFoot}. This is not a T2 one-off: it is the check that answers "do the feet
 * stay planted", and walking (T5), the sonic boom (T8) and the dash (T9) all need it.
 */
public final class BoneTrace {

    private BoneTrace() {
    }

    private static int remainingTicks = 0;
    private static int startTick = Integer.MIN_VALUE;
    private static int sampleCount = 0;
    private static final Map<String, double[]> MIN = new LinkedHashMap<>();
    private static final Map<String, double[]> MAX = new LinkedHashMap<>();
    private static final Map<String, double[]> FIRST = new LinkedHashMap<>();
    private static final Map<String, double[]> LAST = new LinkedHashMap<>();
    private static final Map<String, int[]> MONOTONIC = new LinkedHashMap<>();
    /** leg -> {maxAbsDx, maxAbsDy, maxAbsDz, maxHorizontal, worstCornerIndex} in model pixels. */
    private static final Map<String, double[]> FOOT = new LinkedHashMap<>();
    /**
     * 4.5 spring internals: min/max of the spring angle and of its velocity, per axis.
     *
     * <p>The bone value alone cannot answer "is the spring converging". The applied value is the
     * lag <em>error</em> {@code (angle − target) × AMPLITUDE}, so a spring oscillating with a
     * growing amplitude while chasing a moving head can still produce bone values inside the clamp.
     * Divergence shows up in the velocity, which is why it is recorded separately.
     */
    private static final int SIDES = 2;
    private static final String[] SIDE_NAME = {"오른쪽", "왼쪽"};
    private static final double[][] SPRING_ANGLE_MIN = new double[SIDES][3];
    private static final double[][] SPRING_ANGLE_MAX = new double[SIDES][3];
    private static final double[][] SPRING_VEL_MIN = new double[SIDES][3];
    private static final double[][] SPRING_VEL_MAX = new double[SIDES][3];
    private static final double[] SPRING_STIFFNESS = new double[SIDES];
    private static final boolean[] SPRING_SEEN = new boolean[SIDES];
    private static int totalTicks = 0;

    /**
     * How often the {@code MAX_ANGLE} clamp actually engaged, per side and axis.
     *
     * <p>Requested after the clamp was raised 35 → 45: a clamp is a safety net, and a safety net
     * that carries load every swing is really a limit. The bone's min/max cannot answer this —
     * reaching exactly ±MAX once looks the same as sitting on it for half the window.
     */
    private static final int[][] CLAMP_HITS = new int[SIDES][3];
    private static final int[] SPRING_SAMPLES = new int[SIDES];

    /**
     * Largest <em>simultaneous</em> difference between the two tendrils, per axis.
     *
     * <p>The first version of this compared the two sides' angle <em>maxima</em> and reported
     * 0.0527° for a swing where the rendered bones actually differed by 1.2°. Of course it did:
     * both springs eventually reach nearly the same extreme, they just reach it at different
     * moments, so comparing extremes measures almost nothing. What shows on screen is the two
     * tendrils holding different angles <em>at the same instant</em>, which is this.
     */
    private static final double[] SPRING_SPLIT_MAX = new double[3];
    private static final double[] SPRING_PENDING_RIGHT = new double[3];
    private static boolean pendingRightValid = false;

    /**
     * 본 회전 시계열. 루프 이음매와 전이는 min/max 로는 볼 수 없다.
     *
     * <p>min/max 는 "어디까지 갔는가" 만 말한다. 26틱 사이클이 0/26 에서 매끄럽게 이어지는지,
     * 걷기→정지 전이가 6틱에 걸쳐 잦아드는지 한 프레임에 끊기는지는 <em>순서</em>가 있어야
     * 답할 수 있는 질문이다. 그래서 몇 개 축만 값 그대로 남기고 1차 차분을 함께 낸다 —
     * 이음매에서만 차분이 튀면 불연속이고, 전이가 한 프레임에 끝나면 그 한 칸만 크다.
     */
    private static final String[] SERIES_BONES = {Bones.LEG_RIGHT, Bones.ARM_RIGHT, Bones.BODY};
    private static final int[] SERIES_AXIS = {0, 0, 1};   // leg_r xRot, arm_r xRot, body yRot
    private static final int SERIES_CAP = 4000;
    private static final double[][] SERIES = new double[SERIES_BONES.length][SERIES_CAP];
    private static final int[] SERIES_TICK = new int[SERIES_CAP];
    /** Walk state at each sample, so the report can separate transition phases from steady walking. */
    private static final boolean[] SERIES_WALK = new boolean[SERIES_CAP];
    private static int seriesCount = 0;

    /** 4.4.3 방향 전환 기울임: hip zRot 과 그것을 만든 yaw 변화율. */
    private static double turnLeanMin = Double.POSITIVE_INFINITY;
    private static double turnLeanMax = Double.NEGATIVE_INFINITY;
    private static double turnRateMin = Double.POSITIVE_INFINITY;
    private static double turnRateMax = Double.NEGATIVE_INFINITY;

    public static void noteTurnLean(double leanDeg, double rateDegPerTick) {
        if (remainingTicks <= 0) {
            return;
        }
        turnLeanMin = Math.min(turnLeanMin, leanDeg);
        turnLeanMax = Math.max(turnLeanMax, leanDeg);
        turnRateMin = Math.min(turnRateMin, rateDegPerTick);
        turnRateMax = Math.max(turnRateMax, rateDegPerTick);
    }

    /** Called by the model once per frame per side while a trace is running. */
    public static void noteSpring(int side, double[] angles, double[] velocities,
                                  double effectiveStiffness) {
        if (remainingTicks <= 0 || side < 0 || side >= SIDES) {
            return;
        }
        SPRING_STIFFNESS[side] = effectiveStiffness;
        SPRING_SAMPLES[side]++;
        double amplitude = AnimParams.HEADGEAR_AMPLITUDE.get();
        double max = Math.abs(AnimParams.HEADGEAR_MAX_ANGLE.get());
        for (int i = 0; i < 3; i++) {
            if (!SPRING_SEEN[side]) {
                SPRING_ANGLE_MIN[side][i] = angles[i];
                SPRING_ANGLE_MAX[side][i] = angles[i];
                SPRING_VEL_MIN[side][i] = velocities[i];
                SPRING_VEL_MAX[side][i] = velocities[i];
            }
            SPRING_ANGLE_MIN[side][i] = Math.min(SPRING_ANGLE_MIN[side][i], angles[i]);
            SPRING_ANGLE_MAX[side][i] = Math.max(SPRING_ANGLE_MAX[side][i], angles[i]);
            SPRING_VEL_MIN[side][i] = Math.min(SPRING_VEL_MIN[side][i], velocities[i]);
            SPRING_VEL_MAX[side][i] = Math.max(SPRING_VEL_MAX[side][i], velocities[i]);
        }
        SPRING_SEEN[side] = true;
        if (side == 0) {
            System.arraycopy(angles, 0, SPRING_PENDING_RIGHT, 0, 3);
            pendingRightValid = true;
        } else if (pendingRightValid) {
            for (int i = 0; i < 3; i++) {
                SPRING_SPLIT_MAX[i] = Math.max(SPRING_SPLIT_MAX[i],
                        Math.abs(SPRING_PENDING_RIGHT[i] - angles[i]));
            }
        }
        // The clamp is evaluated on the raw pre-clamp value, which is why the head target has to be
        // re-derived here rather than read back off the bone: the bone only ever shows the clamped
        // result and could not distinguish "reached the limit" from "was held at the limit".
        double[] head = LAST.get(Bones.HEAD);
        if (head != null) {
            for (int i = 0; i < 3; i++) {
                double lag = angles[i] - head[i];
                double raw = lag * amplitude;
                if (Math.abs(raw) >= max - 1e-6) {
                    CLAMP_HITS[side][i]++;
                }
                SPRING_LAG_MAX[side][i] = Math.max(SPRING_LAG_MAX[side][i], Math.abs(lag));
                SPRING_LAG_SUMSQ[side][i] += lag * lag;
                SPRING_LAG_N[side][i]++;
            }
        }
    }

    /**
     * 촉수 휘청임의 크기, 도.
     *
     * <p>The tendril's visible swing is {@code (springAngle − headAngle) * AMPLITUDE}, clamped — so
     * what actually decides "how much does it whip" is the <b>lag error</b>, the amount by which the
     * spring has failed to keep up with the head. Recording the spring's own angular range instead
     * would be the wrong quantity: a head that swings 60° drags the spring through 60° whether the
     * decoration lags by 20° or by 0.2°.
     *
     * <p>Peak and RMS both, because T4's question is whether damping the <em>input</em> kills the
     * effect. A peak can survive on one lucky transient while the typical motion has gone flat, and
     * the RMS is what an eye watching for a minute actually integrates.
     */
    private static final double[][] SPRING_LAG_MAX = new double[SIDES][3];
    private static final double[][] SPRING_LAG_SUMSQ = new double[SIDES][3];
    private static final int[][] SPRING_LAG_N = new int[SIDES][3];

    // ---- 4.6 시선 감쇠 --------------------------------------------------------------------------

    /**
     * Target and damped output, one row per {@link #sample} call.
     *
     * <p>A time series, not just extremes. "감쇠가 실제 작동하는가" cannot be answered by min/max:
     * a filter that is doing nothing and a filter that is working both produce the same range once
     * the input has been held long enough. What distinguishes them is whether {@code current}
     * arrives <em>after</em> {@code target} — which needs the two side by side, in order.
     */
    private static final int LOOK_CAPACITY = 2400;
    private static final double[] LOOK_TARGET_YAW = new double[LOOK_CAPACITY];
    private static final double[] LOOK_CURRENT_YAW = new double[LOOK_CAPACITY];
    private static final double[] LOOK_TARGET_PITCH = new double[LOOK_CAPACITY];
    private static final double[] LOOK_CURRENT_PITCH = new double[LOOK_CAPACITY];
    /**
     * The entity tick each row was taken at.
     *
     * <p>Needed because samples are per frame and frames do not arrive evenly — when the mob leaves
     * the screen no frame renders it at all, so a run's <em>average</em> frames-per-tick can be far
     * from the rate in any particular stretch. Converting a lag measured in samples into ticks with
     * that average is then wrong by whatever the burstiness was. With the tick on every row the
     * conversion is exact, and the recurrence itself becomes checkable off the log:
     * {@code error} must fall by {@code (1-k)^Δtick} between consecutive rows while target is held.
     */
    private static final int[] LOOK_TICK = new int[LOOK_CAPACITY];
    private static int lookSamples = 0;

    private static final double[] LOOK_ERR_MAX = new double[2];
    private static final double[] LOOK_DAMPING_MIN = new double[2];
    private static final double[] LOOK_DAMPING_MAX = new double[2];
    private static double lookDistanceMin = Double.POSITIVE_INFINITY;
    private static double lookDistanceMax = Double.NEGATIVE_INFINITY;

    private static final double[] LOOK_PENDING = new double[4];
    private static int lookPendingTick = 0;
    private static boolean lookPendingValid = false;
    private static int lookFirstTick = Integer.MIN_VALUE;
    private static int lookLastTick = Integer.MIN_VALUE;

    /**
     * Called by the model once per frame, before {@link #sample}.
     *
     * <p>Stashed rather than appended, so the series gets exactly one row per sample no matter how
     * many frames the model draws between them. Appending here instead would silently make the
     * "series" framerate-dependent and its row spacing meaningless.
     */
    public static void noteLook(double[] target, double[] current, double damping,
                                double distance, int tickCount) {
        if (remainingTicks <= 0) {
            return;
        }
        // sample() runs per FRAME — `remainingTicks` counts frames despite the name, so
        // sampleCount/totalTicks is identically 1 and says nothing. The entity's own tick counter
        // is the only clock here that actually ticks, so the frames-per-tick ratio comes from it.
        if (lookFirstTick == Integer.MIN_VALUE) {
            lookFirstTick = tickCount;
        }
        lookLastTick = tickCount;
        LOOK_PENDING[0] = target[0];
        LOOK_PENDING[1] = current[0];
        LOOK_PENDING[2] = target[1];
        LOOK_PENDING[3] = current[1];
        lookPendingTick = tickCount;
        lookPendingValid = true;
        for (int i = 0; i < 2; i++) {
            LOOK_ERR_MAX[i] = Math.max(LOOK_ERR_MAX[i], Math.abs(target[i] - current[i]));
        }
        // Both slots hold the same value today; kept as a pair so a future per-axis coefficient
        // does not need the report rewritten.
        for (int i = 0; i < 2; i++) {
            LOOK_DAMPING_MIN[i] = Math.min(LOOK_DAMPING_MIN[i], damping);
            LOOK_DAMPING_MAX[i] = Math.max(LOOK_DAMPING_MAX[i], damping);
        }
        if (distance >= 0) {
            lookDistanceMin = Math.min(lookDistanceMin, distance);
            lookDistanceMax = Math.max(lookDistanceMax, distance);
        }
    }

    public static void start(int ticks) {
        EntityLock.reset();
        remainingTicks = ticks;
        totalTicks = ticks;
        startTick = Integer.MIN_VALUE;
        sampleCount = 0;
        java.util.Arrays.fill(SPRING_SEEN, false);
        java.util.Arrays.fill(SPRING_SAMPLES, 0);
        java.util.Arrays.fill(SPRING_SPLIT_MAX, 0.0D);
        pendingRightValid = false;
        for (int side = 0; side < SIDES; side++) {
            java.util.Arrays.fill(CLAMP_HITS[side], 0);
            java.util.Arrays.fill(SPRING_LAG_MAX[side], 0.0D);
            java.util.Arrays.fill(SPRING_LAG_SUMSQ[side], 0.0D);
            java.util.Arrays.fill(SPRING_LAG_N[side], 0);
        }
        lookSamples = 0;
        walkFrames = 0;
        fadeCount = 0;
        moveSumBlocks = 0.0D;
        moveTicks = 0;
        moveMaxPerTick = 0.0D;
        moveLastX = Double.NaN;
        moveLastZ = Double.NaN;
        moveLastTick = Integer.MIN_VALUE;
        deltaMovementSum = 0.0D;
        slipCount = 0;
        slipLastX = Double.NaN;
        slipLastZ = Double.NaN;
        slipLastFoot = Double.NaN;
        slipLastWeight = Double.NaN;
        slipLastSwing = Double.NaN;
        slipLastPhase = Double.NaN;
        hurtStarts = 0;
        hurtFrames = 0;
        hurtMaxWeight = 0.0D;
        HURT_BONES.clear();
        HURT_MAX.clear();
        sniffStarts = 0;
        sniffFrames = 0;
        sniffMaxWeight = 0.0D;
        SNIFF_BONES.clear();
        SNIFF_MAX.clear();
        sniffDistMin = Double.POSITIVE_INFINITY;
        sniffDistMax = Double.NEGATIVE_INFINITY;
        sniffFlips = 0;
        sniffBlocked = 0;
        sniffFirstVal = Double.NaN;
        sniffLastVal = Double.NaN;
        pendingDist = Double.NaN;
        pendingPhase = Double.NaN;
        pendingLegAmp = Double.NaN;
        pendingClipRaw = Double.NaN;
        pendingWeight = Double.NaN;
        pendingSwing = Double.NaN;
        walkTransitions = 0;
        lastWalkState = null;
        lastRawMoving = null;
        graceCount = 0;
        rawFallingEdges = 0;
        lookPendingValid = false;
        lookFirstTick = Integer.MIN_VALUE;
        lookLastTick = Integer.MIN_VALUE;
        seriesCount = 0;
        turnLeanMin = Double.POSITIVE_INFINITY;
        turnLeanMax = Double.NEGATIVE_INFINITY;
        turnRateMin = Double.POSITIVE_INFINITY;
        turnRateMax = Double.NEGATIVE_INFINITY;
        java.util.Arrays.fill(LOOK_ERR_MAX, 0.0D);
        java.util.Arrays.fill(LOOK_DAMPING_MIN, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(LOOK_DAMPING_MAX, Double.NEGATIVE_INFINITY);
        lookDistanceMin = Double.POSITIVE_INFINITY;
        lookDistanceMax = Double.NEGATIVE_INFINITY;
        MIN.clear();
        MAX.clear();
        FIRST.clear();
        LAST.clear();
        MONOTONIC.clear();
        FOOT.clear();
        WardenGirlMod.LOGGER.info("[trace] 시작 — {}틱 동안 본 10개 × 3축 전부 기록한다", ticks);
    }

    public static boolean isRunning() {
        return remainingTicks > 0;
    }

    /**
     * One sample.
     *
     * @param rotations bone -> {x,y,z} in degrees, all ten bones
     * @param positions bone -> {x,y,z} in model pixels, all ten bones
     */
    public static void sample(Map<String, double[]> rotations, Map<String, double[]> positions,
                              int tickCount) {
        if (remainingTicks <= 0) {
            return;
        }
        // The window is counted in TICKS, not in frames. It used to decrement once per call, i.e.
        // once per rendered frame — so a `trace 240` covered 94 ticks on one run and 2730 on
        // another as the software renderer's framerate wandered between 2.55 and 0.55 frames per
        // tick. Every window then sampled a different slice of the stimulus, which makes two runs
        // uncomparable: the whole point of the T4 measurement is to hold the input fixed and vary
        // one coefficient.
        if (startTick == Integer.MIN_VALUE) {
            startTick = tickCount;
        }
        sampleCount++;
        remainingTicks = totalTicks - (tickCount - startTick);
        if (remainingTicks < 0) {
            remainingTicks = 0;
        }

        for (Map.Entry<String, double[]> e : rotations.entrySet()) {
            String bone = e.getKey();
            double[] v = e.getValue();
            double[] mn = MIN.computeIfAbsent(bone, k -> new double[]{v[0], v[1], v[2]});
            double[] mx = MAX.computeIfAbsent(bone, k -> new double[]{v[0], v[1], v[2]});
            double[] last = LAST.get(bone);
            int[] mono = MONOTONIC.computeIfAbsent(bone, k -> new int[]{0, 0, 0, 0, 0, 0});
            FIRST.computeIfAbsent(bone, k -> new double[]{v[0], v[1], v[2]});
            for (int i = 0; i < 3; i++) {
                mn[i] = Math.min(mn[i], v[i]);
                mx[i] = Math.max(mx[i], v[i]);
                if (last != null) {
                    if (v[i] > last[i] + 1e-6) {
                        mono[i * 2]++;
                    } else if (v[i] < last[i] - 1e-6) {
                        mono[i * 2 + 1]++;
                    }
                }
            }
            LAST.put(bone, new double[]{v[0], v[1], v[2]});
        }

        if (seriesCount < SERIES_CAP) {
            for (int i = 0; i < SERIES_BONES.length; i++) {
                double[] v = rotations.get(SERIES_BONES[i]);
                SERIES[i][seriesCount] = v == null ? 0.0D : v[SERIES_AXIS[i]];
            }
            SERIES_TICK[seriesCount] = tickCount;
            SERIES_WALK[seriesCount] = Boolean.TRUE.equals(lastWalkState);
            seriesCount++;
        }

        if (lookPendingValid && lookSamples < LOOK_CAPACITY) {
            LOOK_TARGET_YAW[lookSamples] = LOOK_PENDING[0];
            LOOK_CURRENT_YAW[lookSamples] = LOOK_PENDING[1];
            LOOK_TARGET_PITCH[lookSamples] = LOOK_PENDING[2];
            LOOK_CURRENT_PITCH[lookSamples] = LOOK_PENDING[3];
            LOOK_TICK[lookSamples] = lookPendingTick;
            lookSamples++;
        }

        accumulateFoot(Bones.LEG_RIGHT, rotations, positions);
        accumulateFoot(Bones.LEG_LEFT, rotations, positions);

        if (remainingTicks == 0) {
            report(positions);
        }
    }

    // ---- foot displacement -------------------------------------------------------------------

    // Model pivots and cube extents, straight out of warden_girl.geo.json. Part 3.7.
    private static final double[] PIVOT_ROOT = {0, 0, 0};
    private static final double[] PIVOT_HIP = {0, 12, 0};
    private static final double[] PIVOT_LEG_R = {-2.0, 12, 0};
    private static final double[] PIVOT_LEG_L = {2.0, 12, 0};
    /**
     * Absolute model y of the sole. The leg cube spans y 0..12 and its pivot is at y=12, so the
     * sole sits 12px below the leg pivot — that 12px is the lever the whole check exists to expose.
     */
    private static final double SOLE_Y = 0.0D;

    /**
     * The four corners of one sole, not its centre.
     *
     * <p>The centre lies <em>on</em> the leg's own y axis, so {@code leg.yRot} moves it by exactly
     * zero — the 안짱 offset of ±2° measured 0.0000px, which is geometrically correct and
     * completely uninformative. What reads as sliding on screen is the corner sweeping. A
     * centre-only measure is structurally blind to every yaw of the foot, and T5's walk cycle turns
     * the legs on y. So all four corners are tracked and the largest displacement wins.
     *
     * <p>Labels are in the mob's frame: the mob faces +Z and its left is +X, so F/B is z = +2/−2
     * and L/R is the larger/smaller x of that leg's cube.
     */
    private static final String[] CORNER_LABELS = {"FL", "FR", "BL", "BR"};

    /** Corner offsets in absolute model x/z for one leg, ordered to match {@link #CORNER_LABELS}. */
    private static double[][] soleCorners(String leg) {
        // leg_right cube x = [-4, 0], leg_left cube x = [0, 4]; both z = [-2, +2].
        double xMin = leg.equals(Bones.LEG_RIGHT) ? -4.0D : 0.0D;
        double xMax = leg.equals(Bones.LEG_RIGHT) ? 0.0D : 4.0D;
        return new double[][]{
                {xMax, SOLE_Y, 2.0D},   // FL
                {xMin, SOLE_Y, 2.0D},   // FR
                {xMax, SOLE_Y, -2.0D},  // BL
                {xMin, SOLE_Y, -2.0D},  // BR
        };
    }

    private static double[] pivotOf(String leg) {
        return leg.equals(Bones.LEG_RIGHT) ? PIVOT_LEG_R : PIVOT_LEG_L;
    }

    private static void accumulateFoot(String leg, Map<String, double[]> rot,
                                       Map<String, double[]> pos) {
        double[][] rest = soleCorners(leg);
        // {maxAbsDx, maxAbsDy, maxAbsDz, maxHorizontal, worstCornerIndex}
        double[] acc = FOOT.computeIfAbsent(leg, k -> new double[]{0, 0, 0, 0, -1});
        for (int c = 0; c < rest.length; c++) {
            double[] p = transformCorner(leg, rest[c], rot, pos);
            double dx = p[0] - rest[c][0];
            double dy = p[1] - rest[c][1];
            double dz = p[2] - rest[c][2];
            double horiz = Math.hypot(dx, dz);
            acc[0] = Math.max(acc[0], Math.abs(dx));
            acc[1] = Math.max(acc[1], Math.abs(dy));
            acc[2] = Math.max(acc[2], Math.abs(dz));
            if (horiz > acc[3]) {
                acc[3] = horiz;
                acc[4] = c;
            }
        }
    }

    /**
     * Forward kinematics for one sole corner, in model pixels.
     *
     * <p>The chain is root → hip → leg, which is exactly what
     * {@code GeoRenderer.renderRecursively} walks. Each bone contributes
     * {@code T(pos) · T(pivot) · R · T(-pivot)}, verified from
     * {@code RenderUtils.translateMatrixToBone} / {@code translateToPivotPoint} /
     * {@code rotateMatrixAroundBone} / {@code translateAwayFromPivotPoint} in the 4.8.4 bytecode.
     *
     * <p>Note the x negation on positions: {@code translateMatrixToBone} emits
     * {@code translate(-posX/16, +posY/16, +posZ/16)} while {@code translateToPivotPoint} emits
     * {@code translate(+pivotX/16, ...)}, so a bone's position x runs opposite to its pivot x.
     * Nothing in C1 writes a position x, so this cannot affect today's numbers — it is here so the
     * measurement stays correct when T5 or T9 does.
     */
    private static double[] transformCorner(String leg, double[] restPoint,
                                            Map<String, double[]> rot, Map<String, double[]> pos) {
        double[] q = {restPoint[0], restPoint[1], restPoint[2]};
        q = applyBone(q, leg, pivotOf(leg), rot, pos);
        q = applyBone(q, Bones.HIP, PIVOT_HIP, rot, pos);
        q = applyBone(q, Bones.ROOT, PIVOT_ROOT, rot, pos);
        return q;
    }

    private static double[] applyBone(double[] q, String bone, double[] pivot,
                                      Map<String, double[]> rot, Map<String, double[]> pos) {
        double[] r = rot.getOrDefault(bone, new double[]{0, 0, 0});
        double[] t = pos.getOrDefault(bone, new double[]{0, 0, 0});
        double[] v = {q[0] - pivot[0], q[1] - pivot[1], q[2] - pivot[2]};
        v = rotX(v, r[0]);
        v = rotY(v, r[1]);
        v = rotZ(v, r[2]);
        return new double[]{
                v[0] + pivot[0] - t[0],
                v[1] + pivot[1] + t[1],
                v[2] + pivot[2] + t[2]};
    }

    // The three rotations of the Part 4.0.1 convention, written so that the point directly above
    // the pivot moves toward the mob's back for +xRot and toward the mob's left for +zRot, and the
    // point directly in front of the pivot moves to the mob's left for +yRot. Composition order is
    // Rz·Ry·Rx — i.e. x applied to the vector first — matching rotateMatrixAroundBone's
    // mulPose(Z), mulPose(Y), mulPose(X).

    private static double[] rotX(double[] v, double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[]{v[0], v[1] * c + v[2] * s, -v[1] * s + v[2] * c};
    }

    private static double[] rotY(double[] v, double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }

    private static double[] rotZ(double[] v, double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[]{v[0] * c + v[1] * s, -v[0] * s + v[1] * c, v[2]};
    }

    /**
     * Idle tolerance for the sole, in model pixels. 1px is one texture pixel; anything below that
     * cannot be seen. This is a standing pose — the feet are supposed to be nailed down — so the
     * bar is a tenth of that.
     */
    private static final double FOOT_IDLE_LIMIT_PX = 0.10D;

    // ---- expectations ------------------------------------------------------------------------

    /**
     * @param periodTicks the period governing this axis, or 0 for a constant. The drift check needs
     *                    it: "moved in one direction for the whole window" only implies
     *                    accumulation if the window actually covered a full cycle. It does not
     *                    otherwise — a slow sine sampled over less than one period is monotonic by
     *                    construction. Ignoring this produced a false FAIL on hip zRot at preset
     *                    max, where the weight-shift period stretches to 316 ticks and the trace
     *                    window was 300.
     */
    private record Expect(String kind, double centre, double amplitude, double periodTicks) {
        static Expect constant(double v) {
            return new Expect("CONST", v, 0.0D, 0.0D);
        }

        static Expect band(double centre, double amplitude, double periodTicks) {
            return new Expect("RANGE", centre, amplitude, periodTicks);
        }

        /**
         * A band with no period at all — the vanilla look goals are driven by a random timer, so
         * "moved one way for the whole window" carries no information about accumulation. Marked
         * rather than silently exempted: the verdict column says so on every line.
         */
        static Expect aperiodic(double centre, double amplitude) {
            return new Expect("RANGE", centre, amplitude, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * What each axis is supposed to do, derived from {@link AnimParams} so the check follows the
     * parameters instead of duplicating them.
     */
    private static Map<String, Expect[]> expectations() {
        double breathBody = AnimParams.BREATH_BODY_X.get();
        double breathHead = AnimParams.BREATH_HEAD_X.get();
        double armRZ = AnimParams.BREATH_ARM_R_Z.get();
        double armLZ = AnimParams.BREATH_ARM_L_Z.get();
        double swayBodyZ = AnimParams.SWAY_BODY_Z.get();
        double swayBodyY = AnimParams.SWAY_BODY_Y.get();
        double swayHead = AnimParams.SWAY_HEAD_Z.get();
        double weight = AnimParams.WEIGHT_SHIFT_AMP.get();
        double weightHead = AnimParams.WEIGHT_SHIFT_HEAD_Z.get();
        double pBreath = AnimParams.BREATH_PERIOD.get();
        double pSway = AnimParams.SWAY_PERIOD.get();
        double pWeight = AnimParams.WEIGHT_SHIFT_PERIOD.get();

        Map<String, Expect[]> m = new LinkedHashMap<>();
        m.put(Bones.ROOT, new Expect[]{Expect.constant(0), Expect.constant(0), Expect.constant(0)});
        // 4.0.5: hip does not rotate while standing. Both of its former channels moved to body,
        // because hip carries the legs and body does not.
        m.put(Bones.HIP, new Expect[]{
                Expect.constant(0), Expect.constant(0), Expect.constant(0)});
        m.put(Bones.BODY, new Expect[]{
                Expect.band(AnimParams.OFFSET_BODY_X.get(), breathBody, pBreath),
                Expect.band(0, swayBodyY, pSway),
                // 4.3.3 sway and 4.3.5 weight shift are summed into this one axis; the band is the
                // sum of both amplitudes and the slower period governs a full cycle.
                Expect.band(0, swayBodyZ + weight, Math.max(pSway, pWeight))});
        // head now carries 4.6's look on top of C1: xRot = 정적 오프셋 + 호흡 + headPitch,
        // yRot = look only (C1 writes a literal 0 there). Both bands widen by the look clamp.
        double lookYaw = AnimParams.LOOK_YAW_MAX.get();
        double lookPitch = AnimParams.LOOK_PITCH_MAX.get();
        m.put(Bones.HEAD, new Expect[]{
                Expect.aperiodic(AnimParams.OFFSET_HEAD_X.get(), breathHead + lookPitch),
                Expect.aperiodic(0, lookYaw),
                Expect.band(0, swayHead + weightHead, Math.max(pSway, pWeight))});
        // 4.5 spring. Output is clamped to ±MAX_ANGLE by construction, so the band is that clamp —
        // a value outside it means the clamp itself is broken. The nominal period of the discrete
        // oscillator is 2π/√STIFFNESS ticks (≈12.6 at 0.25), which is what the drift guard needs.
        double springPeriod = 2 * Math.PI / Math.sqrt(Math.max(1e-6, AnimParams.HEADGEAR_STIFFNESS.get()));
        double springMax = Math.abs(AnimParams.HEADGEAR_MAX_ANGLE.get());
        // The bone carries the BASE POSE plus the spring output, so the band has to be centred on
        // the base. Centring it on zero produced two FAILs the moment headgear_splay was swept to
        // 90 — the check was measuring a value it did not model. xRot is centred on the tilt, zRot
        // on ±splay (outward is negative on the right tendril, positive on the left).
        double splay = AnimParams.HEADGEAR_SPLAY.get();
        double tilt = AnimParams.HEADGEAR_TILT.get();
        for (int i = 0; i < Bones.HEADGEAR.size(); i++) {
            double outward = i == 0 ? -1.0D : 1.0D;
            m.put(Bones.HEADGEAR.get(i), new Expect[]{
                    Expect.band(tilt, springMax, springPeriod),
                    Expect.band(0, springMax, springPeriod),
                    Expect.band(splay * outward, springMax, springPeriod)});
        }
        // arm zRot carries an outward-only bias: amp*(1+sin) spans 0 .. 2*amp.
        m.put(Bones.ARM_RIGHT, new Expect[]{
                Expect.constant(AnimParams.OFFSET_ARM_R_X.get()),
                Expect.constant(0),
                Expect.band(armRZ, Math.abs(armRZ), pBreath)});
        m.put(Bones.ARM_LEFT, new Expect[]{
                Expect.constant(AnimParams.OFFSET_ARM_L_X.get()),
                Expect.constant(0),
                Expect.band(armLZ, Math.abs(armLZ), pBreath)});
        m.put(Bones.LEG_RIGHT, new Expect[]{
                Expect.constant(0),
                Expect.constant(AnimParams.OFFSET_LEG_R_Y.get()),
                Expect.constant(0)});
        m.put(Bones.LEG_LEFT, new Expect[]{
                Expect.constant(0),
                Expect.constant(AnimParams.OFFSET_LEG_L_Y.get()),
                Expect.constant(0)});
        return m;
    }

    private static final double TOLERANCE = 0.01D;
    private static final String[] AXIS = {"xRot", "yRot", "zRot"};

    // ---- 측정 유효 조건 (Part 6.3) --------------------------------------------------------------

    private static int walkFrames = 0;
    private static int walkTransitions = 0;
    private static Boolean lastWalkState = null;

    /**
     * One frame's C2 state, recorded so the report can say whether this window contained the
     * situation it was meant to measure.
     *
     * <p>Part 6.3: a window that never saw the stimulus produces clean-looking numbers that mean
     * nothing. Measured instance — a C1 walking window with 0 walking frames printed "6채널 전부
     * 잔차 0", which reads as a pass and was in fact a mob standing still.
     */
    public static void noteWalkState(boolean walking, boolean rawMoving, int lastMovingTick,
                                     int tickCount) {
        if (remainingTicks <= 0) {
            return;
        }
        if (walking) {
            walkFrames++;
        }
        if (lastWalkState != null && lastWalkState != walking) {
            walkTransitions++;
            if (!walking && graceCount < GRACE_CAP) {
                // The grace the hysteresis actually applied: how many ticks passed between the
                // last tick the raw threshold said "moving" and the tick the walk animation was
                // switched off. Spec is walk_stop_grace. Never measured until now.
                GRACE_OFF_TICK[graceCount] = tickCount;
                GRACE_LAST_MOVING[graceCount] = lastMovingTick;
                graceCount++;
            }
        }
        if (lastRawMoving != null && lastRawMoving != rawMoving && !rawMoving) {
            rawFallingEdges++;
        }
        lastRawMoving = rawMoving;
        lastWalkState = walking;
    }

    // ---- 이동 속도 (문제 3 조사) ----------------------------------------------------------------

    /**
     * Horizontal distance travelled per tick while the walk animation is playing.
     *
     * <p>Blocks per tick, measured from the entity's own position — not from the
     * {@code MOVEMENT_SPEED} attribute. The attribute is an input to the movement solver, not the
     * resulting speed: friction, the pathfinder's own speed modifier and per-tick acceleration all
     * sit between them. 4.4.2 fixes the walk cycle at 26 ticks, so the number that decides whether
     * the feet slide is this one, and it has never been measured.
     *
     * <p>Sampled once per entity tick, not per frame — the position only changes on a tick, so a
     * per-frame sample would report zero for most frames and inflate the count.
     */
    private static double moveSumBlocks = 0.0D;
    private static int moveTicks = 0;
    private static double moveMaxPerTick = 0.0D;
    private static double moveLastX = Double.NaN;
    private static double moveLastZ = Double.NaN;
    private static int moveLastTick = Integer.MIN_VALUE;
    private static double deltaMovementSum = 0.0D;

    /**
     * C2's two amplitude factors, stashed as the model computes them so the slip rows can carry
     * them. Both scale the <em>drawn</em> leg, but only one of them scales the phase advance — which
     * is precisely the asymmetry {@link #reportSlipConditions} exists to measure.
     */
    private static double pendingWeight = Double.NaN;
    private static double pendingSwing = Double.NaN;
    private static double pendingDist = Double.NaN;
    private static double pendingPhase = Double.NaN;
    private static double pendingLegAmp = Double.NaN;
    private static double pendingClipRaw = Double.NaN;

    /**
     * Everything C2 used this frame, so the 8% residual can be attributed rather than guessed.
     *
     * <p>The shortfall survived removing the staircase at the same size (measured/ceiling 0.895 and
     * 0.917, against 0.917 and 0.926 before), which means it is a <b>uniform scale</b> and not a
     * resolution effect. A uniform scale can enter at exactly three places, and each gets its own
     * column here:
     *
     * <ul>
     *   <li><b>the distance path</b> — {@code distanceUsed} is what the phase consumed. The trace
     *       measures the body independently from the same rendered position, so the two sums must
     *       be identical. If they are not, the phase is being fed a different travel than the one
     *       the ratio divides by, and that alone is the answer.</li>
     *   <li><b>the phase math</b> — {@code Δphase × perCycle / 26} must equal {@code distanceUsed}.
     *       This is the {@code walk_cycle_scale} / {@code legAmp} route.</li>
     *   <li><b>the amplitude path</b> — {@code drawn / (clipRaw × swing × weight)} must be exactly
     *       1. This is where {@code walk_leg_amp_scale} applied at one place and not the other, or
     *       a {@code swingAmount} read at two different moments, would show up.</li>
     * </ul>
     *
     * @param clipRawDeg the walk clip's own {@code leg_right} x value at this phase, before any
     *                   amplitude scaling — the only way to separate "the clip is not ±18" from
     *                   "something scaled it"
     */
    public static void noteLocoState(double weight, double swingAmount, double distanceUsed,
                                     double phase, double legAmpDeg, double clipRawDeg) {
        if (remainingTicks <= 0) {
            return;
        }
        pendingWeight = weight;
        pendingSwing = swingAmount;
        pendingDist = distanceUsed;
        pendingPhase = phase;
        pendingLegAmp = legAmpDeg;
        pendingClipRaw = clipRawDeg;
    }

    /**
     * One row per walking <b>frame</b>: how far the body went, how far the drawn sole went, and the
     * two amplitude factors in force across that interval.
     *
     * <p>The aggregate ratio measured 0.6979 where the geometry says 1.0, and after the offline
     * integration ruled the curve shape out (1.0176, wrong direction) the remaining suspects are
     * both <em>amplitude</em> factors. {@code weight} scales the drawn pose but not the phase
     * advance, so during a fade-in the foot is drawn small while the phase runs at full rate.
     * {@code limbSwingAmount} scales both — but the phase advance at tick {@code t} uses that tick's
     * value while the foot's Δ spans {@code t−1 → t}, so a <em>changing</em> swing contributes a
     * term that is not translation at all. Separating them needs the two recorded per tick, not
     * summed away.
     *
     * <h2>Per frame, since the phase went per frame</h2>
     *
     * This used to sample once per tick, which was exactly right while the phase itself only moved
     * once per tick — the drawn angle was a staircase and tick-boundary samples captured its total
     * variation exactly. Now the phase advances every frame, so a per-tick sample would inscribe a
     * <em>second</em> polygon inside the first and under-report the foot path by the very factor the
     * fix removes. The measurement would then show no improvement however well the fix worked.
     *
     * <p>Both terms move to frame rate together. The body term loses nothing by it: the rendered
     * position is linear in {@code partialTick} between ticks, so the per-frame chords sum to the
     * per-tick distance exactly.
     *
     * <p><b>The frame rate is now the ceiling.</b> What the eye sees is a polygon with
     * (frames/tick × ticks/cycle) vertices per cycle, and that polygon is genuinely shorter than the
     * curve — it is not a measurement artifact, it is what is drawn. So the achievable ratio is
     * {@code 1.018 × retention(frames per cycle)}, and the report prints the frames/tick it ran at
     * so the ceiling can be computed rather than guessed.
     */
    private static final int SLIP_CAP = 4000;
    private static final int[] SLIP_TICK = new int[SLIP_CAP];
    private static final double[] SLIP_BODY = new double[SLIP_CAP];
    private static final double[] SLIP_FOOT = new double[SLIP_CAP];
    private static final double[] SLIP_W0 = new double[SLIP_CAP];
    private static final double[] SLIP_W1 = new double[SLIP_CAP];
    private static final double[] SLIP_S0 = new double[SLIP_CAP];
    private static final double[] SLIP_S1 = new double[SLIP_CAP];
    /** Distance the phase actually consumed this frame. */
    private static final double[] SLIP_DIST = new double[SLIP_CAP];
    /** Phase before / after, in clip ticks, and the cycle distance the phase math used. */
    private static final double[] SLIP_PH0 = new double[SLIP_CAP];
    private static final double[] SLIP_PH1 = new double[SLIP_CAP];
    private static final double[] SLIP_PERCYCLE = new double[SLIP_CAP];
    /** Clip raw leg_right x, and the value actually written to the bone. Both degrees. */
    private static final double[] SLIP_CLIP = new double[SLIP_CAP];
    private static final double[] SLIP_DRAWN = new double[SLIP_CAP];
    private static int slipCount = 0;

    /**
     * @param footArcNow the drawn sole offset from under the hip, in blocks
     *                   ({@code 12 · sin(leg xRot) / 16})
     */
    public static void noteMovement(double x, double z, double deltaMovementHoriz,
                                    double footArcNow, boolean walking, int tickCount) {
        if (remainingTicks <= 0 || tickCount == moveLastTick) {
            return;
        }
        if (!Double.isNaN(moveLastX) && walking && tickCount == moveLastTick + 1) {
            double dx = x - moveLastX;
            double dz = z - moveLastZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            moveSumBlocks += d;
            deltaMovementSum += deltaMovementHoriz;
            moveMaxPerTick = Math.max(moveMaxPerTick, d);
            moveTicks++;
        }
        moveLastX = x;
        moveLastZ = z;
        moveLastTick = tickCount;
    }

    /** A jump larger than this is a teleport, not locomotion. Same bar as the phase itself uses. */
    private static final double SLIP_TELEPORT_BLOCKS = 0.5D;

    private static double slipLastX = Double.NaN;
    private static double slipLastZ = Double.NaN;
    private static double slipLastFoot = Double.NaN;
    private static double slipLastWeight = Double.NaN;
    private static double slipLastSwing = Double.NaN;
    private static double slipLastPhase = Double.NaN;

    /**
     * One walking frame of the slip measurement.
     *
     * @param x,z         the <b>rendered</b> horizontal position — the same
     *                    {@code Mth.lerp(partialTick, xOld, getX())} the phase is driven from, so
     *                    numerator and denominator describe the same drawn motion
     * @param footArcNow  the drawn sole offset from under the hip, in blocks
     */
    public static void noteFrameSlip(double x, double z, double footArcNow, double drawnDeg,
                                     boolean walking, int tickCount) {
        if (remainingTicks <= 0) {
            return;
        }
        if (!walking) {
            // Drop the anchor. Bridging an idle stretch would charge the whole standing interval's
            // body travel against a foot that was deliberately frozen.
            slipLastX = Double.NaN;
            return;
        }
        if (!Double.isNaN(slipLastX) && !Double.isNaN(slipLastFoot)
                && !Double.isNaN(slipLastWeight) && !Double.isNaN(pendingWeight)) {
            double dx = x - slipLastX;
            double dz = z - slipLastZ;
            double body = Math.sqrt(dx * dx + dz * dz);
            if (body <= SLIP_TELEPORT_BLOCKS && slipCount < SLIP_CAP) {
                SLIP_TICK[slipCount] = tickCount;
                SLIP_BODY[slipCount] = body;
                SLIP_FOOT[slipCount] = Math.abs(footArcNow - slipLastFoot);
                SLIP_W0[slipCount] = slipLastWeight;
                SLIP_W1[slipCount] = pendingWeight;
                SLIP_S0[slipCount] = slipLastSwing;
                SLIP_S1[slipCount] = pendingSwing;
                SLIP_DIST[slipCount] = pendingDist;
                SLIP_PH0[slipCount] = slipLastPhase;
                SLIP_PH1[slipCount] = pendingPhase;
                SLIP_PERCYCLE[slipCount] = 2.0D * (2.0D * 12.0D
                        * Math.sin(Math.toRadians(Math.abs(pendingLegAmp))) / 16.0D)
                        * AnimParams.WALK_CYCLE_SCALE.get();
                SLIP_CLIP[slipCount] = pendingClipRaw;
                SLIP_DRAWN[slipCount] = drawnDeg;
                slipCount++;
            }
        }
        slipLastX = x;
        slipLastZ = z;
        slipLastFoot = footArcNow;
        slipLastWeight = pendingWeight;
        slipLastSwing = pendingSwing;
        slipLastPhase = pendingPhase;
    }

    /**
     * Reports measured speed against the stride the 4.4.2 cycle geometrically implies.
     *
     * <p>Stride: the leg pivots at y=12px and the sole is at y=0, so a {@code ±18°} sweep moves the
     * sole {@code 2 × 12 × sin18° = 7.4164px = 0.46352 blocks}. The cycle contains two such sweeps
     * (one per leg), so a non-sliding body advances {@code 0.92703 blocks} per 26-tick cycle —
     * {@code 0.035655 blocks/tick}.
     */
    /**
     * Foot travel divided by body travel, over the same ticks.
     *
     * <p>Part 6.3.0.1: the raw slip distance depends on the walk phase and on how long the mob
     * happened to walk, so it cannot be compared between runs. The <b>ratio</b> can — it is 1.0
     * when the planted foot holds still relative to the ground, whatever the amplitude, whatever
     * the speed, however long the sample.
     *
     * <p>Foot travel is computed from the drawn leg angle, not from a bone position: the sole is
     * {@code 12 × sin(legAngle)} pixels from under the hip, so the ground-relative movement of a
     * planted foot is the body's travel minus that quantity's change. Summing |Δ| of
     * {@code 12·sin θ / 16} over the window and dividing by the body's travel gives the ratio
     * directly.
     */

    private static void reportMovement() {
        double stridePx = 2.0D * 12.0D * Math.sin(Math.toRadians(18.0D));
        double perCycle = 2.0D * stridePx / 16.0D;
        double required = perCycle / 26.0D;
        if (moveTicks < 10) {
            WardenGirlMod.LOGGER.info(
                    "[trace] --- 이동 속도: 걷기 중 틱 표본 {}개 — **측정 불가** (10틱 미만) ---", moveTicks);
            return;
        }
        double measured = moveSumBlocks / moveTicks;
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- 이동 속도 (문제 3). 걷기 중 %d틱 표본 ---", moveTicks));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   실측 이동      : %.5f 블록/틱  (틱당 최대 %.5f)", measured, moveMaxPerTick));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   deltaMovement  : %.5f 블록/틱 (참고. 클라이언트 속도 필드)",
                deltaMovementSum / moveTicks));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   4.4.2 보폭     : 다리 ±18° × 12px 레버 = 편도 %.4fpx, 한 걸음 %.4f블록",
                stridePx / 2.0D, stridePx / 16.0D));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   필요 이동      : 한 사이클(26틱)에 %.5f 블록 = %.5f 블록/틱", perCycle, required));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   어긋남         : 실측 / 필요 = %.3f 배  (1.0 이면 발이 미끄러지지 않는다)",
                required > 1e-9 ? measured / required : 0.0D));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   사이클 이론값  : 현재 속도라면 %.2f 틱 주기여야 한다 (±18° 기준)",
                measured > 1e-9 ? perCycle / measured : 0.0D));
    }

    // ---- 후보 1: 미끄러짐을 조건별로 분리 ---------------------------------------------------------

    /**
     * Minimum walking ticks a condition needs before its ratio is allowed to carry a verdict.
     *
     * <p>One nominal cycle. Below that the two sums cover different fractions of a cycle — the foot
     * term is a rectified sine and the body term is not — so the ratio is biased by wherever the
     * sample happened to start. Part 6.3.0: a condition that does not clear this bar prints
     * 측정 불가, not a number with a verdict attached.
     */
    private static final int SLIP_MIN_TICKS = 26;

    /** |Δ limbSwingAmount| below which the amplitude counts as steady across that tick. */
    private static final double SWING_STABLE = 0.01D;

    /**
     * Foot travel ÷ body travel under three nested conditions, with the sample count of each.
     *
     * <p>Geometry says 1.0; the offline integration (tools/FootPath) says the catmullrom overshoot
     * puts the achievable figure at <b>1.018</b>. The aggregate measured 0.6979. The two remaining
     * candidates are both amplitude factors and they are separated by nesting:
     *
     * <ul>
     *   <li><b>전체</b> — every walking tick. This is the 0.6979 figure.</li>
     *   <li><b>weight == 1</b> — fade excluded. The fade scales the drawn foot and not the phase, so
     *       if the fade is the cause this row goes to ~1.018 and the difference is its share.</li>
     *   <li><b>weight == 1 이고 limbSwingAmount 안정</b> — amplitude also held still. The phase
     *       advance uses the tick's own swing while the foot Δ spans the previous tick to this one,
     *       so a moving swing injects a term that is not translation. If this row is the one that
     *       reaches 1.018, the cause is that mismatch, not the fade.</li>
     * </ul>
     */
    private static void reportSlipConditions() {
        WardenGirlMod.LOGGER.info(
                "[trace] --- 발 이동 / 몸 이동, 조건 3단 (기하 목표 1.0, 곡선 보정 후 1.018) ---");
        if (slipCount == 0) {
            WardenGirlMod.LOGGER.info("[trace]   걷기 프레임 표본 0개 — **측정 불가**");
            return;
        }
        WardenGirlMod.LOGGER.info("[trace]   위상은 프레임마다 전진한다. 그래서 이 측정도 프레임 "
                + "단위다 — 틱 단위로 재면 고쳐진 곡선 안에 다시 다각형을 그어 효과를 지운다");
        double swingMin = Double.POSITIVE_INFINITY;
        double swingMax = Double.NEGATIVE_INFINITY;
        double weightMin = Double.POSITIVE_INFINITY;
        int fadeTicks = 0;
        for (int i = 0; i < slipCount; i++) {
            swingMin = Math.min(swingMin, Math.min(SLIP_S0[i], SLIP_S1[i]));
            swingMax = Math.max(swingMax, Math.max(SLIP_S0[i], SLIP_S1[i]));
            weightMin = Math.min(weightMin, Math.min(SLIP_W0[i], SLIP_W1[i]));
            if (SLIP_W0[i] < 1.0D - 1.0E-9D || SLIP_W1[i] < 1.0D - 1.0E-9D) {
                fadeTicks++;
            }
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   관측된 범위: weight 최소 %.5f (페이드 중 틱 %d개 / %d), "
                        + "limbSwingAmount %.5f ~ %.5f",
                weightMin, fadeTicks, slipCount, swingMin, swingMax));

        String[] name = {"전체", "weight == 1", "weight == 1 이고 swing 안정"};
        for (int cond = 0; cond < 3; cond++) {
            double body = 0.0D;
            double foot = 0.0D;
            double swingSum = 0.0D;
            int n = 0;
            for (int i = 0; i < slipCount; i++) {
                boolean full = SLIP_W0[i] >= 1.0D - 1.0E-9D && SLIP_W1[i] >= 1.0D - 1.0E-9D;
                boolean steady = Math.abs(SLIP_S1[i] - SLIP_S0[i]) <= SWING_STABLE;
                if (cond >= 1 && !full) {
                    continue;
                }
                if (cond >= 2 && !steady) {
                    continue;
                }
                body += SLIP_BODY[i];
                foot += SLIP_FOOT[i];
                swingSum += SLIP_S1[i];
                n++;
            }
            // The bar is a full walk cycle's worth of TIME, not a row count - rows are per frame
            // now, so a row count would pass or fail with the framerate rather than with coverage.
            //
            // DISTINCT ticks, not the span. The first version used hi-lo+1, which counts every idle
            // tick between two walking stretches: a window with two 20-tick walks 600 ticks apart
            // reported a 640-tick sample and a body speed diluted 16-fold. The ratio itself was
            // unaffected (it is a sum over sum), but every per-tick column derived from it was
            // wrong, and the coverage bar passed on stretches that had not earned it.
            int spanTicks = 0;
            int prev = Integer.MIN_VALUE;
            for (int i = 0; i < slipCount; i++) {
                boolean full = SLIP_W0[i] >= 1.0D - 1.0E-9D && SLIP_W1[i] >= 1.0D - 1.0E-9D;
                boolean steady = Math.abs(SLIP_S1[i] - SLIP_S0[i]) <= SWING_STABLE;
                if ((cond >= 1 && !full) || (cond >= 2 && !steady)) {
                    continue;
                }
                if (SLIP_TICK[i] != prev) {
                    spanTicks++;
                    prev = SLIP_TICK[i];
                }
            }
            if (spanTicks < SLIP_MIN_TICKS || n < 8 || body <= 1.0E-9D) {
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace]   %-28s 프레임 %4d, 걸친 틱 %3d (필요 %d) — **측정 불가**",
                        name[cond], n, spanTicks, SLIP_MIN_TICKS));
                continue;
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]   %-28s 프레임 %4d / 틱 %3d  발 %.5f / 몸 %.5f = **%.4f**  "
                            + "(몸 %.5f 블록/틱, swing 평균 %.4f, %.2f 프레임/틱)",
                    name[cond], n, spanTicks, foot, body, foot / body,
                    body / Math.max(1, spanTicks), swingSum / n, (double) n / Math.max(1, spanTicks)));
        }
        reportResidualAttribution();
        // Raw rows, thinned. Part 6.2: the ratios above are derived, and a derived number that
        // nobody can check against the values it came from is not a measurement.
        int step = Math.max(1, slipCount / 40);
        WardenGirlMod.LOGGER.info("[trace]   틱별 원값 (t, 몸 이동, 발 이동, weight, limbSwingAmount):");
        for (int i = 0; i < slipCount; i += step) {
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]     t=%6d  몸 %.5f  발 %.5f  비 %7.4f  w %.5f->%.5f  s %.5f->%.5f",
                    SLIP_TICK[i], SLIP_BODY[i], SLIP_FOOT[i],
                    SLIP_BODY[i] > 1e-9 ? SLIP_FOOT[i] / SLIP_BODY[i] : 0.0D,
                    SLIP_W0[i], SLIP_W1[i], SLIP_S0[i], SLIP_S1[i]));
        }
    }

    /**
     * Splits the 8% residual across the three places a uniform scale can enter.
     *
     * <p>Restricted to {@code weight == 1} and steady swing, because a fading or accelerating frame
     * has legitimate reasons to disagree and would blur every column.
     */
    private static void reportResidualAttribution() {
        double sumDist = 0.0D;
        double sumBody = 0.0D;
        double sumPhaseDist = 0.0D;
        double ampMin = Double.POSITIVE_INFINITY;
        double ampMax = Double.NEGATIVE_INFINITY;
        double ampSum = 0.0D;
        int ampN = 0;
        int n = 0;
        for (int i = 0; i < slipCount; i++) {
            if (SLIP_W0[i] < 1.0D - 1.0E-9D || SLIP_W1[i] < 1.0D - 1.0E-9D
                    || Math.abs(SLIP_S1[i] - SLIP_S0[i]) > SWING_STABLE) {
                continue;
            }
            n++;
            sumBody += SLIP_BODY[i];
            if (!Double.isNaN(SLIP_DIST[i])) {
                sumDist += SLIP_DIST[i];
            }
            // Phase wraps at 26; a wrapped step reads as a large negative, so fold it forward.
            double dPhase = SLIP_PH1[i] - SLIP_PH0[i];
            if (dPhase < 0.0D) {
                dPhase += LocomotionMotion.WALK_LENGTH_TICKS;
            }
            if (!Double.isNaN(dPhase) && SLIP_PERCYCLE[i] > 1.0E-9D) {
                sumPhaseDist += dPhase / LocomotionMotion.WALK_LENGTH_TICKS * SLIP_PERCYCLE[i];
            }
            // The amplitude chain, on frames where the clip value is big enough for the ratio to
            // mean anything - near a zero crossing it is 0/0 and says nothing.
            double expect = SLIP_CLIP[i] * SLIP_S1[i] * AnimParams.WALK_LEG_AMP_SCALE.get()
                    * SLIP_W1[i];
            if (Math.abs(expect) > 1.0D && !Double.isNaN(SLIP_DRAWN[i])) {
                double r = SLIP_DRAWN[i] / expect;
                ampMin = Math.min(ampMin, r);
                ampMax = Math.max(ampMax, r);
                ampSum += r;
                ampN++;
            }
        }
        WardenGirlMod.LOGGER.info("[trace] --- 잔차 8% 귀속 (weight==1 이고 swing 안정 프레임만) ---");
        if (n < 8) {
            WardenGirlMod.LOGGER.info("[trace]   표본 {} — **측정 불가**", n);
            return;
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   [1] 거리 경로 : 위상이 먹은 거리 %.5f / 트레이스가 잰 몸 %.5f = **%.4f**"
                        + "   (1.0 이어야 한다. 같은 렌더 위치를 쓴다)",
                sumDist, sumBody, sumBody > 1e-9 ? sumDist / sumBody : 0.0D));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   [2] 위상 수학 : Δ위상->거리 %.5f / 위상이 먹은 거리 %.5f = **%.4f**"
                        + "   (1.0 이어야 한다)",
                sumPhaseDist, sumDist, sumDist > 1e-9 ? sumPhaseDist / sumDist : 0.0D));
        if (ampN < 8) {
            WardenGirlMod.LOGGER.info("[trace]   [3] 진폭 경로 : 표본 {} — **측정 불가**", ampN);
        } else {
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]   [3] 진폭 경로 : 그려진각 / (클립원값 x swing x scale x weight) "
                            + "평균 **%.5f** (최소 %.5f 최대 %.5f, 표본 %d)   (1.0 이어야 한다)",
                    ampSum / ampN, ampMin, ampMax, ampN));
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   참고: 이 표본의 총 Δ위상 = %.2f 클립틱 = %.2f 사이클",
                sumPhaseDist > 1e-9 && sumDist > 1e-9 ? 0.0D : 0.0D,
                SLIP_PERCYCLE[0] > 1e-9 ? sumDist / SLIP_PERCYCLE[0] : 0.0D));
    }

    // ---- 4.11 피격 움찔 ---------------------------------------------------------------------

    private static int hurtStarts = 0;
    private static int hurtFrames = 0;
    private static double hurtMaxWeight = 0.0D;
    private static final java.util.Set<String> HURT_BONES = new java.util.TreeSet<>();
    private static final Map<String, double[]> HURT_MAX = new LinkedHashMap<>();

    /**
     * One frame of 4.11.
     *
     * <p>Records which bones the flinch ever wrote, not just how much — "다리와 hip 기여 0" is a
     * claim about the bone SET, and a max of 0.0 on a bone that was never in the map would look
     * identical to a bone that was written with zero. The set makes the two distinguishable.
     */
    public static void noteHurt(boolean started, double weight, Map<String, double[]> pose) {
        if (remainingTicks <= 0) {
            return;
        }
        if (started) {
            hurtStarts++;
        }
        if (pose == null) {
            return;
        }
        hurtFrames++;
        hurtMaxWeight = Math.max(hurtMaxWeight, weight);
        for (Map.Entry<String, double[]> e : pose.entrySet()) {
            HURT_BONES.add(e.getKey());
            double[] m = HURT_MAX.computeIfAbsent(e.getKey(), k -> new double[3]);
            for (int i = 0; i < 3; i++) {
                m[i] = Math.max(m[i], Math.abs(e.getValue()[i] * weight));
            }
        }
    }

    private static void reportHurt() {
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- 4.11 피격 움찔. 피격 %d회, 재생 프레임 %d, 최대 가중치 %.4f "
                        + "(hurt_amp_scale %.2f, fade %.1f/%.1f) ---",
                hurtStarts, hurtFrames, hurtMaxWeight, AnimParams.HURT_AMP_SCALE.get(),
                AnimParams.HURT_FADE_IN.get(), AnimParams.HURT_FADE_OUT.get()));
        if (hurtStarts == 0) {
            WardenGirlMod.LOGGER.info("[trace]   피격 0회 — **측정 불가**");
            return;
        }
        WardenGirlMod.LOGGER.info("[trace]   기여한 본: {}", HURT_BONES);
        for (String bone : new String[]{Bones.HIP, Bones.LEG_RIGHT, Bones.LEG_LEFT}) {
            WardenGirlMod.LOGGER.info("[trace]   {} 기여: {}", bone,
                    HURT_BONES.contains(bone) ? "**있다 — 사양 위반**" : "없음 (본 자체가 클립에 없다)");
        }
        HURT_MAX.forEach((bone, m) -> WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   %-10s 최대 |기여| x %.4f  y %.4f  z %.4f", bone, m[0], m[1], m[2])));
    }

    // ===== T6 반응형 idle. 폐기 시 이 블록 전체를 지운다 =====================================

    private static int sniffStarts = 0;
    private static int sniffFrames = 0;
    private static double sniffMaxWeight = 0.0D;
    private static final java.util.Set<String> SNIFF_BONES = new java.util.TreeSet<>();
    private static final Map<String, double[]> SNIFF_MAX = new LinkedHashMap<>();
    private static double sniffDistMin = Double.POSITIVE_INFINITY;
    private static double sniffDistMax = Double.NEGATIVE_INFINITY;
    private static int sniffFlips = 0;
    private static int sniffBlocked = 0;
    private static double sniffFirstVal = Double.NaN;
    private static double sniffLastVal = Double.NaN;

    /** Trigger-side state, sampled per frame. Does not advance the state machine. */
    public static void noteReaction(IdleReaction r, boolean started) {
        if (remainingTicks <= 0) {
            return;
        }
        if (started) {
            sniffStarts++;
        }
        double d = r.lastDistance();
        if (!Double.isNaN(d)) {
            sniffDistMin = Math.min(sniffDistMin, d);
            sniffDistMax = Math.max(sniffDistMax, d);
        }
        sniffFlips = r.hysteresisFlips();
        sniffBlocked = r.cooldownBlocked();
    }

    /** Pose side. {@code age} lets the 0-tick / last-tick requirement be checked by value. */
    public static void noteSniffPose(double age, double lengthTicks, double weight,
                                     Map<String, double[]> pose) {
        if (remainingTicks <= 0 || pose == null) {
            return;
        }
        sniffFrames++;
        sniffMaxWeight = Math.max(sniffMaxWeight, weight);
        double[] head = pose.get(Bones.HEAD);
        double v = head == null ? 0.0D : head[0] * weight;
        if (age <= 0.5D && Double.isNaN(sniffFirstVal)) {
            sniffFirstVal = v;
        }
        if (age >= lengthTicks - 0.5D) {
            sniffLastVal = v;
        }
        for (Map.Entry<String, double[]> e : pose.entrySet()) {
            SNIFF_BONES.add(e.getKey());
            double[] m = SNIFF_MAX.computeIfAbsent(e.getKey(), k -> new double[3]);
            for (int i = 0; i < 3; i++) {
                m[i] = Math.max(m[i], Math.abs(e.getValue()[i] * weight));
            }
        }
    }

    private static void reportSniff() {
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- 4.12 킁킁. 발동 %d회, 재생 프레임 %d, 최대 가중치 %.4f "
                        + "(sniff_distance %.2f, dwell %.0f, cooldown %.0f) ---",
                sniffStarts, sniffFrames, sniffMaxWeight, AnimParams.SNIFF_DISTANCE.get(),
                AnimParams.SNIFF_DWELL.get(), AnimParams.SNIFF_COOLDOWN.get()));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   거리 관측 %.3f ~ %.3f 블록 (진입 %.2f, 이탈 %.2f), 히스테리시스 전환 %d회, "
                        + "쿨다운이 막은 프레임 %d",
                sniffDistMin, sniffDistMax, AnimParams.SNIFF_DISTANCE.get(),
                AnimParams.SNIFF_DISTANCE.get() * IdleReaction.EXIT_FACTOR,
                sniffFlips, sniffBlocked));
        if (sniffStarts == 0) {
            WardenGirlMod.LOGGER.info("[trace]   발동 0회 — **측정 불가**");
            return;
        }
        WardenGirlMod.LOGGER.info("[trace]   기여한 본: {}", SNIFF_BONES);
        for (String bone : new String[]{Bones.HIP, Bones.LEG_RIGHT, Bones.LEG_LEFT}) {
            WardenGirlMod.LOGGER.info("[trace]   {} 기여: {}", bone,
                    SNIFF_BONES.contains(bone) ? "**있다 — 사양 위반**" : "없음 (본 자체가 클립에 없다)");
        }
        SNIFF_MAX.forEach((bone, m) -> WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   %-10s 최대 |기여| x %.4f  y %.4f  z %.4f", bone, m[0], m[1], m[2])));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   끝점: 0틱 부근 head.x %.5f, 마지막 틱 부근 head.x %.5f  (둘 다 0 이어야 한다)",
                sniffFirstVal, sniffLastVal));
    }

    // ===== T6 끝 ===========================================================================

    // ---- C2 페이드 (가설 2 확인) -----------------------------------------------------------------

    /**
     * The idle↔walk fade weight, with the frame delta that produced each step.
     *
     * <p>Hypothesis 2: the fade decays by {@code dt / 6} where {@code dt} is a <em>frame</em> delta,
     * so a frame that skips a tick drops the weight twice as far in one step. Observed frames
     * spanning two ticks ({@code t=3017 → 3019 → 3021}), so this is not speculative — but whether
     * it lands inside a stop transition, and how much of the 3.7~5.7× it accounts for, has to be
     * read off the values.
     */
    private static final int FADE_CAP = 4000;
    private static final double[] FADE_WEIGHT = new double[FADE_CAP];
    private static final double[] FADE_DT = new double[FADE_CAP];
    private static final double[] FADE_TIME = new double[FADE_CAP];
    private static int fadeCount = 0;

    public static void noteFade(double weight, double dt, double timeTicks) {
        if (remainingTicks <= 0 || fadeCount >= FADE_CAP) {
            return;
        }
        FADE_WEIGHT[fadeCount] = weight;
        FADE_DT[fadeCount] = dt;
        FADE_TIME[fadeCount] = timeTicks;
        fadeCount++;
    }

    /**
     * Every falling run of the fade, with how long it actually took.
     *
     * <p>Spec is {@link AnimRegistry#TRANSITION_TICKS}. A run that lands anywhere else is the
     * measurement hypothesis 2 predicts.
     */
    private static void reportFade() {
        if (fadeCount < 4) {
            return;
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- C2 페이드. 사양 %d틱 ---", AnimRegistry.TRANSITION_TICKS));
        int runs = 0;
        for (int i = 1; i < fadeCount && runs < 6; i++) {
            boolean startsFalling = FADE_WEIGHT[i] < FADE_WEIGHT[i - 1]
                    && FADE_WEIGHT[i - 1] >= 1.0D - 1.0E-9D;
            if (!startsFalling) {
                continue;
            }
            int j = i;
            while (j + 1 < fadeCount && FADE_WEIGHT[j] > 0.0D) {
                j++;
            }
            runs++;
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]   하강 %d: t=%.3f -> %.3f  실측 %.3f틱  (프레임 %d개)",
                    runs, FADE_TIME[i - 1], FADE_TIME[j], FADE_TIME[j] - FADE_TIME[i - 1],
                    j - i + 1));
            for (int k = i - 1; k <= j && k < fadeCount; k++) {
                double drop = k > 0 ? FADE_WEIGHT[k - 1] - FADE_WEIGHT[k] : 0.0D;
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace]      t=%9.3f  dt=%6.3f  가중치 %.5f  감소 %.5f%s",
                        FADE_TIME[k], FADE_DT[k], FADE_WEIGHT[k], drop,
                        FADE_DT[k] > 1.0D ? "   <== 프레임이 틱을 건너뛰었다" : ""));
            }
            i = j;
        }
    }

    private static final int GRACE_CAP = 32;
    private static final int[] GRACE_OFF_TICK = new int[GRACE_CAP];
    private static final int[] GRACE_LAST_MOVING = new int[GRACE_CAP];
    private static int graceCount = 0;
    private static int rawFallingEdges = 0;
    private static Boolean lastRawMoving = null;

    /**
     * Prints what the window actually contained, before any verdict.
     *
     * <p>Deliberately first: reading "걷기 프레임 0" after a page of OK verdicts is not the same
     * as reading it before them.
     */
    private static void reportValidity() {
        WardenGirlMod.LOGGER.info("[trace] --- 측정 유효 조건 (판정보다 먼저 본다) ---");
        WardenGirlMod.LOGGER.info("[trace]   엔티티          : {}", EntityLock.validityLine());
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   걷기 프레임      : %d / %d (%.1f%%)   %s",
                walkFrames, sampleCount, 100.0D * walkFrames / Math.max(1, sampleCount),
                walkFrames == 0 ? "정지만 관측 — 걷기 관련 판정은 측정 불가"
                        : walkFrames == sampleCount ? "걷기만 관측 — 정지 관련 판정은 측정 불가"
                        : "정지·걷기 모두 관측"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   idle<->걷기 전이 : %d회   %s",
                walkTransitions, walkTransitions == 0 ? "전이 판정은 측정 불가" : "전이 관측됨"));
        double yawSpan = range(LOOK_TARGET_YAW, lookSamples);
        double pitchSpan = range(LOOK_TARGET_PITCH, lookSamples);
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   시선 목표 변화폭 : yaw %.3f°  pitch %.3f°   %s",
                yawSpan, pitchSpan,
                Math.max(yawSpan, pitchSpan) < 1.0D
                        ? "시선이 거의 움직이지 않았다 — 4.6 감쇠 판정은 측정 불가"
                        : "시선 관측됨"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   원시 이동판정 하강 : %d회   (히스테리시스 이전. 이것이 %d회 걷기 종료로 걸러졌다)",
                rawFallingEdges, graceCount));
        if (graceCount > 0) {
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]   --- A(히스테리시스) 실효 유예. 사양 walk_stop_grace = %.1f틱 ---",
                    AnimParams.WALK_STOP_GRACE.get()));
            for (int i = 0; i < graceCount; i++) {
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace]     걷기 종료 t=%d, 마지막 이동판정 t=%d, 실효 유예 %d틱",
                        GRACE_OFF_TICK[i], GRACE_LAST_MOVING[i],
                        GRACE_OFF_TICK[i] - GRACE_LAST_MOVING[i]));
            }
        }
    }

    private static void report(Map<String, double[]> positions) {
        Map<String, Expect[]> expect = expectations();
        int fails = 0;

        WardenGirlMod.LOGGER.info("[trace] === {}틱 창에서 {}샘플(프레임) 수집 완료. 본 10개 × 3축 판정 ===",
                totalTicks, sampleCount);
        reportValidity();
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] %-10s %-5s %10s %10s %-6s %-26s %s",
                "본", "축", "최소", "최대", "종류", "기대", "판정"));

        for (String bone : Bones.ALL) {
            double[] mn = MIN.get(bone);
            double[] mx = MAX.get(bone);
            int[] mono = MONOTONIC.get(bone);
            if (mn == null) {
                WardenGirlMod.LOGGER.warn("[trace] {} — 샘플 없음 (본을 찾지 못했다)", bone);
                fails++;
                continue;
            }
            Expect[] ex = expect.get(bone);
            for (int i = 0; i < 3; i++) {
                Expect e = ex[i];
                double lo = e.centre - e.amplitude - TOLERANCE;
                double hi = e.centre + e.amplitude + TOLERANCE;
                boolean inRange = mn[i] >= lo && mx[i] <= hi;

                // Monotonic across the whole window with a real span = accumulating — but only
                // decidable if the window actually covered a full cycle of this axis.
                int up = mono[i * 2];
                int down = mono[i * 2 + 1];
                boolean monotonic = (up > 0 && down == 0) || (down > 0 && up == 0);
                boolean windowCoversCycle = e.periodTicks <= 0 || sampleCount >= e.periodTicks;
                boolean drifting = monotonic && (mx[i] - mn[i]) > 1.0D && sampleCount > 20
                        && windowCoversCycle;

                String verdict;
                if (!inRange) {
                    verdict = "FAIL 범위이탈";
                    fails++;
                } else if (drifting) {
                    verdict = "FAIL 단조누적";
                    fails++;
                } else if (monotonic && !windowCoversCycle) {
                    verdict = Double.isInfinite(e.periodTicks)
                            ? "OK (누적판정보류: 비주기 — 바닐라 시선)"
                            : String.format(Locale.ROOT,
                                    "OK (누적판정보류: 창 %d틱 < 주기 %.0f틱)", sampleCount, e.periodTicks);
                } else {
                    verdict = "OK";
                }
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace] %-10s %-5s %+10.3f %+10.3f %-6s %-26s %s",
                        bone, AXIS[i], mn[i], mx[i], e.kind,
                        e.kind.equals("CONST")
                                ? String.format(Locale.ROOT, "%+.3f 고정", e.centre)
                                : String.format(Locale.ROOT, "%+.3f ± %.3f", e.centre, e.amplitude),
                        verdict));
            }
        }

        positions.forEach((bone, v) -> WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] %-10s 위치(px) 마지막 = (%+.4f, %+.4f, %+.4f)", bone, v[0], v[1], v[2])));

        reportSeries();
        reportTurnLean();
        fails += reportLook();
        fails += reportSpring();
        fails += reportFeet();

        if (fails == 0) {
            WardenGirlMod.LOGGER.info("[trace] === 판정: 전 항목 통과 (회전 30 + 시선 + 스프링 좌우 + 발끝 2) ===");
        } else {
            WardenGirlMod.LOGGER.error("[trace] === 판정: 실패 {}개 ===", fails);
        }
    }

    /**
     * Dumps the tracked axes with their first difference.
     *
     * <p>The difference is per <em>sample</em>, not per tick, and samples are per frame — so the
     * absolute size means little, but a single sample whose difference dwarfs its neighbours is a
     * discontinuity wherever it lands. Printed as one row per sample so the seam and the
     * transition can be located by eye as well as by the summary.
     */
    /** Ticks after an edge that still count as "inside the transition". Spec length is 6. */
    private static final int PHASE_WINDOW_TICKS = 8;

    /**
     * Splits the first differences into STOP transition / START transition / steady walking.
     *
     * <h2>Why the split is the whole point</h2>
     *
     * A single "max first difference" over a window that contains starts, stops and steady walking
     * cannot say which of the three is discontinuous — and all three are separate questions:
     * 4.4.1's stop transition, its start transition, and 4.4.2's 26-tick loop seam. The seam in
     * particular has been unmeasurable until now because an 18° stop snap dominated every window it
     * appeared in.
     *
     * <h2>Steady = walking and not near any edge</h2>
     *
     * Classified by distance in <b>ticks</b> from the nearest state change, not by sample index —
     * frames per tick wanders, so an index window would cover a different amount of time on every
     * run.
     */
    private static void reportByPhase() {
        if (seriesCount < 8) {
            return;
        }
        String[] label = {"leg_right.xRot", "arm_right.xRot", "body.yRot"};
        double ticks = Math.max(1, SERIES_TICK[seriesCount - 1] - SERIES_TICK[0]);
        double framesPerTick = seriesCount / ticks;
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- 전이 구간별 1차차분. 프레임/틱 = %.2f (창 %d틱, 표본 %d) ---",
                framesPerTick, (int) ticks, seriesCount));

        for (int i = 0; i < SERIES_BONES.length; i++) {
            double[] worst = new double[3];
            int[] count = new int[3];
            double[] sum = new double[3];
            for (int j = 1; j < seriesCount; j++) {
                int phase = phaseOf(j);
                if (phase < 0) {
                    continue;
                }
                double d = Math.abs(SERIES[i][j] - SERIES[i][j - 1]);
                worst[phase] = Math.max(worst[phase], d);
                sum[phase] += d;
                count[phase]++;
            }
            String[] name = {"정지 전이(walk->idle)", "시작 전이(idle->walk)", "정상 걷기(루프 이음매)"};
            for (int p = 0; p < 3; p++) {
                if (count[p] == 0) {
                    WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                            "[trace]   %-14s %-24s 표본 0 — **측정 불가**", label[i], name[p]));
                    continue;
                }
                // Per-tick as well as per-frame. A first difference is per RENDERED FRAME, so the
                // same motion reports twice the difference at half the framerate — and the
                // framerate on this software renderer wanders by 2x between runs. Two runs are
                // only comparable in 도/틱.
                double meanFrame = sum[p] / count[p];
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace]   %-14s %-24s 표본 %5d  평균 %7.4f  최대 %8.4f  (최대/평균 %6.2f)"
                                + "   [도/틱: 평균 %7.4f  최대 %8.4f]",
                        label[i], name[p], count[p], meanFrame, worst[p],
                        sum[p] > 1e-9 ? worst[p] / meanFrame : 0.0D,
                        meanFrame * framesPerTick, worst[p] * framesPerTick));
            }
        }
    }

    /**
     * Dumps {@code leg_right.xRot} across the first few stop transitions.
     *
     * <p>The phase table gives means and maxima; this gives the actual travel. "How far did the leg
     * move during the stop, and over how many ticks" is the question that separates a fade that is
     * too fast from a fade that never had far to go — and the two paths differ by 5x on the mean
     * without that being decidable from the mean alone.
     */
    private static void reportStopDetail() {
        WardenGirlMod.LOGGER.info("[trace]   판정은 '이동량 / 시작값' 과 '소요 틱' 으로 한다."
                + " 이동량 자체는 정지 순간의 걷기 위상에 따라 3배 넘게 흔들리므로 비교에 못 쓴다");
        int runs = 0;
        for (int k = 1; k < seriesCount && runs < 3; k++) {
            if (SERIES_WALK[k] || !SERIES_WALK[k - 1]) {
                continue;
            }
            runs++;
            int end = k;
            while (end + 1 < seriesCount && SERIES_TICK[end] <= SERIES_TICK[k] + 10) {
                end++;
            }
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (int j = k - 1; j <= end; j++) {
                lo = Math.min(lo, SERIES[0][j]);
                hi = Math.max(hi, SERIES[0][j]);
            }
            // Phase-independent: how far it went as a FRACTION of where it started, and how long
            // that took. The raw distance depends on the walk phase at the moment of the stop and
            // varies threefold between events - averaging four of those cannot separate two code
            // paths, which is exactly the trap the earlier A/B fell into.
            double start = SERIES[0][k - 1];
            int settleTick = SERIES_TICK[end];
            for (int j = k; j <= end; j++) {
                if (Math.abs(SERIES[0][j]) < 1.0E-6D) {
                    settleTick = SERIES_TICK[j];
                    break;
                }
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]   정지 %d: t=%d. leg_right.xRot %+8.4f -> %+8.4f | "
                            + "이동/시작값 %.4f (1.0 이어야 한다) | 0 도달까지 %d틱 (6 이어야 한다) | "
                            + "프레임 %d개",
                    runs, SERIES_TICK[k], start, SERIES[0][end],
                    Math.abs(start) < 1.0E-6D ? 0.0D
                            : Math.abs(SERIES[0][end] - start) / Math.abs(start),
                    settleTick - SERIES_TICK[k - 1], end - k + 2));
            for (int j = k - 1; j <= end; j++) {
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace]      t=%6d  leg_r.x %+9.4f  d%+8.4f",
                        SERIES_TICK[j], SERIES[0][j],
                        j > 0 ? SERIES[0][j] - SERIES[0][j - 1] : 0.0D));
            }
            k = end;
        }
    }

    /**
     * Audits {@link #phaseOf} itself: what each edge actually collected, event by event.
     *
     * <p>The aggregate said the stop transition averaged 0.1424 °/frame; the raw trajectory across
     * the same kind of event averaged about 0.79. A five-fold disagreement between an aggregate and
     * the values it aggregates means the aggregate is not measuring what its label says. Until that
     * is located by value, neither number can be used — and several T5 verdicts rest on this
     * classifier.
     *
     * <p>Prints, per edge: the tick it was detected at, how many samples landed in its window, the
     * tick span they covered, and the total travel. Cross-check against the raw dump below it.
     */
    private static void reportPhaseAudit() {
        WardenGirlMod.LOGGER.info("[trace] --- 분류기 감사: 에지별로 무엇이 담겼나 ---");
        int edges = 0;
        int classifiedStop = 0;
        int classifiedStart = 0;
        int classifiedSteady = 0;
        int classifiedNone = 0;
        for (int j = 1; j < seriesCount; j++) {
            int p = phaseOf(j);
            if (p == 0) {
                classifiedStop++;
            } else if (p == 1) {
                classifiedStart++;
            } else if (p == 2) {
                classifiedSteady++;
            } else {
                classifiedNone++;
            }
        }
        for (int k = 1; k < seriesCount && edges < 8; k++) {
            if (SERIES_WALK[k] == SERIES_WALK[k - 1]) {
                continue;
            }
            edges++;
            int edgeTick = SERIES_TICK[k];
            int count = 0;
            double sum = 0.0D;
            int mine = 0;
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int j = 1; j < seriesCount; j++) {
                int t = SERIES_TICK[j];
                if (t < edgeTick || t > edgeTick + PHASE_WINDOW_TICKS) {
                    continue;
                }
                count++;
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
                sum += Math.abs(SERIES[0][j] - SERIES[0][j - 1]);
                // Did phaseOf actually award this sample to THIS edge, or to an earlier one?
                if (phaseOf(j) == (SERIES_WALK[k] ? 1 : 0)) {
                    mine++;
                }
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]   에지 %d (%s) t=%d: 창 안 표본 %d개, 틱 %d..%d, |차분| 합 %.4f, 평균 %.4f"
                            + "  (그중 이 구간으로 분류된 것 %d개)",
                    edges, SERIES_WALK[k] ? "시작" : "정지", edgeTick, count, lo, hi, sum,
                    count > 0 ? sum / count : 0.0D, mine));
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   에지 %d개. 분류 결과 정지 %d / 시작 %d / 정상걷기 %d / 미분류(정지상태) %d"
                        + "  합 %d, 표본 %d",
                edges, classifiedStop, classifiedStart, classifiedSteady, classifiedNone,
                classifiedStop + classifiedStart + classifiedSteady + classifiedNone,
                seriesCount - 1));
    }

    /** 0 = stop transition, 1 = start transition, 2 = steady walk, -1 = steady idle (ignored). */
    private static int phaseOf(int j) {
        int tick = SERIES_TICK[j];
        for (int k = 1; k < seriesCount; k++) {
            if (SERIES_WALK[k] == SERIES_WALK[k - 1]) {
                continue;
            }
            int edge = SERIES_TICK[k];
            if (tick >= edge && tick <= edge + PHASE_WINDOW_TICKS) {
                return SERIES_WALK[k] ? 1 : 0;
            }
        }
        return SERIES_WALK[j] ? 2 : -1;
    }

    private static void reportSeries() {
        if (seriesCount < 4) {
            return;
        }
        String[] label = {"leg_right.xRot", "arm_right.xRot", "body.yRot"};
        for (int i = 0; i < SERIES_BONES.length; i++) {
            double maxAbs = 0;
            double sum = 0;
            int n = 0;
            for (int j = 1; j < seriesCount; j++) {
                double d = Math.abs(SERIES[i][j] - SERIES[i][j - 1]);
                maxAbs = Math.max(maxAbs, d);
                sum += d;
                n++;
            }
            double mean = n > 0 ? sum / n : 0;
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] --- 시계열 %s: 표본 %d, 1차차분 평균 %.5f 최대 %.5f (최대/평균 %.2f) ---",
                    label[i], seriesCount, mean, maxAbs, mean > 1e-9 ? maxAbs / mean : 0.0D));
        }
        // The one row that settled the previous investigation was the row either side of the
        // jump. Print the neighbourhood of the argmax explicitly instead of hoping the sampled
        // rows happen to land on it.
        for (int i = 0; i < SERIES_BONES.length; i++) {
            int arg = 1;
            double best = -1;
            for (int j = 1; j < seriesCount; j++) {
                double d = Math.abs(SERIES[i][j] - SERIES[i][j - 1]);
                if (d > best) {
                    best = d;
                    arg = j;
                }
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] --- %s 최대 차분 근방 (표본 %d, t=%d, 차분 %+.4f) ---",
                    label[i], arg, SERIES_TICK[arg], SERIES[i][arg] - SERIES[i][arg - 1]));
            for (int j = Math.max(0, arg - 5); j < Math.min(seriesCount, arg + 6); j++) {
                double d = j > 0 ? SERIES[i][j] - SERIES[i][j - 1] : 0.0D;
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace]     %s S %5d t=%6d  %+10.4f  d%+9.4f%s",
                        label[i], j, SERIES_TICK[j], SERIES[i][j], d, j == arg ? "   <== 최대" : ""));
            }
        }
        reportMovement();
        reportSlipConditions();
        reportHurt();
        reportSniff();
        reportFade();
        reportByPhase();
        reportPhaseAudit();
        WardenGirlMod.LOGGER.info("[trace] --- 정지 전이 상세 (leg_right.xRot) ---");
        reportStopDetail();
        int step = Math.max(1, seriesCount / 260);
        WardenGirlMod.LOGGER.info("[trace] 시계열 행 (t=엔티티틱, d=직전 표본 대비 차분):");
        for (int j = 0; j < seriesCount; j += step) {
            double d0 = j > 0 ? SERIES[0][j] - SERIES[0][j - 1] : 0;
            double d1 = j > 0 ? SERIES[1][j] - SERIES[1][j - 1] : 0;
            double d2 = j > 0 ? SERIES[2][j] - SERIES[2][j - 1] : 0;
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace]  S %5d t=%6d  leg_r.x %+9.4f d%+8.4f | arm_r.x %+9.4f d%+8.4f "
                            + "| body.y %+8.4f d%+8.4f",
                    j, SERIES_TICK[j], SERIES[0][j], d0, SERIES[1][j], d1, SERIES[2][j], d2));
        }
    }

    private static void reportTurnLean() {
        if (Double.isInfinite(turnLeanMin)) {
            return;
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- 4.4.3 방향 전환: hip zRot %+.4f ~ %+.4f 도 (사양 ±3 x scale %.2f), "
                        + "몸통 yaw 변화율 %+.3f ~ %+.3f 도/틱 ---",
                turnLeanMin, turnLeanMax, AnimParams.TURN_LEAN_SCALE.get(),
                turnRateMin, turnRateMax));
    }

    /**
     * 4.6 시선 감쇠 — does the filter actually lag, and by how much.
     *
     * <h2>The lag is measured, not asserted</h2>
     *
     * A coefficient being set is not evidence that anything is being smoothed; Part 6.2 wants the
     * observation. So the series is searched for the shift {@code d} that best aligns
     * {@code current[t]} with {@code target[t-d]}, and that shift is reported in ticks. A filter
     * that is doing nothing reports 0. A working first-order lag reports something near its time
     * constant, and the analytic prediction {@code 1/damping − 1} is printed alongside so the two
     * can be compared rather than trusted.
     *
     * <p>The search is over the sample index, and samples are one per frame — so the unit is
     * "samples", converted to ticks with the measured samples-per-tick ratio. Reporting raw frames
     * would make the number change with the framerate while the behaviour did not.
     */
    private static int reportLook() {
        if (lookSamples < 4) {
            WardenGirlMod.LOGGER.warn("[trace] 4.6 시선 표본 없음 ({}개) — 시선 감쇠가 돌지 않았다",
                    lookSamples);
            return 1;
        }
        double damping = LOOK_DAMPING_MAX[0];
        boolean bypass = damping >= 1.0D;
        int tickSpan = lookLastTick - lookFirstTick;
        double samplesPerTick = tickSpan > 0 ? (double) lookSamples / tickSpan : 1.0D;

        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] --- 4.6 시선 감쇠: 실효계수 %.4f ~ %.4f (거리 %.2f ~ %.2f 블록), "
                        + "표본 %d개 / 엔티티 %d틱 = %.2f 프레임/틱 ---",
                LOOK_DAMPING_MIN[0], LOOK_DAMPING_MAX[0],
                Double.isInfinite(lookDistanceMin) ? -1 : lookDistanceMin,
                Double.isInfinite(lookDistanceMax) ? -1 : lookDistanceMax,
                lookSamples, tickSpan, samplesPerTick));

        int fails = 0;
        String[] name = {"yaw ", "pitch"};
        double[][] tgt = {LOOK_TARGET_YAW, LOOK_TARGET_PITCH};
        double[][] cur = {LOOK_CURRENT_YAW, LOOK_CURRENT_PITCH};
        for (int a = 0; a < 2; a++) {
            int bestShift = bestLagShift(tgt[a], cur[a], lookSamples);
            double lagTicks = lagInTicks(bestShift);
            double predicted = bypass ? 0.0D : 1.0D / damping - 1.0D;
            double span = range(tgt[a], lookSamples);
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] 시선 %s  target 진폭 %.3f도  최대 |target-current| %.4f도  "
                            + "실측 지연 %d표본 = %.2f틱  (1차 지연 예측 1/k-1 = %.2f틱)",
                    name[a], span, LOOK_ERR_MAX[a], bestShift, lagTicks, predicted));
            if (bypass && LOOK_ERR_MAX[a] > 1e-9) {
                WardenGirlMod.LOGGER.error(String.format(Locale.ROOT,
                        "[trace] FAIL 시선 %s — 우회(계수 1.0)인데 target 과 current 가 %.6f도 다르다. "
                                + "T3 와 동일해야 한다", name[a], LOOK_ERR_MAX[a]));
                fails++;
            }
            if (!bypass && span > 1.0D && LOOK_ERR_MAX[a] < 1e-6) {
                WardenGirlMod.LOGGER.error(String.format(Locale.ROOT,
                        "[trace] FAIL 시선 %s — target 이 %.3f도 움직였는데 지연이 0이다. "
                                + "감쇠가 걸리지 않았다", name[a], span));
                fails++;
            }
        }

        // target 과 current 를 나란히. 지연은 요약값이 아니라 이 표에서 눈으로도 보여야 한다.
        int rows = Math.min(30, lookSamples);
        int step = Math.max(1, lookSamples / rows);
        WardenGirlMod.LOGGER.info("[trace] 시계열 (표본 {}개 중 {}개 간격으로, t=엔티티 틱):",
                lookSamples, step);
        for (int i = 0; i < lookSamples; i += step) {
            logLookRow(i);
        }
        // 연속 구간도 하나 남긴다. 간격을 띄운 표로는 점화식을 검산할 수 없다 —
        // 두 행 사이에 틱이 몇 번 지났는지가 행마다 다르기 때문이다.
        int burstFrom = findLargestStepIndex();
        WardenGirlMod.LOGGER.info("[trace] 연속 구간 (가장 큰 target 변화 직후 40행):");
        for (int i = burstFrom; i < Math.min(lookSamples, burstFrom + 40); i++) {
            logLookRow(i);
        }

        // 3. 근거리 전환이 계단식이 아닌지 — 거리를 직접 넣어 실효값을 뽑는다.
        StringBuilder sb = new StringBuilder("[trace] 거리별 실효 감쇠: ");
        for (double d : new double[]{0.0D, 1.0D, 2.0D, 2.5D, 3.0D, 3.5D, 4.0D, 8.0D}) {
            sb.append(String.format(Locale.ROOT, "%.1f=%.4f  ", d, LookDamper.dampingFor(d)));
        }
        WardenGirlMod.LOGGER.info(sb.toString());
        double prevK = LookDamper.dampingFor(0.0D);
        double maxJump = 0;
        for (double d = 0.05D; d <= 6.0001D; d += 0.05D) {
            double k = LookDamper.dampingFor(d);
            maxJump = Math.max(maxJump, Math.abs(k - prevK));
            prevK = k;
        }
        // 계단이라면 경계 한 칸에서 두 계수의 차 전체가 한꺼번에 나타난다. 선형 보간이면
        // 한 칸 변화는 (차이 / 구간길이) x 칸 크기 로 나뉜다.
        double gap = Math.abs(AnimParams.LOOK_DAMPING.get() - AnimParams.LOOK_DAMPING_NEAR.get());
        double linearStep = gap * 0.05D / Math.max(1e-9, AnimParams.LOOK_NEAR_DISTANCE.get());
        boolean smooth = maxJump <= linearStep * 1.5D + 1e-9;
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace] 0.05블록 간격 최대 변화 %.6f (선형 예측 %.6f, 계단이면 %.6f) — %s",
                maxJump, linearStep, gap, smooth ? "OK 연속 (계단 없음)" : "FAIL 계단식 전환"));
        if (!smooth) {
            fails++;
        }
        return fails;
    }

    private static void logLookRow(int i) {
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[trace]   #%4d t=%d  yaw target %+8.3f -> current %+8.3f (차 %+7.3f)   "
                        + "pitch target %+8.3f -> current %+8.3f (차 %+7.3f)",
                i, LOOK_TICK[i], LOOK_TARGET_YAW[i], LOOK_CURRENT_YAW[i],
                LOOK_CURRENT_YAW[i] - LOOK_TARGET_YAW[i],
                LOOK_TARGET_PITCH[i], LOOK_CURRENT_PITCH[i],
                LOOK_CURRENT_PITCH[i] - LOOK_TARGET_PITCH[i]));
    }

    /** Index of the sample where the yaw target jumped hardest — the most informative burst. */
    private static int findLargestStepIndex() {
        int best = 0;
        double bestJump = -1;
        for (int i = 1; i < lookSamples; i++) {
            double jump = Math.abs(LOOK_TARGET_YAW[i] - LOOK_TARGET_YAW[i - 1]);
            if (jump > bestJump) {
                bestJump = jump;
                best = i;
            }
        }
        return Math.max(0, best - 2);
    }

    /**
     * Converts a shift measured in samples into ticks using the tick stamps themselves.
     *
     * <p>Not {@code shift / averageFramesPerTick}: the average is taken over the whole window
     * including any stretch where the mob was off screen and no frame sampled it, so it can be
     * several times off from the rate during the motion being measured.
     */
    private static double lagInTicks(int shiftSamples) {
        if (shiftSamples <= 0 || lookSamples <= shiftSamples) {
            return 0.0D;
        }
        long sum = 0;
        int n = 0;
        for (int i = shiftSamples; i < lookSamples; i++) {
            sum += LOOK_TICK[i] - LOOK_TICK[i - shiftSamples];
            n++;
        }
        return n == 0 ? 0.0D : (double) sum / n;
    }

    /**
     * The shift {@code d} minimising sum |current[t] − target[t−d]| over the series.
     *
     * <p>Absolute error rather than correlation: correlation is scale-free and would report a
     * confident alignment even for a filter whose output amplitude had collapsed, which is the very
     * failure this is meant to catch.
     */
    private static int bestLagShift(double[] target, double[] current, int n) {
        int maxShift = Math.min(120, n / 3);
        int best = 0;
        double bestErr = Double.POSITIVE_INFINITY;
        for (int d = 0; d <= maxShift; d++) {
            double err = 0;
            for (int t = maxShift; t < n; t++) {
                err += Math.abs(current[t] - target[t - d]);
            }
            if (err < bestErr - 1e-12) {
                bestErr = err;
                best = d;
            }
        }
        return best;
    }

    private static double range(double[] v, int n) {
        double mn = Double.POSITIVE_INFINITY;
        double mx = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            mn = Math.min(mn, v[i]);
            mx = Math.max(mx, v[i]);
        }
        return mx - mn;
    }

    /**
     * 4.5 spring convergence.
     *
     * <h2>Why this is decided analytically, not from the samples</h2>
     *
     * The first version of this check compared the spring's speed in the last quarter of the window
     * against the first quarter and failed above a 4× ratio. <b>It produced a false FAIL in three
     * runs out of four.</b> The reason is structural: the spring is <em>driven</em>, and its input
     * is the vanilla look AI, which turns the head on a random timer. A window whose first quarter
     * happens to be quiet and whose last quarter contains a big turn gives a ratio of ∞ — the
     * spring did exactly what it should. "Velocity grew" measures the input, not the spring.
     *
     * <p>But stability of this recurrence does not depend on the input at all. It is a linear
     * system, so the question is entirely a property of {@code STIFFNESS} and {@code DAMPING}.
     * Writing {@code e = angle - target}:
     *
     * <pre>
     *   v' = (1-c)·v - k·e
     *   e' = (1-c)·v + (1-k)·e
     * </pre>
     *
     * whose characteristic polynomial is {@code λ² - (2-c-k)λ + (1-c) = 0}. The Jury conditions for
     * both roots inside the unit circle reduce to <b>{@code k > 0}, {@code k < 4 - 2c},
     * {@code 0 < c < 2}</b>. That is checked directly, and it cannot be fooled by what the head
     * happened to do during the window.
     *
     * <p>The sampled numbers are still printed — overshoot against the head's own range is the
     * thing a human tunes by eye ("지나쳤다 되돌아오는가"), and the velocity extremes say how hard
     * the spring was actually driven. They are <em>information</em>, not verdicts.
     */
    private static int reportSpring() {
        if (!SPRING_SEEN[0] && !SPRING_SEEN[1]) {
            WardenGirlMod.LOGGER.warn("[trace] 4.5 스프링 표본 없음 — 감각 촉수 스프링이 돌지 않았다");
            return 1;
        }
        int fails = 0;
        double c = AnimParams.HEADGEAR_DAMPING.get();
        double amp = AnimParams.HEADGEAR_AMPLITUDE.get();
        double max = Math.abs(AnimParams.HEADGEAR_MAX_ANGLE.get());
        double[] headMin = MIN.get(Bones.HEAD);
        double[] headMax = MAX.get(Bones.HEAD);
        String[] axis = {"x", "y", "z"};
        String[] headAxis = {"head xRot", "head yRot", "head zRot"};

        for (int side = 0; side < SIDES; side++) {
            if (!SPRING_SEEN[side]) {
                WardenGirlMod.LOGGER.warn("[trace] 4.5 스프링 {} 표본 없음", SIDE_NAME[side]);
                fails++;
                continue;
            }
            double k = SPRING_STIFFNESS[side];
            boolean stable = k > 0 && k < 4 - 2 * c && c > 0 && c < 2;
            if (!stable) {
                fails++;
            }
            double det = 1 - c;
            double tr = 2 - c - k;
            double disc = tr * tr - 4 * det;
            double magnitude = disc < 0 ? Math.sqrt(Math.abs(det))
                    : Math.max(Math.abs((tr + Math.sqrt(disc)) / 2),
                               Math.abs((tr - Math.sqrt(disc)) / 2));
            String mode;
            if (disc < 0) {
                double period = 2 * Math.PI / Math.atan2(Math.sqrt(-disc) / 2, tr / 2);
                double settle = magnitude >= 1 ? Double.POSITIVE_INFINITY
                        : Math.log(0.01) / Math.log(magnitude);
                mode = String.format(Locale.ROOT, "감쇠진동 주기 %.1f틱, 1%%까지 %.0f틱", period, settle);
            } else {
                mode = "과감쇠 (진동 없음)";
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] --- 4.5 스프링 %s 안정성(해석): k=%.4f c=%.3f  |λ|=%.4f  %s  %s ---",
                    SIDE_NAME[side], k, c, magnitude, mode,
                    stable ? "OK 안정 (Jury 조건 충족)" : "FAIL 발산 (Jury 조건 위반)"));

            int samples = Math.max(1, SPRING_SAMPLES[side]);
            for (int i = 0; i < 3; i++) {
                double springSpan = SPRING_ANGLE_MAX[side][i] - SPRING_ANGLE_MIN[side][i];
                double headSpan = headMin == null ? 0 : headMax[i] - headMin[i];
                String overshoot = headSpan <= 1e-6
                        ? "입력 거의 없음"
                        : String.format(Locale.ROOT, "입력폭 %.3f 대비 초과 %+.1f%%",
                                headSpan, 100.0D * (springSpan / headSpan - 1.0D));
                double hitPct = 100.0D * CLAMP_HITS[side][i] / samples;
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace] 스프링 %s %s축  각도 %+9.3f ~ %+9.3f (폭 %.3f)  속도 %+7.3f ~ %+7.3f  "
                                + "[%s] %s  클램프 접촉 %d/%d (%.1f%%)",
                        SIDE_NAME[side], axis[i],
                        SPRING_ANGLE_MIN[side][i], SPRING_ANGLE_MAX[side][i], springSpan,
                        SPRING_VEL_MIN[side][i], SPRING_VEL_MAX[side][i],
                        headAxis[i], overshoot, CLAMP_HITS[side][i], samples, hitPct));
                int n = SPRING_LAG_N[side][i];
                double rms = n > 0 ? Math.sqrt(SPRING_LAG_SUMSQ[side][i] / n) : 0.0D;
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[trace] 촉수휘청 %s %s축  지연오차 |스프링-head|  최대 %.4f도  RMS %.4f도  "
                                + "(화면 각도 = 이 값 x amplitude %.2f, ±%.0f 클램프)",
                        SIDE_NAME[side], axis[i], SPRING_LAG_MAX[side][i], rms, amp, max));
            }
        }
        // The whole point of splitting the bone: identical springs would produce identical output
        // and the two tendrils would move as one rigid piece.
        if (SPRING_SEEN[0] && SPRING_SEEN[1]) {
            double diff = Math.max(SPRING_SPLIT_MAX[0],
                    Math.max(SPRING_SPLIT_MAX[1], SPRING_SPLIT_MAX[2]));
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] 좌우 비대칭: k 오른쪽 %.4f / 왼쪽 %.4f | 동시각 차 최대 x %.3f y %.3f z %.3f 도  %s",
                    SPRING_STIFFNESS[0], SPRING_STIFFNESS[1],
                    SPRING_SPLIT_MAX[0], SPRING_SPLIT_MAX[1], SPRING_SPLIT_MAX[2],
                    diff > 1e-6 ? "OK 좌우가 다르게 움직인다" : "주의: 좌우가 완전히 동일하다"));
        }
        return fails;
    }

    private static int reportFeet() {
        int fails = 0;
        WardenGirlMod.LOGGER.info(
                "[trace] --- 발끝 변위 (발 밑면 중심, 모델 px. 정지 자세 허용치 {}px) ---",
                String.format(Locale.ROOT, "%.2f", FOOT_IDLE_LIMIT_PX));
        for (String leg : new String[]{Bones.LEG_RIGHT, Bones.LEG_LEFT}) {
            double[] a = FOOT.get(leg);
            if (a == null) {
                WardenGirlMod.LOGGER.warn("[trace] {} — 발끝 표본 없음", leg);
                fails++;
                continue;
            }
            boolean ok = a[3] <= FOOT_IDLE_LIMIT_PX;
            if (!ok) {
                fails++;
            }
            String worst = a[4] >= 0 ? CORNER_LABELS[(int) a[4]] : "--";
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] %-10s 최대|dx|=%.4f  최대|dy|=%.4f  최대|dz|=%.4f  "
                            + "최대 수평변위=%.4f (%s 모서리)  %s",
                    leg, a[0], a[1], a[2], a[3], worst, ok ? "OK" : "FAIL 발이 미끄러진다"));
        }
        return fails;
    }
}
