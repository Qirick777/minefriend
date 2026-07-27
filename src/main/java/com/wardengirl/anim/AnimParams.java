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
        /**
         * Explicit tuning range, or {@code null} to derive one from the default.
         *
         * <p>Derivation (×0.01 … ×10) is fine for an amplitude but meaningless for a value like a
         * pivot coordinate, where 0.29 … 290 is not a range anyone wants to sweep. Shape parameters
         * carry the range the design actually admits.
         */
        public final double[] range;

        private double value;

        Param(String key, String molang, double defaultValue, String unit, String task,
              String description, boolean presetScaled, double[] range, List<String> affects) {
            this.range = range;
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
        Param p = new Param(key, molang, def, unit, task, desc, true, null, List.of(affects));
        REGISTRY.put(key, p);
        return p;
    }

    /** Like {@link #add}, but the preset sweep leaves it alone. See {@link Param#presetScaled}. */
    private static Param addFixed(String key, String molang, double def, String unit, String task,
                                  String desc, String... affects) {
        Param p = new Param(key, molang, def, unit, task, desc, false, null, List.of(affects));
        REGISTRY.put(key, p);
        return p;
    }

    /** Excluded from the preset sweep and carrying an explicit range instead of a derived one. */
    private static Param addShape(String key, double def, double lo, double hi, String unit,
                                  String task, String desc, String... affects) {
        Param p = new Param(key, null, def, unit, task, desc, false, new double[]{lo, hi},
                List.of(affects));
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
    public static final Param HEADGEAR_MAX_ANGLE = addFixed("headgear_max_angle", null, 45.0D,
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

    // ---- 4.5 촉수 형태 (런타임 조정용) ---------------------------------------------------------
    //
    // The base pose used to be baked into the cube's `rotation` in geo.json, which is read once at
    // model load and cannot be moved by a command. It now lives here and is ADDED to the bone under
    // the spring output, so `param set` changes the shape on the next frame with no reload.
    //
    // The spring is unaffected by these: it chases head's rotation and emits a lag error, and that
    // error is computed before the base pose is added. Changing the base cannot destabilise it.

    /** Outward lean, degrees. Right tendril leans toward −X, left toward +X. */
    public static final Param HEADGEAR_SPLAY = addShape("headgear_splay", 5.0D, -90.0D, 90.0D,
            "deg", "T3", "촉수 바깥 각도. 0 = 수직, 90 = 수평. 텍스처가 이미 사선이라 기본은 거의 0",
            "headgear_right", "headgear_left");
    /** Backward lean, degrees. Positive tips the ends toward the mob's back. */
    public static final Param HEADGEAR_TILT = addShape("headgear_tilt", 0.0D, -90.0D, 90.0D,
            "deg", "T3", "촉수 앞뒤 각도. 양수 = 뒤로, 음수 = 앞으로", "headgear_right", "headgear_left");
    /**
     * Roll about the tendril's own long axis (bone yRot), mirrored left/right.
     *
     * <p>The tendril is a flat plane, so this turns it edge-on to the viewer. Worth having because
     * a plane facing straight forward and a plane angled outward read very differently.
     */
    public static final Param HEADGEAR_ROLL = addShape("headgear_roll", 0.0D, -90.0D, 90.0D,
            "deg", "T3", "촉수 비틀기 (자기 축 회전). 평면이 정면을 보는 정도", "headgear_right", "headgear_left");

    // Position is an OFFSET from the geo pivot (∓4, 30, 0), not an absolute coordinate — x is
    // mirrored so one number moves both sides symmetrically. The geo pivot itself is not
    // reachable at runtime; translating the bone is exactly equivalent (T(pos)·T(pivot)·R·T(-pivot)
    // puts a root vertex on the pivot at pivot + pos whatever the rotation), and unlike the pivot
    // it is settable from a command.
    public static final Param HEADGEAR_POS_X = addShape("headgear_pos_x", 0.0D, -8.0D, 8.0D,
            "px", "T3", "촉수 좌우 이동. 기준 피벗 x=∓4 에서의 오프셋. 부호는 좌우 자동 대칭",
            "headgear_right", "headgear_left");
    public static final Param HEADGEAR_POS_Y = addShape("headgear_pos_y", 0.0D, -8.0D, 8.0D,
            "px", "T3", "촉수 상하 이동. 기준 피벗 y=30 에서의 오프셋 (머리 큐브는 y24~32)",
            "headgear_right", "headgear_left");
    public static final Param HEADGEAR_POS_Z = addShape("headgear_pos_z", 0.0D, -8.0D, 8.0D,
            "px", "T3", "촉수 앞뒤 이동. 양수 = 앞(+Z)", "headgear_right", "headgear_left");

    /**
     * Uniform scale on both tendrils, about their pivot.
     *
     * <p>Applied with {@code GeoBone.setScaleX/Y/Z}. {@code RenderUtils.scaleMatrixForBone} runs
     * between {@code translateToPivotPoint} and {@code translateAwayFromPivotPoint}, so the scale
     * is centred on the <b>pivot</b> — the root stays welded to the side of the head however small
     * the tendril gets. UVs are untouched: the same picture is drawn on a smaller quad, not a
     * smaller crop of the picture.
     */
    public static final Param HEADGEAR_SCALE = addShape("headgear_scale", 0.6D, 0.3D, 2.0D,
            "배율", "T3", "촉수 크기. 피벗 기준이라 줄여도 뿌리는 머리 옆에 붙어 있다 (T3 확정 0.6)",
            "headgear_right", "headgear_left");

    /**
     * Debug isolation: 1 hides every outer-layer cube (hat, jacket, sleeves, pants).
     *
     * <p>Exists because with the base and the overlay drawn on top of each other, a capture cannot
     * say which of the two is wrong.
     */
    public static final Param OVERLAY_HIDDEN = addShape("overlay_hidden", 0.0D, 0.0D, 1.0D,
            "0/1", "T3", "진단용. 1 이면 바깥 레이어(모자·재킷·소매·바지)를 전부 숨긴다",
            "head", "body", "arm_right", "arm_left", "leg_right", "leg_left");

    // ---- 4.6 시선 추적 (T3: 가산과 클램프. T4: 감쇠 보간 · 근거리 반응) ---------------------------

    /**
     * Overall gain on the vanilla look direction.
     *
     * <p>1.0 means "the head bone matches the direction the entity is actually looking". Anything
     * else desynchronises the rendered gaze from the AI's gaze, so this is excluded from the preset
     * sweep — see {@link Param#presetScaled}. It exists as a knob for T4's damping work.
     */
    public static final Param LOOK_GAIN = addFixed("look_gain", null, 1.0D,
            "배율", "T3", "바닐라 시선을 head 본에 싣는 배율. 1.0 = 엔티티가 실제로 보는 방향 그대로", "head");
    public static final Param LOOK_YAW_MAX = addFixed("look_yaw_max", null, 75.0D,
            "deg", "T3",
            "head yRot 클램프 (4.6). 바닐라 getMaxHeadYRot() 과 같은 값이라 기준이 명확하다. "
                    + "70 이면 실측 6.0%, 75 면 4.0% 의 시간 동안 걸린다 (70/75/90 비교는 param set 으로)",
            "head");
    public static final Param LOOK_PITCH_MAX = addFixed("look_pitch_max", null, 35.0D,
            "deg", "T3", "head xRot 클램프 (4.6)", "head");

    /**
     * 4.6 감쇠 계수 — {@code current += (target - current) * LOOK_DAMPING} per tick.
     *
     * <p><b>1.0 은 우회 스위치다.</b> 필터를 끄고 {@code current = target} 으로 만들어 T3 상태를
     * 그대로 재현한다 — {@link com.wardengirl.client.LookDamper} 참조. 그래야 감쇠가 있는 화면과
     * 없는 화면을 같은 세션에서 번갈아 보며 판정할 수 있다.
     *
     * <p>범위를 0..1 로 명시한다. 파생 범위(×0.01..×10)였다면 0.0013..1.3 이 되어 우회 스위치가
     * TOML 로는 표현되지만 발산 구간(>1)도 같이 열린다. 음수는 목표에서 <em>멀어지는</em> 방향이라
     * 의미가 없고, 1 초과는 매 틱 지나쳐 진동한다.
     */
    public static final Param LOOK_DAMPING = addShape("look_damping", 0.5D, 0.0D, 1.0D,
            "계수/틱", "T4",
            "시선 감쇠. current += (target-current)*이 값. 1.0 이면 감쇠 없음(T3 동작). "
                    + "T4 확정 0.5 — 지연 1.69틱으로 스냅은 사라지되 굼뜨지 않고, "
                    + "head yRot 이 ±75 를 온전히 채우며, 촉수 오버슈트가 T3 대비 12%만 준다", "head");

    /**
     * 근거리에서 쓰는 감쇠 계수. 작을수록 더 느리고 부드럽게 따라온다.
     *
     * <p>{@link #LOOK_DAMPING} 과 같은 이유로 범위를 0..1 로 명시한다. 파생 범위였다면 상한이
     * 0.6 이라 우회 스위치(1.0)를 TOML 로 쓸 수 없었다.
     */
    public static final Param LOOK_DAMPING_NEAR = addShape("look_damping_near", 0.06D, 0.0D, 1.0D,
            "계수/틱", "T4",
            "근거리 감쇠. NEAR_DISTANCE 안에서 거리에 비례해 look_damping 과 이 값 사이를 잇는다",
            "head");

    /**
     * 근거리 판정 거리, 블록. 클라이언트 로컬 플레이어와 엔티티 사이의 3D 거리로 잰다.
     *
     * <p>이 거리 밖이면 {@link #LOOK_DAMPING}, 안이면 거리에 <b>선형 보간</b>한 값을 쓴다.
     * 계단식 전환을 쓰면 이 경계를 걸어서 넘는 순간 목 각속도가 배로 뛴다.
     */
    public static final Param LOOK_NEAR_DISTANCE = addShape("look_near_distance", 3.0D, 0.5D, 16.0D,
            "블록", "T4", "근거리 판정 거리. 이 안에서 look_damping -> look_damping_near 로 선형 보간",
            "head");

    // ---- 4.4.3 방향 전환 기울임 (T5) -------------------------------------------------------

    /**
     * Scale on the 4.4.3 hip bank. 1.0 = the doc's ±3°.
     *
     * <p>The 3° itself is a Part 4 fixed value and is not exposed; this is the on/off-and-magnitude
     * knob the human needs to judge it, in the same way {@code headgear_scale} sits over a fixed
     * shape. Setting it to 0 disables the bank entirely without touching the spec number.
     */
    public static final Param TURN_LEAN_SCALE = addShape("turn_lean_scale", 1.0D, 0.0D, 3.0D,
            "배율", "T5", "4.4.3 방향 전환 hip 기울임 배율. 1.0 = 사양의 ±3도, 0 = 끔", "hip");

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
