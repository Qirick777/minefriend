package com.wardengirl.anim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tunable animation parameter, in one file. Design doc Part 3.5 / Part 3.6.
 *
 * <p>The doc is explicit that this stays a single file: <em>"파라미터가 흩어지면 튜닝이 불가능해진다."</em>
 *
 * <h2>Where a value lives, and why</h2>
 *
 * Design doc Part 3.6 splits tuning into two paths, and they must not be confused:
 *
 * <ul>
 *   <li><b>The shape of a motion lives in json</b> — {@code warden_girl.animation.json} holds the
 *       Molang formulas. Editing those is an F3+T reload, ~3 seconds, no recompile.</li>
 *   <li><b>The magnitude lives here</b> — amplitudes and speeds are exported to Molang as
 *       {@code query.wg_*} variables, so a formula reads
 *       {@code query.wg_breath_body_x * math.sin(query.wg_time * query.wg_breath_speed)}.</li>
 * </ul>
 *
 * <p>That split is what makes {@code /wardengirl param} work at all. If the amplitudes were baked
 * into the json as literals, no command could move them; if the formulas were built in Java, F3+T
 * would be useless. Both halves are needed.
 *
 * <h2>Presets</h2>
 *
 * <p>Preset levels are a geometric scale — <b>×0.25 / ×0.5 / ×1 / ×2 / ×4</b> of the default.
 * Geometric rather than linear because the question being answered is "what order of magnitude is
 * right", and equal ratios are what the eye actually compares. Nudging a default by ±10% cannot
 * answer that; seeing 0.1 next to 1.6 can.
 *
 * <p><b>The sweep applies to oscillation amplitudes only</b> — breathing, sway, weight shift,
 * bounce. Static pose offsets and rate constants are excluded: see {@link Param#presetScaled}.
 * Those are shapes and ratios, not magnitudes, and ×4 on them produces a different pose or a
 * divergent spring rather than a bigger version of the same motion.
 *
 * <p>Units are degrees for rotations, pixels for positions, and ticks for periods and phase
 * delays, per Part 4.0.3. Nothing here is "degrees of phase per tick" — the json divides 360 by
 * the period, so the tunable number is the one a human reasons about.
 */
public final class AnimParams {

    private AnimParams() {
    }

    /** Five-level sweep. Design doc Part 4.12 recommends looking at the extremes first. */
    public enum Preset {
        MIN(0.25D), LOW(0.5D), DEFAULT(1.0D), HIGH(2.0D), MAX(4.0D);

        public final double factor;

        Preset(double factor) {
            this.factor = factor;
        }

        public static Preset parse(String raw) {
            for (Preset p : values()) {
                if (p.name().equalsIgnoreCase(raw)) {
                    return p;
                }
            }
            return null;
        }
    }

    /** One tunable value. */
    public static final class Param {
        public final String key;
        /** Molang name this is exported as, or null if it is a Java-side value. */
        public final String molang;
        public final double defaultValue;
        public final String unit;
        public final String task;
        public final String description;
        /** Bones whose rotation this parameter moves — used by the command's readback. */
        public final List<String> affects;
        /**
         * Whether {@link #applyPreset} moves this value.
         *
         * <p>The preset sweep is a sweep of <em>magnitudes</em> — "is this motion an order of
         * magnitude too big". Not every number here is a magnitude. A spring's stiffness and
         * damping are rate constants of a difference equation, and {@code ×4} does not make the
         * spring "four times bigger": {@code DAMPING 0.35 → 1.4} makes the velocity term overshoot
         * its own sign every tick and the spring diverges. A gain of 1.0 that means "match the
         * entity's actual look direction" has no meaningful ×4 either.
         *
         * <p>So those are held fixed across presets. They are still settable individually with
         * {@code /wardengirl param set} — the exclusion is from the sweep, not from tuning.
         */
        public final boolean presetScaled;

        private double value;

        Param(String key, String molang, double defaultValue, String unit, String task,
              String description, boolean presetScaled, List<String> affects) {
            this.key = key;
            this.molang = molang;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
            this.unit = unit;
            this.task = task;
            this.description = description;
            this.presetScaled = presetScaled;
            this.affects = affects;
        }

        public double get() {
            return this.value;
        }

        public void set(double v) {
            if (this.value != v) {
                this.value = v;
                GENERATION++;
            }
        }

        public double presetValue(Preset preset) {
            return this.presetScaled ? this.defaultValue * preset.factor : this.defaultValue;
        }
    }

    private static final Map<String, Param> REGISTRY = new LinkedHashMap<>();

    /**
     * Bumped on every value change.
     *
     * <p>Exists so the renderer can notice "a parameter moved" without polling every value, and
     * print the resulting bone rotations on the next frame — design doc Part 6.3 requires
     * {@code param set} to report the bone values it affected, and only the client can measure
     * those.
     */
    private static volatile long GENERATION = 0L;

    public static long generation() {
        return GENERATION;
    }

    private static Param add(String key, String molang, double def, String unit, String task,
                             String desc, String... affects) {
        Param p = new Param(key, molang, def, unit, task, desc, true, List.of(affects));
        REGISTRY.put(key, p);
        return p;
    }

    /** Like {@link #add}, but the preset sweep leaves it alone. See {@link Param#presetScaled}. */
    private static Param addFixed(String key, String molang, double def, String unit, String task,
                                  String desc, String... affects) {
        Param p = new Param(key, molang, def, unit, task, desc, false, List.of(affects));
        REGISTRY.put(key, p);
        return p;
    }

    // ---- 4.3.1 호흡 -------------------------------------------------------------------------
    //
    // Periods, not speeds. The json divides 360 by the period, so what is tuned here is the number
    // a human actually reasons about: "how many ticks per breath". Amplitude alone cannot fix an
    // impression of "too fast", so the period has to be reachable from the command too.

    public static final Param BREATH_PERIOD = add("breath_period", "wg_breath_period", 60.0D,
            "tick", "T2", "호흡 주기. 60틱 = 3초 = 분당 20회, 사람 안정 시 호흡에 가깝다 (30 → 44 → 60 확정)",
            "body", "head", "arm_right", "arm_left");
    public static final Param BREATH_BODY_X = add("breath_body_x", "wg_breath_body_x", 0.5D,
            "deg", "T2", "들숨에 상체를 뒤로 젖히는 진폭", "body");
    public static final Param BREATH_BODY_Y = add("breath_body_y", "wg_breath_body_y", 0.12D,
            "px", "T2", "흉곽 상승", "body");
    public static final Param BREATH_HEAD_X = add("breath_head_x", "wg_breath_head_x", 0.25D,
            "deg", "T2", "머리 수평 유지 보정 (2.0틱 지연 = 60틱에서 -12°. 구판 44틱 -16° 환산)", "head");
    public static final Param BREATH_ARM_Z = add("breath_arm_z", "wg_breath_arm_z", 0.35D,
            "deg", "T2", "들숨에 어깨가 바깥으로. 바깥 방향 바이어스 (관통 방지). 1.5틱 지연 = 60틱에서 -9°",
            "arm_right", "arm_left");

    // ---- 4.3.2 바운스 ------------------------------------------------------------------------

    public static final Param BOUNCE_PERIOD = add("bounce_period", "wg_bounce_period", 25.0D,
            "tick", "T2", "바운스 주기", "root");
    /**
     * Confirmed 0 for the idle pose by human judgement — a standing companion should not bob.
     * The formula and the parameter are kept rather than deleted because T5 may want bounce while
     * walking; this is "off when standing", not "removed".
     */
    public static final Param BOUNCE_AMPLITUDE = add("bounce_amplitude", "wg_bounce_amp", 0.0D,
            "px", "T2", "정지 상태 기본값 0 (사용자 판정). 수식은 T5 이동 중 바운스 대비로 보존", "root");

    // ---- 4.3.3 미세 흔들림 --------------------------------------------------------------------

    public static final Param SWAY_PERIOD = add("sway_period", "wg_sway_period", 53.0D,
            "tick", "T2", "미세 흔들림 주기", "body", "head");
    public static final Param SWAY_BODY_Z = add("sway_body_z", "wg_sway_body_z", 1.2D,
            "deg", "T2", "상체 좌우", "body");
    public static final Param SWAY_HEAD_Z = add("sway_head_z", "wg_sway_head_z", 0.7D,
            "deg", "T2", "머리 반대 보정 (2.06틱 지연 = 53틱에서 -14°)", "head");
    /**
     * Body, not hip. Rotating the hip swings the feet along a 12px arc, which reads as sliding —
     * see Part 11. Body pivots at the hip too, but the legs hang off hip, so body rotation leaves
     * them untouched.
     */
    public static final Param SWAY_BODY_Y = add("sway_body_y", "wg_sway_body_y", 0.5D,
            "deg", "T2", "상체 미세 비틀기 (2.94틱 지연 = 53틱에서 -20°). 구판 hip.yRot 에서 이관 — hip 회전은 발을 미끄러뜨린다",
            "body");

    // ---- 4.3.5 체중 이동 ---------------------------------------------------------------------
    //
    // The third axis, and the slowest. 60 / 53 / 79 share no practical common multiple (LCM
    // 251,220 ticks ~ 209 min), so the three layers never realign — that non-repetition is the
    // entire point of this layer, not its amplitude.

    public static final Param WEIGHT_SHIFT_PERIOD = add("weight_shift_period", "wg_weight_period", 79.0D,
            "tick", "T2", "체중 이동 주기. 60/53 과 공배수가 없다 (LCM 251,220틱 ≈ 209분)", "body", "head");
    public static final Param WEIGHT_SHIFT_AMP = add("weight_shift_amp", "wg_weight_amp", 0.8D,
            "deg", "T2", "아주 느린 좌우 체중 이동 (body zRot 에 가산). 구판 hip.zRot 에서 이관", "body");
    public static final Param WEIGHT_SHIFT_HEAD_Z = add("weight_shift_head_z", "wg_weight_head_z", 0.4D,
            "deg", "T2", "체중 이동에 대한 머리 반대 보정 (5.27틱 지연 = 79틱에서 -24°)", "head");

    // ---- 4.3.4 기본 자세 오프셋 (정적, C1 과 별개로 상시 가산) --------------------------------------
    //
    // None of these are preset-swept. A static pose offset is a SHAPE, not a magnitude: turning the
    // 안짱 from 2 degrees into 8 is not searching an amplitude range, it is standing differently —
    // and it measurably broke the grounding, driving the sole corner from 0.0987px to 0.3946px.
    // The preset sweep keeps only the oscillation amplitudes it was designed to answer for.

    public static final Param OFFSET_ARM_R_X = addFixed("offset_arm_right_x", null, 4.0D,
            "deg", "T2", "오른팔을 힘 빼고 살짝 앞으로", "arm_right");
    public static final Param OFFSET_ARM_L_X = addFixed("offset_arm_left_x", null, 4.0D,
            "deg", "T2", "왼팔을 힘 빼고 살짝 앞으로", "arm_left");
    public static final Param OFFSET_LEG_R_Y = addFixed("offset_leg_right_y", null, 2.0D,
            "deg", "T2", "오른발 안짱", "leg_right");
    public static final Param OFFSET_LEG_L_Y = addFixed("offset_leg_left_y", null, -2.0D,
            "deg", "T2", "왼발 안짱", "leg_left");
    public static final Param OFFSET_HEAD_X = addFixed("offset_head_x", null, -3.0D,
            "deg", "T2", "목 앞으로", "head");
    public static final Param OFFSET_BODY_X = addFixed("offset_body_x", null, -1.5D,
            "deg", "T2", "어깨 앞으로", "body");

    // ---- 4.5.1 headgear 감쇠 스프링 (T3) --------------------------------------------------------
    //
    // headgear is head's child, so head's rotation is already inherited. What the spring adds is
    // the lag error only — (angleDeg - targetDeg) — which is why the applied value is a difference
    // and not the spring angle itself. Applying the angle would turn the decoration twice.

    public static final Param HEADGEAR_STIFFNESS = addFixed("headgear_stiffness", null, 0.25D,
            "계수", "T3", "스프링 강성. 클수록 빠르게 따라온다. 4.5.2 상태별 전환 대상", "headgear_right", "headgear_left");
    public static final Param HEADGEAR_DAMPING = addFixed("headgear_damping", null, 0.35D,
            "계수", "T3", "감쇠. 발산하면 올린다. 1.0 을 넘기면 속도 항의 부호가 매 틱 뒤집혀 발산한다", "headgear_right", "headgear_left");
    public static final Param HEADGEAR_AMPLITUDE = add("headgear_amplitude", null, 1.3D,
            "배율", "T3", "지연 오차분에 곱하는 배율. 장식이 과장되게 휘청이는 정도", "headgear_right", "headgear_left");
    public static final Param HEADGEAR_MAX_ANGLE = add("headgear_max_angle", null, 45.0D,
            "deg", "T3",
            "스프링 출력 클램프. 35 에서는 기본 AMPLITUDE 만으로도 pitch 스윙에서 클램프에 닿아 "
                    + "튜닝 여지가 없었다. 클램프는 안전장치이지 상시 제한이 아니다 (35 → 45)",
            "headgear_right", "headgear_left");

    /**
     * Left-right stiffness split, as a fraction. Right uses {@code STIFFNESS}, left uses
     * {@code STIFFNESS × (1 - HEADGEAR_ASYMMETRY)}.
     *
     * <p>Splitting the bone in two achieves <em>nothing</em> on its own: both springs take the same
     * input (head rotation) and the same constants, so they produce bit-identical output and the
     * two tendrils move as one rigid piece. Some difference has to exist for the split to mean
     * anything. A stiffness ratio is the option with no steady-state cost — both sides still
     * converge on the same equilibrium, only the transient differs — so the tendrils drift apart
     * while moving and agree again when still.
     *
     * <p>Excluded from the preset sweep for the same reason as the stiffness it scales. Set it to
     * 0 to make the two sides identical again.
     */
    public static final Param HEADGEAR_ASYMMETRY = addFixed("headgear_asymmetry", null, 0.06D,
            "비율", "T3", "좌우 스프링 강성 차이. 왼쪽 = STIFFNESS × (1 - 이 값). 0 이면 좌우 동일",
            "headgear_left");

    // ---- 4.6 시선 추적 (T3 에서는 가산과 클램프만. 감쇠 보간 · 근거리 반응은 T4) ------------------------

    /**
     * Overall gain on the vanilla look direction.
     *
     * <p>1.0 means "the head bone matches the direction the entity is actually looking". Anything
     * else desynchronises the rendered gaze from the AI's gaze, so this is excluded from the preset
     * sweep — see {@link Param#presetScaled}. It exists as a knob for T4's damping work.
     */
    public static final Param LOOK_GAIN = addFixed("look_gain", null, 1.0D,
            "배율", "T3", "바닐라 시선을 head 본에 싣는 배율. 1.0 = 엔티티가 실제로 보는 방향 그대로", "head");
    public static final Param LOOK_YAW_MAX = add("look_yaw_max", null, 75.0D,
            "deg", "T3",
            "head yRot 클램프 (4.6). 바닐라 getMaxHeadYRot() 과 같은 값이라 기준이 명확하다. "
                    + "70 이면 실측 6.0%, 75 면 4.0% 의 시간 동안 걸린다 (70/75/90 비교는 param set 으로)",
            "head");
    public static final Param LOOK_PITCH_MAX = add("look_pitch_max", null, 35.0D,
            "deg", "T3", "head xRot 클램프 (4.6)", "head");

    // ------------------------------------------------------------------------------------------

    public static Map<String, Param> all() {
        return REGISTRY;
    }

    public static Param get(String key) {
        return REGISTRY.get(key);
    }

    /** Applies one preset level across every parameter. Returns key -> {before, after}. */
    public static Map<String, double[]> applyPreset(Preset preset) {
        Map<String, double[]> changed = new LinkedHashMap<>();
        for (Param p : REGISTRY.values()) {
            double before = p.get();
            double after = p.presetValue(preset);
            if (before != after) {
                p.set(after);
                changed.put(p.key, new double[]{before, after});
            }
        }
        return changed;
    }
}
