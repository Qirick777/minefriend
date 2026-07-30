package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.Bones;

import java.util.Locale;
import java.util.Map;

/**
 * Verifies that C2 and C3 actually <b>compose</b> on the axes they share.
 *
 * <h2>What is still open after round 2</h2>
 *
 * Round 2 established "C3 reaches the bone" and "C3 works while walking". Neither of those says
 * <b>C2 survives on a shared axis</b> — the whole failure that started this work was a layer being
 * silently overwritten, and the axes where that would happen are exactly the four here:
 * {@code arm_right.xRot}, {@code arm_left.xRot}, {@code body.yRot}, {@code head.yRot}.
 *
 * <h2>Per-frame reconstruction, not range comparison</h2>
 *
 * Comparing "walk-only range" against "walk+attack range" is weak: the walk cycle and the action are
 * not phase-locked, so the two windows sample different parts of the walk and the ranges can differ
 * for reasons that have nothing to do with composition. Instead every frame is reconstructed from
 * its parts and the residual is checked:
 *
 * <pre>
 *   arm_right.xRot  =  walkOnly × eff  +  offset_arm_right_x  +  c3
 *   arm_left.xRot   =  walkOnly × eff  +  offset_arm_left_x   +  c3
 *   body.yRot       =  walkOnly × eff  +  C1.swayBodyY        +  c3
 *   head.yRot       =  walkOnly × eff  +  look yaw (0)        +  c3
 * </pre>
 *
 * A residual of zero says every term is present <em>and</em> correctly weighted — "C2 survived" and
 * "the blend weight was applied as specified" in one measurement. If C2 were being overwritten the
 * {@code walkOnly × eff} term would be missing and the residual would be the walk's whole swing.
 *
 * <p><b>{@code look_gain} must be 0 for the window.</b> The look adds an unpredictable amount to
 * {@code head.yRot} and would make that one channel's residual meaningless — the same precondition
 * {@code signtest} has, for the same reason.
 *
 * <h2>Ranges are still reported</h2>
 *
 * Conditioned on state, which is what makes them readable: the walk's swing during overlap frames
 * is printed separately from the swing while nothing is attacking. That is the number a human can
 * check against "0.0이면 걷기 팔 스윙이 완전히 사라져야 한다".
 */
public final class BlendCheck {

    private BlendCheck() {
    }

    private static final String[] CHANNEL = {
            "arm_right.xRot", "arm_left.xRot", "body.yRot", "head.yRot"};
    private static final int N = 4;
    private static final double EPSILON = 1.0E-3D;

    private static int remainingTicks = 0;
    private static int totalTicks = 0;
    private static int startTick = Integer.MIN_VALUE;
    private static int lastTick = Integer.MIN_VALUE;
    private static int sampleCount = 0;

    // ---- validity ---------------------------------------------------------------------------

    private static int walkFrames = 0;
    private static int actionFrames = 0;
    /** The one that matters: frames where BOTH were contributing. */
    private static int overlapFrames = 0;

    // ---- reconstruction ---------------------------------------------------------------------

    private static final double[] RESIDUAL = new double[N];
    private static final double[] WORST_BONE = new double[N];
    private static final double[] WORST_PARTS = new double[N];
    private static final double[] WORST_WALK = new double[N];
    private static final double[] WORST_C3 = new double[N];

    // ---- ranges, conditioned on state -------------------------------------------------------

    /** [channel] walk-only contribution seen while walking and NOT attacking. */
    private static final double[] WALK_ALONE_MIN = new double[N];
    private static final double[] WALK_ALONE_MAX = new double[N];
    /**
     * [channel] the walk's SURVIVING share ({@code walkOnly × eff}) during overlap frames at
     * <b>full</b> action weight.
     *
     * <p>Conditioned on full weight for the same reason the leg range is: during the fade,
     * {@code eff} sweeps from 1 down to the configured blend, so a range taken over all overlap
     * frames is dominated by the ramp and never shows the configured ratio. At full weight
     * {@code eff} is exactly the parameter, so this width divided by the no-attack width is the
     * ratio the human asked to see — 1.0 / 0.5 / 0.0.
     */
    private static final double[] WALK_KEPT_MIN = new double[N];
    private static final double[] WALK_KEPT_MAX = new double[N];
    private static int keptFullFrames = 0;
    /** [channel] final bone value during overlap frames. */
    private static final double[] BONE_OVERLAP_MIN = new double[N];
    private static final double[] BONE_OVERLAP_MAX = new double[N];
    private static final double[] EFF_MIN = new double[N];
    private static final double[] EFF_MAX = new double[N];

    /**
     * The legs must keep swinging even at blend 0 — a mob whose legs stop mid-stride to swing at
     * something is broken.
     *
     * <p><b>Conditioned on full action weight, not merely on overlap.</b> {@code actiontest} adds a
     * constant +30 to {@code leg_right.xRot}, and during the fade that constant ramps 0 → 30, which
     * widens the range by 30 all on its own. A range taken over all overlap frames would therefore
     * look "wide" even if the walk's leg swing had been killed outright. At full weight the C3 term
     * is constant, so the whole remaining spread is the walk's.
     */
    private static double legMinFull = Double.POSITIVE_INFINITY;
    private static double legMaxFull = Double.NEGATIVE_INFINITY;
    private static int legFullFrames = 0;

    public static void start(int ticks) {
        EntityLock.reset();
        remainingTicks = ticks;
        totalTicks = ticks;
        startTick = Integer.MIN_VALUE;
        lastTick = Integer.MIN_VALUE;
        sampleCount = 0;
        walkFrames = 0;
        actionFrames = 0;
        overlapFrames = 0;
        legMinFull = Double.POSITIVE_INFINITY;
        legMaxFull = Double.NEGATIVE_INFINITY;
        legFullFrames = 0;
        for (int i = 0; i < N; i++) {
            RESIDUAL[i] = -1.0D;
            WORST_BONE[i] = 0.0D;
            WORST_PARTS[i] = 0.0D;
            WORST_WALK[i] = 0.0D;
            WORST_C3[i] = 0.0D;
            WALK_ALONE_MIN[i] = Double.POSITIVE_INFINITY;
            WALK_ALONE_MAX[i] = Double.NEGATIVE_INFINITY;
            WALK_KEPT_MIN[i] = Double.POSITIVE_INFINITY;
            WALK_KEPT_MAX[i] = Double.NEGATIVE_INFINITY;
            BONE_OVERLAP_MIN[i] = Double.POSITIVE_INFINITY;
            BONE_OVERLAP_MAX[i] = Double.NEGATIVE_INFINITY;
            EFF_MIN[i] = Double.POSITIVE_INFINITY;
            EFF_MAX[i] = Double.NEGATIVE_INFINITY;
        }
        WardenGirlMod.LOGGER.info("[blendcheck] 시작 — {}틱. 걷기와 actiontest 가 겹쳐야 한다"
                + " (배회 + action play, look_gain 0)", ticks);
    }

    public static boolean isRunning() {
        return remainingTicks > 0;
    }

    /**
     * One frame, taken at the very end of {@code setCustomAnimations} so the bone values are final.
     *
     * @param walkOnly C2's contribution on the four shared axes, read before any Java addition
     * @param vital    C1's contribution, or null when C1 is on the json path
     * @param c3       C3's post-fade contribution on the same four axes
     * @param eff      the effective walk weight actually applied, per channel
     */
    public static void sample(double[] walkOnly, double[] vitalBodyY, double[] c3, double[] eff,
                              double actionWeight, Map<String, double[]> finalRotations,
                              boolean walking, int tickCount) {
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

        boolean attacking = actionWeight > 0.0D;
        if (walking) {
            walkFrames++;
        }
        if (attacking) {
            actionFrames++;
        }
        boolean overlap = walking && attacking;
        if (overlap) {
            overlapFrames++;
            double[] legR = finalRotations.get(Bones.LEG_RIGHT);
            if (legR != null && actionWeight >= 1.0D - 1.0E-6D) {
                legFullFrames++;
                legMinFull = Math.min(legMinFull, legR[0]);
                legMaxFull = Math.max(legMaxFull, legR[0]);
            }
        }

        double[] armR = finalRotations.get(Bones.ARM_RIGHT);
        double[] armL = finalRotations.get(Bones.ARM_LEFT);
        double[] body = finalRotations.get(Bones.BODY);
        double[] head = finalRotations.get(Bones.HEAD);
        if (armR == null || armL == null || body == null || head == null) {
            return;
        }
        double[] bone = {armR[0], armL[0], body[1], head[1]};
        // Everything on these four axes that is neither C2 nor C3.
        double[] other = {
                AnimParams.OFFSET_ARM_R_X.get(),
                AnimParams.OFFSET_ARM_L_X.get(),
                vitalBodyY == null ? 0.0D : vitalBodyY[0],
                0.0D};

        for (int i = 0; i < N; i++) {
            double kept = walkOnly[i] * eff[i];
            double parts = kept + other[i] + c3[i];
            double diff = Math.abs(bone[i] - parts);
            if (diff > RESIDUAL[i]) {
                RESIDUAL[i] = diff;
                WORST_BONE[i] = bone[i];
                WORST_PARTS[i] = parts;
                WORST_WALK[i] = kept;
                WORST_C3[i] = c3[i];
            }
            if (walking && !attacking) {
                WALK_ALONE_MIN[i] = Math.min(WALK_ALONE_MIN[i], walkOnly[i]);
                WALK_ALONE_MAX[i] = Math.max(WALK_ALONE_MAX[i], walkOnly[i]);
            }
            if (overlap && actionWeight >= 1.0D - 1.0E-6D) {
                WALK_KEPT_MIN[i] = Math.min(WALK_KEPT_MIN[i], kept);
                WALK_KEPT_MAX[i] = Math.max(WALK_KEPT_MAX[i], kept);
            }
            if (overlap) {
                BONE_OVERLAP_MIN[i] = Math.min(BONE_OVERLAP_MIN[i], bone[i]);
                BONE_OVERLAP_MAX[i] = Math.max(BONE_OVERLAP_MAX[i], bone[i]);
                EFF_MIN[i] = Math.min(EFF_MIN[i], eff[i]);
                EFF_MAX[i] = Math.max(EFF_MAX[i], eff[i]);
            }
        }

        if (remainingTicks == 0) {
            report();
        }
    }

    private static void report() {
        WardenGirlMod.LOGGER.info("[blendcheck] ==== C2 + C3 합성 실측 ====");
        WardenGirlMod.LOGGER.info("[blendcheck] 틱 {}..{} ({}틱), 프레임 {}개",
                startTick, lastTick, lastTick - startTick + 1, sampleCount);
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[blendcheck] blend arm_x=%.2f  body_y=%.2f  head_y=%.2f   look_gain=%.2f",
                AnimParams.BLEND_WALK_ARM_X.get(), AnimParams.BLEND_WALK_BODY_Y.get(),
                AnimParams.BLEND_WALK_HEAD_Y.get(), AnimParams.LOOK_GAIN.get()));

        WardenGirlMod.LOGGER.info("[blendcheck] ---- 측정 유효 조건 (판정보다 먼저) ----");
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[blendcheck]   걷기 프레임        : %d / %d   %s",
                walkFrames, sampleCount, walkFrames > 0 ? "OK" : "**없음**"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[blendcheck]   actiontest 프레임  : %d / %d   %s",
                actionFrames, sampleCount, actionFrames > 0 ? "OK" : "**없음**"));
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[blendcheck]   겹친 프레임        : %d / %d   %s",
                overlapFrames, sampleCount,
                overlapFrames > 0 ? "OK — 이것이 이 측정의 핵심 조건이다"
                        : "**측정 불가 — 두 레이어가 한 번도 겹치지 않았다**"));
        if (AnimParams.LOOK_GAIN.get() > 1.0E-6D) {
            WardenGirlMod.LOGGER.info("[blendcheck]   look_gain 이 0 이 아니다 —"
                    + " head.yRot 재구성 잔차는 판정 대상이 아니다");
        }
        if (overlapFrames == 0) {
            WardenGirlMod.LOGGER.info("[blendcheck] **판정 없음.** 겹친 프레임이 0 이면 합성을"
                    + " 관측하지 못한 것이다. 통과가 아니다.");
            WardenGirlMod.LOGGER.info("[blendcheck] ==== 끝 ====");
            return;
        }

        WardenGirlMod.LOGGER.info("[blendcheck] ---- 재구성 (본 = 걷기×가중치 + 그외 + C3) ----");
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[blendcheck] %-15s %10s %10s %10s %10s %12s  %s",
                "채널", "최악 본", "부품 합", "그중 걷기", "그중 C3", "최대 잔차", "판정"));
        int fails = 0;
        for (int i = 0; i < N; i++) {
            boolean judged = i != 3 || AnimParams.LOOK_GAIN.get() <= 1.0E-6D;
            boolean ok = RESIDUAL[i] <= EPSILON;
            if (judged && !ok) {
                fails++;
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[blendcheck] %-15s %10.4f %10.4f %10.4f %10.4f %12.6f  %s",
                    CHANNEL[i], WORST_BONE[i], WORST_PARTS[i], WORST_WALK[i], WORST_C3[i],
                    RESIDUAL[i], judged ? (ok ? "일치" : "FAIL") : "보고 전용 (look_gain≠0)"));
        }

        WardenGirlMod.LOGGER.info("[blendcheck] ---- 걷기 기여 폭: 공격 없음 vs 공격 전량 구간 ----");
        WardenGirlMod.LOGGER.info("[blendcheck]   전량 구간 표본 {}프레임. 폭 비율이 곧 적용된 가중치다",
                legFullFrames);
        for (int i = 0; i < N; i++) {
            double alone = span(WALK_ALONE_MIN[i], WALK_ALONE_MAX[i]);
            double kept = span(WALK_KEPT_MIN[i], WALK_KEPT_MAX[i]);
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[blendcheck] %-15s 공격없음 폭 %8.4f | 전량 중 남은 걷기 폭 %8.4f "
                            + "(비 %5.3f) | 적용 가중치 %.3f..%.3f | 겹침 중 본 %8.4f..%8.4f",
                    CHANNEL[i], alone, kept, alone > 1.0E-6D ? kept / alone : 0.0D,
                    EFF_MIN[i], EFF_MAX[i], BONE_OVERLAP_MIN[i], BONE_OVERLAP_MAX[i]));
        }

        double legSpan = span(legMinFull, legMaxFull);
        // 4.4.2 swings leg_right.xRot through -18..+18, i.e. a 36 degree span over a full cycle.
        // Half of that is a generous floor for a window that may not cover a whole cycle.
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[blendcheck] C3 전량 구간의 leg_right.xRot %8.4f .. %8.4f (폭 %.4f, 프레임 %d)   %s",
                legMinFull, legMaxFull, legSpan, legFullFrames,
                legFullFrames < 20 ? "**표본 부족 — 다리 판정 측정 불가**"
                        : legSpan > 18.0D ? "다리는 계속 걷는다 — 가중치가 겹치는 축에만 적용됐다"
                        : "**다리 스윙이 죽었다 — 가중치가 걷기 전체에 걸렸다**"));
        WardenGirlMod.LOGGER.info("[blendcheck] 판정 대상 채널 중 불일치: {}개 (허용 {})",
                fails, String.format(Locale.ROOT, "%.0e", EPSILON));
        WardenGirlMod.LOGGER.info("[blendcheck] ==== 끝 ====");
    }

    private static double span(double min, double max) {
        return max < min ? 0.0D : max - min;
    }
}
