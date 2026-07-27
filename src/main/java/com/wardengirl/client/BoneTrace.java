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
 * guarantees the dump can never contradict it.
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

    public static void start(int ticks) {
        remainingTicks = ticks;
        sampleCount = 0;
        MIN.clear();
        MAX.clear();
        FIRST.clear();
        LAST.clear();
        MONOTONIC.clear();
        WardenGirlMod.LOGGER.info("[trace] 시작 — {}틱 동안 본 9개 × 3축 전부 기록한다", ticks);
    }

    public static boolean isRunning() {
        return remainingTicks > 0;
    }

    /** One sample. {@code rotations} is bone -> {x,y,z} degrees. */
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

        if (remainingTicks == 0) {
            report(positions);
        }
    }

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
        double swayBody = AnimParams.SWAY_BODY_Z.get();
        double swayHead = AnimParams.SWAY_HEAD_Z.get();
        double swayHip = AnimParams.SWAY_HIP_Y.get();
        double weight = AnimParams.WEIGHT_SHIFT_AMP.get();
        double weightHead = AnimParams.WEIGHT_SHIFT_HEAD_Z.get();
        double pBreath = AnimParams.BREATH_PERIOD.get();
        double pSway = AnimParams.SWAY_PERIOD.get();
        double pWeight = AnimParams.WEIGHT_SHIFT_PERIOD.get();

        Map<String, Expect[]> m = new LinkedHashMap<>();
        m.put(Bones.ROOT, new Expect[]{Expect.constant(0), Expect.constant(0), Expect.constant(0)});
        m.put(Bones.HIP, new Expect[]{
                Expect.constant(0), Expect.band(0, swayHip, pSway), Expect.band(0, weight, pWeight)});
        m.put(Bones.BODY, new Expect[]{
                Expect.band(AnimParams.OFFSET_BODY_X.get(), breathBody, pBreath),
                Expect.constant(0),
                Expect.band(0, swayBody, pSway)});
        m.put(Bones.HEAD, new Expect[]{
                Expect.band(AnimParams.OFFSET_HEAD_X.get(), breathHead, pBreath),
                Expect.constant(0),
                // 4.3.3 and 4.3.5 both write head zRot; the band is the sum of both amplitudes,
                // and the slower of the two periods governs how long a full cycle takes.
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

        if (fails == 0) {
            WardenGirlMod.LOGGER.info("[trace] === 판정: 전 축 통과 (27개 항목) ===");
        } else {
            WardenGirlMod.LOGGER.error("[trace] === 판정: 실패 {}개 ===", fails);
        }
    }
}
