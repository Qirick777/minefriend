package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
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
