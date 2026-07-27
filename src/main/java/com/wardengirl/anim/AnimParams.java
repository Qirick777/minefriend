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
 * <p>Every parameter carries five levels on a geometric scale — <b>×0.25 / ×0.5 / ×1 / ×2 / ×4</b>
 * of its default. Geometric rather than linear because the question being answered is "what order
 * of magnitude is right", and equal ratios are what the eye actually compares. Nudging a default
 * by ±10% cannot answer that; seeing 0.1 next to 1.6 can.
 *
 * <p>Units are degrees for rotations and pixels for positions, per Part 4.0.3. Speeds are
 * degrees of phase per tick, so {@code 12} is a 30-tick period (360/30).
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

        private double value;

        Param(String key, String molang, double defaultValue, String unit, String task,
              String description, List<String> affects) {
            this.key = key;
            this.molang = molang;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
            this.unit = unit;
            this.task = task;
            this.description = description;
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
            return this.defaultValue * preset.factor;
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
        Param p = new Param(key, molang, def, unit, task, desc, List.of(affects));
        REGISTRY.put(key, p);
        return p;
    }

    // ---- 4.3.1 호흡 -------------------------------------------------------------------------
    //
    // Periods, not speeds. The json divides 360 by the period, so what is tuned here is the number
    // a human actually reasons about: "how many ticks per breath". Amplitude alone cannot fix an
    // impression of "too fast", so the period has to be reachable from the command too.

    public static final Param BREATH_PERIOD = add("breath_period", "wg_breath_period", 44.0D,
            "tick", "T2", "호흡 주기. 44틱 ≈ 분당 27회 (30틱은 분당 40회로 사람의 3배 가까이 빨랐다)",
            "body", "head", "arm_right", "arm_left");
    public static final Param BREATH_BODY_X = add("breath_body_x", "wg_breath_body_x", 0.5D,
            "deg", "T2", "들숨에 상체를 뒤로 젖히는 진폭", "body");
    public static final Param BREATH_BODY_Y = add("breath_body_y", "wg_breath_body_y", 0.12D,
            "px", "T2", "흉곽 상승", "body");
    public static final Param BREATH_HEAD_X = add("breath_head_x", "wg_breath_head_x", 0.25D,
            "deg", "T2", "머리 수평 유지 보정 (위상 -16°)", "head");
    public static final Param BREATH_ARM_Z = add("breath_arm_z", "wg_breath_arm_z", 0.35D,
            "deg", "T2", "들숨에 어깨가 바깥으로. 바깥 방향 바이어스 (관통 방지)", "arm_right", "arm_left");

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
            "tick", "T2", "미세 흔들림 주기", "body", "head", "hip");
    public static final Param SWAY_BODY_Z = add("sway_body_z", "wg_sway_body_z", 1.2D,
            "deg", "T2", "상체 좌우", "body");
    public static final Param SWAY_HEAD_Z = add("sway_head_z", "wg_sway_head_z", 0.7D,
            "deg", "T2", "머리 반대 보정 (위상 -14°)", "head");
    public static final Param SWAY_HIP_Y = add("sway_hip_y", "wg_sway_hip_y", 0.5D,
            "deg", "T2", "전신 미세 비틀기 (위상 -20°)", "hip");

    // ---- 4.3.5 체중 이동 ---------------------------------------------------------------------
    //
    // The third axis, and the slowest. 44 / 53 / 79 share no common multiple, so the three layers
    // never realign — that non-repetition is the entire point of this layer, not its amplitude.

    public static final Param WEIGHT_SHIFT_PERIOD = add("weight_shift_period", "wg_weight_period", 79.0D,
            "tick", "T2", "체중 이동 주기. 44/53 과 공배수가 없어 위상이 계속 어긋난다", "hip", "head");
    public static final Param WEIGHT_SHIFT_AMP = add("weight_shift_amp", "wg_weight_amp", 0.8D,
            "deg", "T2", "아주 느린 좌우 체중 이동 (hip zRot)", "hip");
    public static final Param WEIGHT_SHIFT_HEAD_Z = add("weight_shift_head_z", "wg_weight_head_z", 0.4D,
            "deg", "T2", "체중 이동에 대한 머리 반대 보정 (위상 -24°)", "head");

    // ---- 4.3.4 기본 자세 오프셋 (정적, C1 과 별개로 상시 가산) --------------------------------------

    public static final Param OFFSET_ARM_R_X = add("offset_arm_right_x", null, 4.0D,
            "deg", "T2", "오른팔을 힘 빼고 살짝 앞으로", "arm_right");
    public static final Param OFFSET_ARM_L_X = add("offset_arm_left_x", null, 4.0D,
            "deg", "T2", "왼팔을 힘 빼고 살짝 앞으로", "arm_left");
    public static final Param OFFSET_LEG_R_Y = add("offset_leg_right_y", null, 2.0D,
            "deg", "T2", "오른발 안짱", "leg_right");
    public static final Param OFFSET_LEG_L_Y = add("offset_leg_left_y", null, -2.0D,
            "deg", "T2", "왼발 안짱", "leg_left");
    public static final Param OFFSET_HEAD_X = add("offset_head_x", null, -3.0D,
            "deg", "T2", "목 앞으로", "head");
    public static final Param OFFSET_BODY_X = add("offset_body_x", null, -1.5D,
            "deg", "T2", "어깨 앞으로", "body");

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
