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

    // ---- 4.3.1 호흡 (주기 30tick) -------------------------------------------------------------

    public static final Param BREATH_SPEED = add("breath_speed", "wg_breath_speed", 12.0D,
            "deg/tick", "T2", "호흡 위상 속도. 12 = 30틱 주기 (360/30)", "body", "head", "arm_right", "arm_left", "leg_right", "leg_left");
    public static final Param BREATH_BODY_X = add("breath_body_x", "wg_breath_body_x", 0.7D,
            "deg", "T2", "들숨에 상체를 뒤로 젖히는 진폭", "body");
    public static final Param BREATH_BODY_Y = add("breath_body_y", "wg_breath_body_y", 0.15D,
            "px", "T2", "흉곽 상승", "body");
    public static final Param BREATH_HEAD_X = add("breath_head_x", "wg_breath_head_x", 0.35D,
            "deg", "T2", "머리 수평 유지 보정 (2틱 지연)", "head");
    public static final Param BREATH_ARM_Z = add("breath_arm_z", "wg_breath_arm_z", 0.5D,
            "deg", "T2", "들숨에 양팔이 바깥으로 (좌우 반대 부호)", "arm_right", "arm_left");
    public static final Param BREATH_LEG_X = add("breath_leg_x", "wg_breath_leg_x", 0.2D,
            "deg", "T2", "체중 후방 이동", "leg_right", "leg_left");

    // ---- 4.3.2 바운스 (주기 25tick) ------------------------------------------------------------

    public static final Param BOUNCE_SPEED = add("bounce_speed", "wg_bounce_speed", 7.2D,
            "deg/tick", "T2", "바운스 위상 속도. 7.2 = 25틱 주기이나 abs() 때문에 실질 12.5틱", "root", "leg_right", "leg_left");
    /** Design doc Part 4.12: tuning priority #1. Slim model may make this read as too much. */
    public static final Param BOUNCE_AMPLITUDE = add("bounce_amplitude", "wg_bounce_amp", 0.4D,
            "px", "T2", "귀여움의 핵심. 튜닝 1순위. 0.2~1.0 을 먼저 훑을 것", "root");
    public static final Param BOUNCE_LEG_Z = add("bounce_leg_z", "wg_bounce_leg_z", 0.4D,
            "deg", "T2", "접지 순간 다리가 미세하게 벌어짐 (무릎 없음의 유일한 대체)", "leg_right", "leg_left");

    // ---- 4.3.3 미세 흔들림 (주기 53tick) --------------------------------------------------------

    public static final Param SWAY_SPEED = add("sway_speed", "wg_sway_speed", 6.8D,
            "deg/tick", "T2", "미세 흔들림 위상 속도. 호흡·바운스와 서로소에 가까워 반복 감지가 안 된다", "body", "head", "hip");
    public static final Param SWAY_BODY_Z = add("sway_body_z", "wg_sway_body_z", 0.8D,
            "deg", "T2", "상체 좌우", "body");
    public static final Param SWAY_HEAD_Z = add("sway_head_z", "wg_sway_head_z", 0.5D,
            "deg", "T2", "머리 반대 보정", "head");
    public static final Param SWAY_HIP_Y = add("sway_hip_y", "wg_sway_hip_y", 0.3D,
            "deg", "T2", "전신 미세 비틀기 (구판 leg yRot 오류의 대체 — 감사 후보 B-5)", "hip");

    // ---- 4.3.4 기본 자세 오프셋 (정적, C1 과 별개로 상시 가산) --------------------------------------

    public static final Param OFFSET_ARM_R_Z = add("offset_arm_right_z", null, -4.0D,
            "deg", "T2", "오른팔 안쪽으로 모음", "arm_right");
    public static final Param OFFSET_ARM_L_Z = add("offset_arm_left_z", null, 4.0D,
            "deg", "T2", "왼팔 안쪽으로 모음", "arm_left");
    public static final Param OFFSET_LEG_R_Y = add("offset_leg_right_y", null, 2.0D,
            "deg", "T2", "오른발 안짱", "leg_right");
    public static final Param OFFSET_LEG_L_Y = add("offset_leg_left_y", null, -2.0D,
            "deg", "T2", "왼발 안짱", "leg_left");
    public static final Param OFFSET_HEAD_X = add("offset_head_x", null, -3.0D,
            "deg", "T2", "목 앞으로", "head");
    public static final Param OFFSET_BODY_X = add("offset_body_x", null, -1.5D,
            "deg", "T2", "어깨 앞으로", "body");

    // ---- 이후 태스크에서 사용. 값만 보유한다 (Part 3.5 "전 파라미터 단일 파일") ---------------------
    // 이 값들을 읽는 코드는 아직 없다. T3/T4 에서 붙는다.

    public static final Param HEADGEAR_STIFFNESS = add("headgear_stiffness", null, 0.25D,
            "-", "T3", "4.5.1 스프링 강성. 낮으면 늦게 따라온다", "headgear");
    public static final Param HEADGEAR_DAMPING = add("headgear_damping", null, 0.35D,
            "-", "T3", "4.5.1 감쇠. 낮으면 오래 흔들린다", "headgear");
    public static final Param HEADGEAR_AMPLITUDE = add("headgear_amplitude", null, 1.3D,
            "-", "T3", "4.5.1 최종 배율 (오버슈트)", "headgear");
    public static final Param HEADGEAR_MAX_ANGLE = add("headgear_max_angle", null, 35.0D,
            "deg", "T3", "4.5.1 안전 클램프", "headgear");
    public static final Param LOOK_DAMPING = add("look_damping", null, 0.13D,
            "-", "T4", "4.6 시선 감쇠", "head");
    public static final Param LOOK_DAMPING_NEAR = add("look_damping_near", null, 0.06D,
            "-", "T4", "4.6 근거리 감쇠 (느긋하게)", "head");
    public static final Param LOOK_NEAR_DISTANCE = add("look_near_distance", null, 3.0D,
            "block", "T4", "4.6 근거리 판정", "head");
    public static final Param LOOK_YAW_MAX = add("look_yaw_max", null, 70.0D,
            "deg", "T4", "4.6 목 좌우 한계", "head");
    public static final Param LOOK_PITCH_MAX = add("look_pitch_max", null, 35.0D,
            "deg", "T4", "4.6 목 상하 한계", "head");

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
