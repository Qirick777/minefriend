package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.Bones;

import java.util.Locale;
import java.util.Map;

/**
 * Measures what C1 actually put on the bone, against what the formula says it should be.
 *
 * <h2>What this answers</h2>
 *
 * The T5 C1 migration has to establish two things, and neither of them can be settled by looking
 * at the code:
 *
 * <ol>
 *   <li><b>이관 전후가 부호도 크기도 같은가.</b> Comparing the two runs to <em>each other</em> is
 *       not possible — {@code query.wg_time} is an absolute tick count, the three periods
 *       (60 / 53 / 79) share no practical common multiple, and two measurements never start at the
 *       same tick. So neither run is compared to the other: <b>both are compared to the formula</b>,
 *       evaluated at the sample's own {@code tickCount + partialTick}. If both residuals are zero,
 *       both paths produce the same function of time, which is a stronger statement than "the two
 *       runs looked similar".</li>
 *   <li><b>걷는 중에 C1 이 살아남는가.</b> That is the whole point of the migration. Six axes —
 *       body z, body pos y, head x/z, arm_right z, arm_left z — are written by C1 and by nothing
 *       else in the walk cycle, so during walking their measured value should still equal the
 *       formula exactly. Before the migration they measured 0.000 oscillation, because C2 assigned
 *       zero over the top of them.</li>
 * </ol>
 *
 * <h2>Where the sample is taken</h2>
 *
 * In {@code setCustomAnimations}, immediately after the C1 addition and <b>before</b> the static
 * offsets, the look, the turn lean and the spring. So on both paths the number read is C1 plus
 * whatever the locomotion controller wrote, and nothing else:
 *
 * <pre>
 *   c1_source = 0 (json)  : controller 'vital' writes C1, then 'locomotion' may overwrite it
 *   c1_source = 1 (Java)  : controller 'vital' writes 0,  'locomotion' writes its clip,
 *                           then VitalMotion adds C1 where no controller can reach
 * </pre>
 *
 * Standing, the locomotion clip is {@code idle} and contributes nothing, so the reading is pure C1
 * on both paths and every residual must be 0. Walking, it contributes on body x, body y and root
 * pos y, so those three residuals are expected to be non-zero and are labelled as such; the other
 * six are the ones under test.
 *
 * <h2>Residual, not oscillation width</h2>
 *
 * Width alone cannot see a sign flip — a sine and its negative have identical width, and this
 * project has already shipped one inverted parameter that a symmetric measurement would never have
 * caught. The residual is signed and per-sample, so a flip shows up as twice the amplitude rather
 * than as nothing at all.
 */
public final class VitalCheck {

    private VitalCheck() {
    }

    /** The nine channels C1 drives, in the order of {@link VitalMotion.Contribution}. */
    private static final String[] CHANNEL = {
            "body.xRot", "body.yRot", "body.zRot", "body.pos.y",
            "head.xRot", "head.zRot", "arm_right.zRot", "arm_left.zRot", "root.pos.y"};

    /**
     * Which of the nine the walk clip also writes with a non-zero value.
     *
     * <p>Read off {@code warden_girl.animation.json}'s {@code walk} bones, not guessed: {@code body}
     * rotation is {@code [walk_body_lean, ±3, 0]} and {@code root} position is {@code [0, 0..0.7,
     * 0]}. Everything else the walk cycle touches is either a different axis or a literal 0, so on
     * the remaining six channels the measured value must still be C1 alone even while walking —
     * and those six are the ones under test.
     */
    private static final boolean[] SHARED_WITH_C2 = {
            true, true, false, false, false, false, false, false, true};

    private static final String[] SHARED_NOTE = {
            "walk body.xRot(4.4.2 상체 기울기)", "walk body.yRot(4.4.2 상체 좌우 ±3)",
            "", "", "", "", "", "", "walk root.pos.y(4.4.2 상하동 0~0.7)"};

    private static final int N = 9;

    private static int remainingTicks = 0;
    private static int totalTicks = 0;
    private static int startTick = Integer.MIN_VALUE;
    private static int lastTick = Integer.MIN_VALUE;
    private static int sampleCount = 0;
    private static double sourceAtStart = -1.0D;

    private static final double[] MEAS_MIN = new double[N];
    private static final double[] MEAS_MAX = new double[N];
    private static final double[] PRED_MIN = new double[N];
    private static final double[] PRED_MAX = new double[N];
    private static final double[] RES_MIN = new double[N];
    private static final double[] RES_MAX = new double[N];
    private static final double[] RES_ABS_MAX = new double[N];
    /** The sample where the residual was largest, kept so the report can print the raw pair. */
    private static final double[] WORST_MEAS = new double[N];
    private static final double[] WORST_PRED = new double[N];
    private static final double[] WORST_TIME = new double[N];

    public static void start(int ticks) {
        EntityLock.reset();
        remainingTicks = ticks;
        totalTicks = ticks;
        startTick = Integer.MIN_VALUE;
        lastTick = Integer.MIN_VALUE;
        sampleCount = 0;
        walkingSamples = 0;
        sourceAtStart = AnimParams.C1_SOURCE.get();
        for (int i = 0; i < N; i++) {
            MEAS_MIN[i] = Double.POSITIVE_INFINITY;
            MEAS_MAX[i] = Double.NEGATIVE_INFINITY;
            PRED_MIN[i] = Double.POSITIVE_INFINITY;
            PRED_MAX[i] = Double.NEGATIVE_INFINITY;
            RES_MIN[i] = Double.POSITIVE_INFINITY;
            RES_MAX[i] = Double.NEGATIVE_INFINITY;
            RES_ABS_MAX[i] = -1.0D;
            WORST_MEAS[i] = 0.0D;
            WORST_PRED[i] = 0.0D;
            WORST_TIME[i] = 0.0D;
        }
        WardenGirlMod.LOGGER.info(
                "[vitalcheck] 시작 — {}틱, c1_source={} ({})",
                ticks, String.format(Locale.ROOT, "%.0f", sourceAtStart),
                sourceAtStart >= 0.5D ? "Java 가산 = 이관 후" : "json Molang = 이관 전");
    }

    public static boolean isRunning() {
        return remainingTicks > 0;
    }

    /**
     * One sample.
     *
     * @param rotations bone -> {x,y,z} degrees, all ten bones
     * @param positions bone -> {x,y,z} model pixels, all ten bones
     * @param tickCount the entity's tick — the window is counted in ticks, never in frames
     */
    public static void sample(Map<String, double[]> rotations, Map<String, double[]> positions,
                              int tickCount, float partialTick) {
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

        double time = tickCount + partialTick;
        boolean walking = false;
        VitalMotion.Contribution c = VitalMotion.evaluate(time);

        double[] body = rotations.get(Bones.BODY);
        double[] head = rotations.get(Bones.HEAD);
        double[] armR = rotations.get(Bones.ARM_RIGHT);
        double[] armL = rotations.get(Bones.ARM_LEFT);
        double[] bodyPos = positions.get(Bones.BODY);
        double[] rootPos = positions.get(Bones.ROOT);
        if (body == null || head == null || armR == null || armL == null
                || bodyPos == null || rootPos == null) {
            return;
        }

        double[] measured = {
                body[0], body[1], body[2], bodyPos[1],
                head[0], head[2], armR[2], armL[2], rootPos[1]};
        double[] predicted = {
                c.bodyRotX(), c.bodyRotY(), c.bodyRotZ(), c.bodyPosY(),
                c.headRotX(), c.headRotZ(), c.armRightRotZ(), c.armLeftRotZ(), c.rootPosY()};

        for (int i = 0; i < N; i++) {
            double residual = measured[i] - predicted[i];
            // body.xRot is the walk cycle's 상체 기울기 (4.4.2, a constant -4° for the whole clip)
            // and C1's breathing is ±0.5°, so a residual past half a degree on that one channel
            // means the walk clip is contributing on this frame. That makes the walking run
            // self-verifying: a window where this count is 0 measured a standing mob and says
            // nothing about whether C1 survives walking.
            if (i == 0 && Math.abs(residual) > 0.5D) {
                walking = true;
            }
            MEAS_MIN[i] = Math.min(MEAS_MIN[i], measured[i]);
            MEAS_MAX[i] = Math.max(MEAS_MAX[i], measured[i]);
            PRED_MIN[i] = Math.min(PRED_MIN[i], predicted[i]);
            PRED_MAX[i] = Math.max(PRED_MAX[i], predicted[i]);
            RES_MIN[i] = Math.min(RES_MIN[i], residual);
            RES_MAX[i] = Math.max(RES_MAX[i], residual);
            if (Math.abs(residual) > RES_ABS_MAX[i]) {
                RES_ABS_MAX[i] = Math.abs(residual);
                WORST_MEAS[i] = measured[i];
                WORST_PRED[i] = predicted[i];
                WORST_TIME[i] = time;
            }
        }

        if (walking) {
            walkingSamples++;
        }

        if (remainingTicks == 0) {
            report();
        }
    }

    private static int walkingSamples = 0;

    /**
     * Float, not double. The bone stores radians as a {@code float} and the report compares against
     * a {@code double} formula, so the round trip through {@code (float) Math.toRadians(deg)} and
     * back loses about seven significant digits. 1e-4 degrees is two orders of magnitude below
     * anything visible and two orders above that noise floor.
     */
    private static final double EPSILON = 1.0E-4D;

    private static void report() {
        boolean java = sourceAtStart >= 0.5D;
        WardenGirlMod.LOGGER.info("[vitalcheck] ==== C1 실측 (경로 {} = {}) ====",
                java ? 1 : 0, java ? "Java 가산" : "json Molang");
        WardenGirlMod.LOGGER.info("[vitalcheck] 틱 {}..{} ({}틱), 프레임 {}개, {}",
                startTick, lastTick, lastTick - startTick + 1, sampleCount,
                String.format(Locale.ROOT, "%.2f 프레임/틱",
                        sampleCount / Math.max(1.0D, lastTick - startTick + 1.0D)));
        WardenGirlMod.LOGGER.info("[vitalcheck] 측정값 = 본에서 읽은 값 (C1 + 로코모션 컨트롤러)."
                + " 예측값 = VitalMotion.evaluate(tickCount + partialTick). 잔차 = 측정 - 예측");
        WardenGirlMod.LOGGER.info("[vitalcheck] 회전은 도, 위치는 모델 픽셀");
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[vitalcheck] %-16s %9s %9s %9s | %9s %9s %9s | %10s %10s %s",
                "채널", "측정min", "측정max", "측정폭",
                "예측min", "예측max", "예측폭", "잔차max", "잔차절대", "판정"));

        int fails = 0;
        for (int i = 0; i < N; i++) {
            double measWidth = MEAS_MAX[i] - MEAS_MIN[i];
            double predWidth = PRED_MAX[i] - PRED_MIN[i];
            boolean ok = RES_ABS_MAX[i] <= EPSILON;
            String verdict;
            if (SHARED_WITH_C2[i]) {
                // C2 writes this channel too, so a non-zero residual is the walk's own value and
                // says nothing about C1. Reported, never judged.
                verdict = ok ? "일치 (C2 기여 없음 — 정지 중)"
                        : "C2 공유 — 잔차는 " + SHARED_NOTE[i] + " 이다. 판정 대상 아님";
            } else if (ok) {
                verdict = "일치";
            } else {
                verdict = "FAIL 불일치";
                fails++;
            }
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[vitalcheck] %-16s %9.4f %9.4f %9.4f | %9.4f %9.4f %9.4f | %10.6f %10.6f %s",
                    CHANNEL[i], MEAS_MIN[i], MEAS_MAX[i], measWidth,
                    PRED_MIN[i], PRED_MAX[i], predWidth,
                    RES_ABS_MAX[i], Math.max(Math.abs(RES_MIN[i]), Math.abs(RES_MAX[i])),
                    verdict));
        }

        WardenGirlMod.LOGGER.info("[vitalcheck] 최악 잔차 시점의 원본 쌍 (부호 확인용):");
        for (int i = 0; i < N; i++) {
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[vitalcheck]   %-16s t=%10.3f  측정 %+9.4f  예측 %+9.4f  잔차 %+10.6f",
                    CHANNEL[i], WORST_TIME[i], WORST_MEAS[i], WORST_PRED[i],
                    WORST_MEAS[i] - WORST_PRED[i]));
        }

        int flat = 0;
        for (int i = 0; i < N; i++) {
            if (!SHARED_WITH_C2[i] && MEAS_MAX[i] - MEAS_MIN[i] < 1.0E-3D) {
                flat++;
            }
        }
        WardenGirlMod.LOGGER.info(
                "[vitalcheck] C1 전용 6축 중 진동 폭이 0.000 인 축: {}개 "
                        + "(이관 전 걷는 중에는 6개 전부였다 — C2 가 0 을 덮어썼기 때문)", flat);
        WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                "[vitalcheck] walk 클립이 기여한 프레임: %d / %d (%.1f%%) "
                        + "— body.xRot 잔차 > 0.5° 로 판정. 0 이면 이 창은 걷기를 재지 못했다",
                walkingSamples, sampleCount,
                100.0D * walkingSamples / Math.max(1, sampleCount)));
        WardenGirlMod.LOGGER.info("[vitalcheck] 판정 대상 6축 중 불일치: {}개, 허용 오차 {}",
                fails, String.format(Locale.ROOT, "%.0e", EPSILON));
        WardenGirlMod.LOGGER.info("[vitalcheck] ==== 끝 ====");
    }
}
