package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.Bones;
import com.wardengirl.anim.ClipSampler;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies the C3 direct-evaluation path against an independently written expectation.
 *
 * <h2>Independent, on purpose</h2>
 *
 * The expected constants below are transcribed from {@code actiontest} in the json, and the
 * expected fade envelope is recomputed from the parameters — <b>neither is read back from
 * {@link ClipSampler} or {@link ActionMotion}.</b> A check that asks the thing it is checking what
 * the answer should be proves only that the thing is consistent with itself. The one shared call is
 * {@link ActionMotion#envelope}, which is a pure function of (age, length, two parameters) and is
 * deliberately static so it can be evaluated without a playback object.
 *
 * <h2>Measurement validity is recorded, not assumed</h2>
 *
 * Part 6.3: <b>a window that did not contain the situation it was meant to measure must not print a
 * pass.</b> This one records how many frames the clip actually played, whether a rising edge and a
 * falling edge were both seen, and how many frames were sampled after playback ended. If the clip
 * never played, every residual is trivially zero and the table would read as a clean pass — which is
 * exactly the failure mode that produced a false "통과" in the C1 walking measurement.
 */
public final class ActionCheck {

    private ActionCheck() {
    }

    /**
     * {@code actiontest}'s constants, transcribed from the json by hand.
     *
     * <p>Rotations in degrees, in the Part 4.0 bone convention: a json {@code +18} on
     * {@code arm_right} x must arrive at the bone as {@code +18}. That is the same rule the C2 path
     * obeys after {@code JsonAxisConvention}, and checking it here is what establishes that direct
     * evaluation did not introduce a second sign system.
     */
    private static final Map<String, double[]> EXPECTED_ROT = new LinkedHashMap<>();

    static {
        EXPECTED_ROT.put(Bones.HIP, new double[]{42, -44, 46});
        EXPECTED_ROT.put(Bones.BODY, new double[]{6, -8, 10});
        EXPECTED_ROT.put(Bones.HEAD, new double[]{12, -14, 16});
        EXPECTED_ROT.put(Bones.ARM_RIGHT, new double[]{18, -20, 22});
        EXPECTED_ROT.put(Bones.ARM_LEFT, new double[]{24, -26, 28});
        EXPECTED_ROT.put(Bones.LEG_RIGHT, new double[]{30, -32, 34});
        EXPECTED_ROT.put(Bones.LEG_LEFT, new double[]{36, -38, 40});
    }

    /** {@code root} position, in GeckoLib's raw units — the same units {@link ClipSampler} returns. */
    private static final double[] EXPECTED_ROOT_POS = {2, 3, 0};

    /** json {@code animation_length: 1.0} second; GeckoLib multiplies by 20. */
    private static final double CLIP_LENGTH_TICKS = 20.0D;

    private static final String[] AXIS = {"x", "y", "z"};
    /**
     * Float, not double: the pose goes onto the bone as a {@code float} radian and comes back
     * through {@code toDegrees}. Same reasoning as {@code VitalCheck}'s epsilon.
     */
    private static final double EPSILON = 1.0E-4D;

    private static int remainingTicks = 0;
    private static int totalTicks = 0;
    private static int startTick = Integer.MIN_VALUE;
    private static int lastTick = Integer.MIN_VALUE;
    private static int sampleCount = 0;

    // ---- validity ---------------------------------------------------------------------------

    private static int playingFrames = 0;
    private static int idleFrames = 0;
    private static int fadeInFrames = 0;
    private static int fullFrames = 0;
    private static int fadeOutFrames = 0;
    private static double minAgeSeen = Double.POSITIVE_INFINITY;
    private static double maxAgeSeen = Double.NEGATIVE_INFINITY;
    /**
     * First and last playhead position at which the envelope reached full weight.
     *
     * <p>This is the direct answer to "페이드가 지정한 틱수에 걸쳐 일어나는가": with fade_in 3 and
     * fade_out 5 on a 20-tick clip, full weight must begin at age 3 and end at age 15. Counting
     * fade frames only says a ramp existed; these two say it was the right length.
     */
    private static double fullFrom = Double.POSITIVE_INFINITY;
    private static double fullTo = Double.NEGATIVE_INFINITY;

    // ---- residuals --------------------------------------------------------------------------

    /** bone -> {x,y,z} worst |sampled − expected| over the window. */
    private static final Map<String, double[]> ROT_RESIDUAL = new LinkedHashMap<>();
    private static final Map<String, double[]> ROT_WORST_SAMPLED = new LinkedHashMap<>();
    private static final Map<String, double[]> ROT_WORST_EXPECTED = new LinkedHashMap<>();
    private static final double[] POS_RESIDUAL = new double[3];
    private static final double[] POS_WORST_SAMPLED = new double[3];
    private static final double[] POS_WORST_EXPECTED = new double[3];
    /** Same comparison, but against what actually landed on the bone. */
    private static final Map<String, double[]> ROT_BONE_RESIDUAL = new LinkedHashMap<>();
    private static final double[] POS_BONE_RESIDUAL = new double[3];

    /** Worst |envelope − independently recomputed envelope|. */
    private static double weightResidual = 0.0D;
    /** Largest |contribution| observed on any channel while nothing was playing. */
    private static double leakAfterEnd = 0.0D;

    public static void start(int ticks) {
        remainingTicks = ticks;
        totalTicks = ticks;
        startTick = Integer.MIN_VALUE;
        lastTick = Integer.MIN_VALUE;
        sampleCount = 0;
        playingFrames = 0;
        idleFrames = 0;
        fadeInFrames = 0;
        fullFrames = 0;
        fadeOutFrames = 0;
        minAgeSeen = Double.POSITIVE_INFINITY;
        maxAgeSeen = Double.NEGATIVE_INFINITY;
        fullFrom = Double.POSITIVE_INFINITY;
        fullTo = Double.NEGATIVE_INFINITY;
        weightResidual = 0.0D;
        leakAfterEnd = 0.0D;
        ROT_RESIDUAL.clear();
        ROT_WORST_SAMPLED.clear();
        ROT_WORST_EXPECTED.clear();
        ROT_BONE_RESIDUAL.clear();
        java.util.Arrays.fill(POS_BONE_RESIDUAL, 0.0D);
        java.util.Arrays.fill(POS_RESIDUAL, 0.0D);
        java.util.Arrays.fill(POS_WORST_SAMPLED, 0.0D);
        java.util.Arrays.fill(POS_WORST_EXPECTED, 0.0D);
        WardenGirlMod.LOGGER.info("[actioncheck] 시작 — {}틱. 그 사이에 action play 를 걸어야 한다", ticks);
    }

    public static boolean isRunning() {
        return remainingTicks > 0;
    }

    /**
     * One frame.
     *
     * <p>Two independent things are checked per frame and they are not the same claim:
     * <b>the sampler's output</b> ({@code pose}) says the clip was read correctly, and
     * <b>the bone delta</b> says that output actually reached the bone. A pose that is right and a
     * bone that never moves would pass the first and fail the second — which is the whole question
     * "임시 클립 값이 본에 정확히 도달하는가".
     *
     * @param pose      what {@link ClipSampler} produced, or null when nothing is playing
     * @param deltaRot  bone -> {x,y,z} degrees, (after C3) − (before C3)
     * @param deltaPos  bone -> {x,y,z} model pixels, (after C3) − (before C3)
     * @param age       playhead in ticks, or negative when nothing is playing
     * @param weight    the fade envelope {@link ActionMotion} applied
     */
    public static void sample(ClipSampler.Pose pose, Map<String, double[]> deltaRot,
                              Map<String, double[]> deltaPos, double age, double weight,
                              String clip, int tickCount) {
        if (remainingTicks <= 0) {
            return;
        }
        if (startTick == Integer.MIN_VALUE) {
            startTick = tickCount;
        }
        lastTick = tickCount;
        sampleCount++;
        remainingTicks = totalTicks - (tickCount - startTick);
        if (remainingTicks < 0) {
            remainingTicks = 0;
        }

        if (pose == null || age < 0.0D) {
            idleFrames++;
            // Nothing playing must mean a contribution of exactly zero. weight is what the model
            // multiplies by, so a non-zero weight here would leak the whole pose onto the bone.
            leakAfterEnd = Math.max(leakAfterEnd, Math.abs(weight));
            for (double[] v : deltaRot.values()) {
                for (double d : v) {
                    leakAfterEnd = Math.max(leakAfterEnd, Math.abs(d));
                }
            }
            for (double[] v : deltaPos.values()) {
                for (double d : v) {
                    leakAfterEnd = Math.max(leakAfterEnd, Math.abs(d));
                }
            }
            if (remainingTicks == 0) {
                report();
            }
            return;
        }

        playingFrames++;
        minAgeSeen = Math.min(minAgeSeen, age);
        maxAgeSeen = Math.max(maxAgeSeen, age);

        double expectedWeight = ActionMotion.envelope(age, CLIP_LENGTH_TICKS);
        weightResidual = Math.max(weightResidual, Math.abs(weight - expectedWeight));
        if (weight >= 1.0D - 1.0E-6D) {
            fullFrames++;
            fullFrom = Math.min(fullFrom, age);
            fullTo = Math.max(fullTo, age);
        } else if (age < CLIP_LENGTH_TICKS / 2.0D) {
            fadeInFrames++;
        } else {
            fadeOutFrames++;
        }

        for (Map.Entry<String, double[]> e : EXPECTED_ROT.entrySet()) {
            double[] sampled = pose.rotationsDeg().get(e.getKey());
            double[] bone = deltaRot.get(e.getKey());
            double[] residual = ROT_RESIDUAL.computeIfAbsent(e.getKey(), k -> new double[3]);
            double[] worstS = ROT_WORST_SAMPLED.computeIfAbsent(e.getKey(), k -> new double[3]);
            double[] worstE = ROT_WORST_EXPECTED.computeIfAbsent(e.getKey(), k -> new double[3]);
            double[] boneRes = ROT_BONE_RESIDUAL.computeIfAbsent(e.getKey(), k -> new double[3]);
            for (int i = 0; i < 3; i++) {
                // The sampler returns the clip pose UNWEIGHTED - the envelope is applied by the
                // model when it writes to the bone. So this column is compared against the bare
                // json constant, and the bone column below against constant x envelope. Two
                // different claims, two different expectations.
                double got = sampled == null ? 0.0D : sampled[i];
                double want = e.getValue()[i];
                double diff = Math.abs(got - want);
                if (diff >= residual[i]) {   // >= so an all-zero-residual channel still shows a
                    residual[i] = diff;      // real sample rather than the array's init value
                    worstS[i] = got;
                    worstE[i] = want;
                }
                double gotBone = bone == null ? 0.0D : bone[i];
                boneRes[i] = Math.max(boneRes[i],
                        Math.abs(gotBone - e.getValue()[i] * expectedWeight));
            }
        }

        double[] sampledPos = pose.positionsRaw().get(Bones.ROOT);
        double[] bonePos = deltaPos.get(Bones.ROOT);
        for (int i = 0; i < 3; i++) {
            double got = sampledPos == null ? 0.0D : sampledPos[i];
            double want = EXPECTED_ROOT_POS[i];
            double diff = Math.abs(got - want);
            if (diff >= POS_RESIDUAL[i]) {
                POS_RESIDUAL[i] = diff;
                POS_WORST_SAMPLED[i] = got;
                POS_WORST_EXPECTED[i] = want;
            }
            // The bone readback goes through AxisConvention.readPositionPx, which negates x. So
            // the expected BONE delta on x is the negative of the json number - and printing that
            // is the point: it is the measurement of the position-x disagreement, not a fudge.
            double wantBone = (i == 0 ? -1.0D : 1.0D) * EXPECTED_ROOT_POS[i] * expectedWeight;
            double gotBone = bonePos == null ? 0.0D : bonePos[i];
            POS_BONE_RESIDUAL[i] = Math.max(POS_BONE_RESIDUAL[i], Math.abs(gotBone - wantBone));
        }

        if (remainingTicks == 0) {
            report();
        }
    }

    private static void report() {
        WardenGirlMod.LOGGER.info("[actioncheck] ==== C3 직접 평가 실측 ====");
        WardenGirlMod.LOGGER.info("[actioncheck] 틱 {}..{} ({}틱), 프레임 {}개",
                startTick, lastTick, lastTick - startTick + 1, sampleCount);
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck] fade_in=%.1f틱  fade_out=%.1f틱  클립 길이=%.1f틱",
                AnimParams.ATTACK_FADE_IN.get(), AnimParams.ATTACK_FADE_OUT.get(),
                CLIP_LENGTH_TICKS));

        // ---- 측정 유효 조건. 판정보다 먼저 찍는다 (Part 6.3) ----
        boolean played = playingFrames > 0;
        boolean sawRise = fadeInFrames > 0;
        boolean sawFall = fadeOutFrames > 0;
        boolean sawAfter = idleFrames > 0;
        WardenGirlMod.LOGGER.info("[actioncheck] ---- 측정 유효 조건 ----");
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck]   임시 클립 재생 프레임 : %d / %d   %s",
                playingFrames, sampleCount, played ? "OK" : "**측정 불가 — 클립이 재생되지 않았다**"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck]   재생 위치 범위        : %.3f .. %.3f 틱",
                played ? minAgeSeen : 0.0D, played ? maxAgeSeen : 0.0D));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck]   페이드 인 프레임      : %d   %s",
                fadeInFrames, sawRise ? "OK" : "**측정 불가 — 상승 구간을 못 봤다**"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck]   전량(가중치 1) 프레임 : %d", fullFrames));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck]   페이드 아웃 프레임    : %d   %s",
                fadeOutFrames, sawFall ? "OK" : "**측정 불가 — 하강 구간을 못 봤다**"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck]   재생 종료 후 프레임   : %d   %s",
                idleFrames, sawAfter ? "OK" : "**측정 불가 — 종료 후를 못 봤다**"));

        if (!played) {
            WardenGirlMod.LOGGER.info("[actioncheck] **판정 없음.** 잔차가 전부 0 인 것은 아무 일도"
                    + " 일어나지 않았기 때문이다. 통과가 아니다.");
            WardenGirlMod.LOGGER.info("[actioncheck] ==== 끝 ====");
            return;
        }

        WardenGirlMod.LOGGER.info("[actioncheck] ---- 값 도달 ----");
        WardenGirlMod.LOGGER.info("[actioncheck]   샘플러 잔차 = |ClipSampler 출력 − json 상수|"
                + " (페이드 이전, 가중치 없음)");
        WardenGirlMod.LOGGER.info("[actioncheck]   본 잔차     = |본 델타 − json 상수 × 독립 재계산 페이드|");
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck] %-12s %-4s %10s %10s %12s %12s  %s",
                "본", "축", "샘플러값", "json", "샘플러 잔차", "본 잔차", "판정"));
        int fails = 0;
        for (Map.Entry<String, double[]> e : EXPECTED_ROT.entrySet()) {
            double[] residual = ROT_RESIDUAL.get(e.getKey());
            double[] worstS = ROT_WORST_SAMPLED.get(e.getKey());
            double[] worstE = ROT_WORST_EXPECTED.get(e.getKey());
            double[] boneRes = ROT_BONE_RESIDUAL.get(e.getKey());
            for (int i = 0; i < 3; i++) {
                boolean ok = residual[i] <= EPSILON && boneRes[i] <= EPSILON;
                if (!ok) {
                    fails++;
                }
                WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                        "[actioncheck] %-12s %-4s %10.4f %10.4f %12.6f %12.6f  %s  (json %+.0f)",
                        e.getKey(), AXIS[i], worstS[i], worstE[i], residual[i], boneRes[i],
                        ok ? "일치" : "FAIL", e.getValue()[i]));
            }
        }
        for (int i = 0; i < 3; i++) {
            // x is reported, never judged: json position x and AxisConvention's model-pixel x
            // disagree in sign and that is an open item, not a settled convention. Part 11.
            boolean judged = i != 0;
            boolean ok = POS_RESIDUAL[i] <= EPSILON;
            ok = ok && POS_BONE_RESIDUAL[i] <= EPSILON;
            if (judged && !ok) {
                fails++;
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[actioncheck] %-12s %-4s %10.4f %10.4f %12.6f %12.6f  %s  (json %+.0f)",
                    "root.pos", AXIS[i], POS_WORST_SAMPLED[i], POS_WORST_EXPECTED[i],
                    POS_RESIDUAL[i], POS_BONE_RESIDUAL[i],
                    judged ? (ok ? "일치" : "FAIL") : "보고 전용 (규약 미확정. 본 기댓값은 부호 반전)",
                    EXPECTED_ROOT_POS[i]));
        }

        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck] 페이드 잔차 (적용값 vs 독립 재계산) 최대 : %.8f   %s",
                weightResidual, weightResidual <= 1.0E-9D ? "일치" : "FAIL"));
        if (fullFrames > 0) {
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[actioncheck] 전량 구간 실측 : age %.3f .. %.3f 틱   (사양 %.1f .. %.1f)",
                    fullFrom, fullTo, AnimParams.ATTACK_FADE_IN.get(),
                    CLIP_LENGTH_TICKS - AnimParams.ATTACK_FADE_OUT.get()));
        }
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[actioncheck] 종료 후 잔류 (가중치·본 델타 통합) 최대 : %.8f   %s",
                leakAfterEnd, leakAfterEnd == 0.0D ? "0 — 기여도 누적도 없다" : "FAIL 잔류"));
        WardenGirlMod.LOGGER.info("[actioncheck] 회전 21채널 + 위치 y/z 중 불일치: {}개 (허용 {})",
                fails, String.format(Locale.ROOT, "%.0e", EPSILON));
        WardenGirlMod.LOGGER.info("[actioncheck] ==== 끝 ====");
    }
}
