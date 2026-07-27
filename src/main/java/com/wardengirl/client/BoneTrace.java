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
 * <p>So: <b>9 bones × 3 axes, unconditionally.</b> "This motion only uses these bones" is an
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
 * {@link #footDisplacement}. This is not a T2 one-off: it is the check that answers "do the feet
 * stay planted", and walking (T5), the sonic boom (T8) and the dash (T9) all need it.
 */
public final class BoneTrace {

    private BoneTrace() {
    }

    private static int remainingTicks = 0;
    private static int sampleCount = 0;
    private static final Map<String, double[]> MIN = new LinkedHashMap<>();
    private static final Map<String, double[]> MAX = new LinkedHashMap<>();
    private static final Map<String, double[]> FIRST = new LinkedHashMap<>();
    private static final Map<String, double[]> LAST = new LinkedHashMap<>();
    private static final Map<String, int[]> MONOTONIC = new LinkedHashMap<>();
    /** bone -> {maxAbsDx, maxAbsDy, maxAbsDz, maxHorizontal} in model pixels. */
    private static final Map<String, double[]> FOOT = new LinkedHashMap<>();

    public static void start(int ticks) {
        remainingTicks = ticks;
        sampleCount = 0;
        MIN.clear();
        MAX.clear();
        FIRST.clear();
        LAST.clear();
        MONOTONIC.clear();
        FOOT.clear();
        WardenGirlMod.LOGGER.info("[trace] 시작 — {}틱 동안 본 9개 × 3축 전부 기록한다", ticks);
    }

    public static boolean isRunning() {
        return remainingTicks > 0;
    }

    /**
     * One sample.
     *
     * @param rotations bone -> {x,y,z} in degrees, all nine bones
     * @param positions bone -> {x,y,z} in model pixels, all nine bones
     */
    public static void sample(Map<String, double[]> rotations, Map<String, double[]> positions) {
        if (remainingTicks <= 0) {
            return;
        }
        remainingTicks--;
        sampleCount++;

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

        accumulateFoot(Bones.LEG_RIGHT, rotations, positions);
        accumulateFoot(Bones.LEG_LEFT, rotations, positions);

        if (remainingTicks == 0) {
            report(positions);
        }
    }

    // ---- foot displacement -------------------------------------------------------------------

    // Model pivots, straight out of warden_girl.geo.json. Part 3.7.
    private static final double[] PIVOT_ROOT = {0, 0, 0};
    private static final double[] PIVOT_HIP = {0, 12, 0};
    private static final double[] PIVOT_LEG_R = {-2.0, 12, 0};
    private static final double[] PIVOT_LEG_L = {2.0, 12, 0};
    /**
     * Absolute model y of the sole. The leg cube spans y 0..12 and its pivot is at y=12, so the
     * sole sits 12px below the leg pivot — that 12px is the lever the whole check exists to expose.
     */
    private static final double SOLE_Y = 0.0D;

    private static double[] pivotOf(String leg) {
        return leg.equals(Bones.LEG_RIGHT) ? PIVOT_LEG_R : PIVOT_LEG_L;
    }

    private static void accumulateFoot(String leg, Map<String, double[]> rot,
                                       Map<String, double[]> pos) {
        double[] legPivot = pivotOf(leg);
        double[] rest = {legPivot[0], SOLE_Y, legPivot[2]};
        double[] p = solePosition(leg, rot, pos);
        double dx = p[0] - rest[0];
        double dy = p[1] - rest[1];
        double dz = p[2] - rest[2];
        double horiz = Math.hypot(dx, dz);
        double[] acc = FOOT.computeIfAbsent(leg, k -> new double[]{0, 0, 0, 0});
        acc[0] = Math.max(acc[0], Math.abs(dx));
        acc[1] = Math.max(acc[1], Math.abs(dy));
        acc[2] = Math.max(acc[2], Math.abs(dz));
        acc[3] = Math.max(acc[3], horiz);
    }

    /**
     * Forward kinematics for the centre of one sole, in model pixels.
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
    private static double[] solePosition(String leg, Map<String, double[]> rot,
                                         Map<String, double[]> pos) {
        double[] legPivot = pivotOf(leg);
        double[] q = {legPivot[0], SOLE_Y, legPivot[2]};
        q = applyBone(q, leg, legPivot, rot, pos);
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
    }

    /**
     * What each axis is supposed to do, derived from {@link AnimParams} so the check follows the
     * parameters instead of duplicating them.
     */
    private static Map<String, Expect[]> expectations() {
        double breathBody = AnimParams.BREATH_BODY_X.get();
        double breathHead = AnimParams.BREATH_HEAD_X.get();
        double armZ = AnimParams.BREATH_ARM_Z.get();
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
        m.put(Bones.HEAD, new Expect[]{
                Expect.band(AnimParams.OFFSET_HEAD_X.get(), breathHead, pBreath),
                Expect.constant(0),
                Expect.band(0, swayHead + weightHead, Math.max(pSway, pWeight))});
        // headgear is spring-driven from T3 onward; in T2 nothing writes it.
        m.put(Bones.HEADGEAR, new Expect[]{
                Expect.constant(0), Expect.constant(0), Expect.constant(0)});
        // arm zRot carries an outward-only bias: amp*(1+sin) spans 0 .. 2*amp.
        m.put(Bones.ARM_RIGHT, new Expect[]{
                Expect.constant(AnimParams.OFFSET_ARM_R_X.get()),
                Expect.constant(0),
                Expect.band(armZ, armZ, pBreath)});
        m.put(Bones.ARM_LEFT, new Expect[]{
                Expect.constant(AnimParams.OFFSET_ARM_L_X.get()),
                Expect.constant(0),
                Expect.band(-armZ, armZ, pBreath)});
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

    private static void report(Map<String, double[]> positions) {
        Map<String, Expect[]> expect = expectations();
        int fails = 0;

        WardenGirlMod.LOGGER.info("[trace] === {}샘플 수집 완료. 본 9개 × 3축 판정 ===", sampleCount);
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
                    verdict = String.format(Locale.ROOT,
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

        fails += reportFeet();

        if (fails == 0) {
            WardenGirlMod.LOGGER.info("[trace] === 판정: 전 항목 통과 (회전 27 + 발끝 2) ===");
        } else {
            WardenGirlMod.LOGGER.error("[trace] === 판정: 실패 {}개 ===", fails);
        }
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
            WardenGirlMod.LOGGER.info(String.format(Locale.ROOT,
                    "[trace] %-10s 최대|dx|=%.4f  최대|dy|=%.4f  최대|dz|=%.4f  최대 수평변위=%.4f  %s",
                    leg, a[0], a[1], a[2], a[3], ok ? "OK" : "FAIL 발이 미끄러진다"));
        }
        return fails;
    }
}
