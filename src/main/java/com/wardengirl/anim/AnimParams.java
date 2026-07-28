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

    // ---- C1 공급 경로 (T5 이관 검증용) ----------------------------------------------------------

    /**
     * 0 = json Molang (이관 전), 1 = Java 가산 (이관 후, 기본).
     *
     * <p>이관이 "부호도 크기도 바뀌지 않았다" 를 주장하려면 두 경로의 본 값을 대조해야 하고,
     * 빌드를 두 번 하면 틱 기준이 달라져 같은 시점을 비교할 수 없다. 스위치를 두면 한 빌드
     * 안에서 두 경로를 몇 초 간격으로 재는 것이 가능해진다 — Part 6.2 "원하는 상황을 즉시
     * 만들어라".
     *
     * <p>0 으로 두면 T5 에서 실측한 결함(C2 가 C1 을 덮어 7축 진동 폭이 0.000)이 그대로
     * 재현된다. 그것이 이 값이 0 일 때의 의미이고, 기본값이 1 인 이유다.
     */
    public static final Param C1_SOURCE = addShape("c1_source", 1.0D, 0.0D, 1.0D,
            "flag", "T5", "C1 공급 경로. 0 = json Molang(이관 전), 1 = Java 가산(이관 후)",
            "root", "body", "head", "arm_right", "arm_left");

    /**
     * 걷기 애니메이션 강제 재생. 0 = 평소대로, 1 = 이동 여부와 무관하게 walk 클립 재생.
     *
     * <p><b>검증 자극이다. 이동을 만들지 않는다 — 애니메이션 상태만 만든다.</b>
     *
     * <p>필요한 이유: `WaterAvoidingRandomStrollGoal` 은 재현되지 않는다. 3라운드에서 같은 하네스로
     * 연속 세 창을 재는데 1·2번 창은 걷기 프레임 451 / 433 이 나오고 3번 창은 0 이 나왔다. 몹을
     * 매 창 시작 시 플레이어 11블록 앞으로 텔레포트해도 그랬다. 배회 Goal 은 `noActionTime`,
     * 난수 간격(120틱), 경로 탐색 성공 여부에 동시에 걸려 있어 **원하는 상황을 즉시 만들 수 없다**
     * (Part 6.2 위반).
     *
     * <p><b>이 값으로 잰 창에서 판정할 수 없는 것:</b> 발 미끄러짐, 보폭 대 이동속도, 접지. 전부
     * 실제 이동이 있어야 성립한다. 레이어 합성처럼 "C2 클립이 재생 중인가" 만 필요한 측정에 쓴다.
     */
    public static final Param WALK_FORCE = addShape("walk_force", 0.0D, 0.0D, 1.0D,
            "flag", "T5", "걷기 애니메이션 강제 재생 (검증용. 실제 이동은 만들지 않는다)",
            "arm_right", "arm_left", "leg_right", "leg_left", "body", "head", "root");

    /**
     * 0 = GeckoLib 컨트롤러 (이관 전), 1 = Java 직접 평가 (이관 후, 기본).
     *
     * <p>{@code c1_source} 와 같은 이유로 존재한다. 1차차분은 <b>프레임당</b> 값이라 프레임률이
     * 바뀌면 같은 동작도 다른 수치가 된다 — 이 소프트웨어 렌더러의 프레임/틱은 실행마다 1.9 에서
     * 3.8 사이를 오간다. 빌드를 두 번 해서 비교하면 그 차이가 코드 변경과 뒤섞인다.
     *
     * <p>한 빌드 안에서 몇 초 간격으로 두 경로를 재면 프레임률이 사실상 같으므로 비교가 성립한다.
     * Part 6.2 — 원하는 상황을 즉시 만들 수 있어야 한다.
     */
    public static final Param C2_SOURCE = addShape("c2_source", 1.0D, 0.0D, 1.0D,
            "flag", "T5", "C2 공급 경로. 0 = 컨트롤러(이관 전), 1 = Java 직접 평가(이관 후)",
            "root", "body", "head", "arm_right", "arm_left", "leg_right", "leg_left");

    // ---- 4.4.2 거리 기반 걷기 (문제 3, 2라운드) --------------------------------------------------
    //
    // 걷기 사이클을 시간이 아니라 이동 거리에서 뽑는다. 바닐라 HumanoidModel 과 같은 구조다:
    // 위상은 limbSwing(누적 이동), 진폭은 limbSwingAmount(평활된 속도).
    //
    // 자기 일관성이 핵심이다. 진폭이 정하는 보폭만큼 이동했을 때 한 사이클이 끝나도록 위상을
    // 전진시킨다. 그래서 진폭 배율을 어떻게 잡든 발 미끄러짐 비율은 1.0 으로 유지된다 —
    // 배율은 실루엣을 정하지 접지를 깨지 않는다.

    /** 거리 대비 주기 배율. 1.0 = 계산된 무미끄러짐 주기. 크게 하면 보폭이 길어지고 발이 끌린다. */
    public static final Param WALK_CYCLE_SCALE = addShape("walk_cycle_scale", 1.0D, 0.25D, 4.0D,
            "ratio", "T5", "걷기 주기 배율. 1.0 이 발이 미끄러지지 않는 계산값이다",
            "leg_right", "leg_left", "arm_right", "arm_left", "body", "head", "root");

    /**
     * 다리 진폭 배율. 4.4.2 의 ±18° 에 곱한다.
     *
     * <p>기준값 ±18° 는 그대로 둔다. 바닐라 {@code HumanoidModel} 의 다리 계수는 1.4 rad = 80.2°
     * 이고 우리 ±18° 는 그 4.5분의 1이라, <b>배율 4.5 가 바닐라와 같아지는 지점</b>이다.
     * 실루엣 판정은 사람이 한다.
     */
    public static final Param WALK_LEG_AMP_SCALE = addShape("walk_leg_amp_scale", 4.0D, 0.25D, 4.0D,
            "ratio", "T5", "걷기 다리 진폭 배율. 확정 4.0 = 유효 ±72°, 바닐라의 90%",
            "leg_right", "leg_left");
    // ---- T6 반응형 idle. 폐기 가능. 0 이면 발동 안 함 -----------------------------------------

    /** 4.12 킁킁 발동 거리. **0 이면 이 동작 전체가 꺼진다.** */
    public static final Param SNIFF_DISTANCE = addShape("sniff_distance", 2.0D, 0.0D, 6.0D,
            "block", "T6", "킁킁 발동 거리. 0 = 끔", "body", "head", "arm_right", "arm_left", "root");
    /** 그 거리 안에 머물러야 하는 시간. */
    public static final Param SNIFF_DWELL = addShape("sniff_dwell", 40.0D, 0.0D, 200.0D,
            "tick", "T6", "킁킁 발동까지 근접 유지 시간", "body", "head", "arm_right", "arm_left");
    // ---- 4.13 탑승 앉기 (C2 세 번째 상태) -----------------------------------------------------

    /**
     * 넓적다리를 접는 각. 본 공간. <b>+ 가 앞이다 — 화면 실측으로 확정했다.</b>
     *
     * <p>−90 으로 두었더니 다리가 뒤로 꺾였다. 4.0.2 의 {@code arm / leg xRot: + = 앞으로} 가
     * 옳았고 내가 부호를 뒤집어 넣은 것이었다.
     */
    public static final Param SIT_LEG_X = addShape("sit_leg_x", 90.0D, 0.0D, 120.0D,
            "deg", "T6", "탑승 앉기 다리 xRot. + 가 앞이다 (실측)", "leg_right", "leg_left");
    /** 다리를 좌우로 벌리는 각. 오른다리 +, 왼다리 −. */
    public static final Param SIT_LEG_Y = addShape("sit_leg_y", 10.0D, 0.0D, 30.0D,
            "deg", "T6", "탑승 앉기 다리 yRot (오른쪽 +, 왼쪽 −)", "leg_right", "leg_left");
    /**
     * 팔 xRot. 바닐라 riding 의 −36° 는 두 손으로 노를 잡는 자세라 쓰지 않는다.
     *
     * <p>−20 은 사람이 준 범위 −15 ~ −25 의 가운데다. 넓적다리가 −90 으로 수평이므로 어깨에서
     * 무릎 위까지의 각이 대략 그 부근이고, −15 면 팔이 허공에 뜨고 −25 면 손이 무릎을 지나
     * 정강이로 내려간다.
     */
    public static final Param SIT_ARM_X = addShape("sit_arm_x", -20.0D, -60.0D, 0.0D,
            "deg", "T6", "탑승 앉기 팔 xRot", "arm_right", "arm_left");
    /**
     * 팔 zRot. 오른팔 +, 왼팔 −(둘 다 바깥).
     *
     * <p>0 이 아니라 +2 인 이유는 4.11 에서 값으로 확인한 기하다 — 팔 안쪽면과 몸통 바깥면이
     * 둘 다 {@code x = ±4} 로 맞닿아 간격이 0px 이므로 <b>안쪽으로는 각도와 무관하게 즉시
     * 관통한다.</b> 0 은 그 경계 위이고, 살짝 바깥이 안전하다.
     */
    public static final Param SIT_ARM_Z = addShape("sit_arm_z", 2.0D, -10.0D, 10.0D,
            "deg", "T6", "탑승 앉기 팔 zRot (오른쪽 +, 왼쪽 −)", "arm_right", "arm_left");
    /** 탑승 중에만 킁킁 진폭에 곱한다. 0 이면 탑승 중 킁킁 없음. */
    public static final Param SNIFF_RIDING_SCALE =
            addShape("sniff_riding_scale", 1.0D, 0.0D, 1.0D,
                    "ratio", "T6", "탑승 중 킁킁 진폭 배율", "body", "head", "arm_right", "arm_left");

    /** 확률 판정 주기. dwell 을 채운 뒤 이 주기마다 한 번씩 굴린다. */
    public static final Param SNIFF_ROLL_INTERVAL =
            addShape("sniff_roll_interval", 20.0D, 1.0D, 200.0D,
                    "tick", "T6", "킁킁 확률 판정 주기", "body", "head", "arm_right", "arm_left");
    /** 한 번의 판정이 성공할 확률. */
    public static final Param SNIFF_ROLL_CHANCE =
            addShape("sniff_roll_chance", 0.25D, 0.0D, 1.0D,
                    "ratio", "T6", "킁킁 확률 판정 성공 확률", "body", "head", "arm_right", "arm_left");
    /** 정면 기준 이 각도 안에 플레이어가 있어야 발동한다. 180 이면 제한 없음. */
    public static final Param SNIFF_FRONT_ANGLE =
            addShape("sniff_front_angle", 60.0D, 0.0D, 180.0D,
                    "deg", "T6", "킁킁 발동 정면 각도. 180 = 제한 없음",
                    "body", "head", "arm_right", "arm_left");
    /** 공격·피격이 오면 이 시간에 걸쳐 킁킁을 끊는다. */
    public static final Param SNIFF_INTERRUPT_FADE =
            addShape("sniff_interrupt_fade", 3.0D, 0.0D, 10.0D,
                    "tick", "T6", "킁킁 중단 페이드 아웃", "body", "head", "arm_right", "arm_left");
    public static final Param SNIFF_SOUND_VOLUME =
            addShape("sniff_sound_volume", 0.6D, 0.0D, 2.0D, "ratio", "T6", "킁킁 소리 볼륨");
    /** 2차 발화 볼륨 배율. 1차 대비. 두 번째 덩어리가 약해야 감쇠가 읽힌다. */
    public static final Param SNIFF_SOUND_VOLUME_2ND =
            addShape("sniff_sound_volume_2nd", 0.8D, 0.0D, 1.0D, "ratio", "T6",
                    "킁킁 2차 발화 볼륨 배율 (1차 대비)");
    public static final Param SNIFF_SOUND_PITCH =
            addShape("sniff_sound_pitch", 1.0D, 0.5D, 2.0D, "ratio", "T6", "킁킁 소리 피치");
    /** 재발동 금지 시간. */
    public static final Param SNIFF_COOLDOWN = addShape("sniff_cooldown", 200.0D, 0.0D, 600.0D,
            "tick", "T6", "킁킁 재발동 쿨다운", "body", "head", "arm_right", "arm_left");

    /** 4.11 피격 움찔 페이드 인. 기본 0 — 충격은 즉발이어야 한다. */
    public static final Param HURT_FADE_IN = addShape("hurt_fade_in", 0.0D, 0.0D, 5.0D,
            "tick", "T5", "피격 움찔 페이드 인. 0 = 즉발", "body", "head", "arm_right", "arm_left");
    /** 4.11 피격 움찔 페이드 아웃. */
    public static final Param HURT_FADE_OUT = addShape("hurt_fade_out", 3.0D, 0.0D, 10.0D,
            "tick", "T5", "피격 움찔 페이드 아웃", "body", "head", "arm_right", "arm_left");
    /**
     * 4.11 피격 팔 zRot 배율.
     *
     * <p>팔 피벗은 {@code (±5, 21.5, 0)} 이고 몸통 반폭은 4px 이라, 팔 안쪽면과 몸통 바깥면이
     * 둘 다 {@code x = ±4} 로 <b>맞닿아 있다</b>. 간격이 0px 이므로 안쪽 zRot 은 각도와 무관하게
     * 즉시 관통한다 — T2 에서 확인한 것과 같은 기하다. 그래서 4.11 의 팔은 바깥으로 간다.
     */
    public static final Param HURT_ARM_Z_SCALE = addShape("hurt_arm_z_scale", 1.0D, 0.0D, 3.0D,
            "ratio", "T5", "피격 팔 zRot 배율", "arm_right", "arm_left");
    /** 4.11 피격 움찔 진폭 배율. 사람이 화면으로 크기를 정한다. */
    public static final Param HURT_AMP_SCALE = addShape("hurt_amp_scale", 1.0D, 0.0D, 3.0D,
            "ratio", "T5", "피격 움찔 진폭 배율", "body", "head", "arm_right", "arm_left", "root");
    /** 피격 중 걷기 body.xRot 을 얼마나 남길지. 두 동작이 겹치는 유일한 축이다. */
    public static final Param BLEND_WALK_BODY_X_HURT =
            addShape("blend_walk_body_x_hurt", 1.0D, 0.0D, 1.0D,
                    "ratio", "T5", "피격 중 걷기 body.xRot 유지 비율", "body");

    /**
     * 팔 진폭 배율. 확정 3.15 = 유효 ±31.5°, 바닐라 팔(57.296°)의 55%.
     *
     * <p>다리와 같은 배율이 아니다. 사람이 화면에서 골랐다 — 다리는 성큼, 팔은 얌전한 쪽이다.
     */
    public static final Param WALK_ARM_AMP_SCALE = addShape("walk_arm_amp_scale", 3.15D, 0.25D, 4.0D,
            "ratio", "T5", "걷기 팔 진폭 배율. 확정 3.15 = 유효 ±31.5°, 바닐라의 55%",
            "arm_right", "arm_left");

    /**
     * {@code walk_force} 가 위상에 먹이는 합성 속도, 블록/틱. 0 이면 위상이 멈춘다.
     *
     * <p>기본값은 실측 순항 속도다. {@code walk_force} 는 실제 이동을 만들지 않으므로 거리 기반
     * 위상이 그대로 멈춰 버린다 — 지금까지 그 값으로 잰 전이·합성 창이 전부 무의미해진다.
     * 합성 속도를 공급하면 느린 걷기·빠른 걷기도 재현할 수 있어 검증 도구로도 낫다.
     */
    public static final Param WALK_FORCE_SPEED = addShape("walk_force_speed", 0.11428D, 0.0D, 0.5D,
            "블록/틱", "T5", "walk_force 가 위상에 먹이는 합성 속도. 0 이면 위상 정지",
            "leg_right", "leg_left", "arm_right", "arm_left");

    // ---- C3 직접 평가 (T5 2라운드) --------------------------------------------------------------
    //
    // C3 는 컨트롤러가 아니라 setCustomAnimations 에서 직접 평가해 가산한다. 컨트롤러였다면 C2 가
    // 덮거나 C2 를 덮었을 것이고, 걷기 + 기본 공격 동시 재생이 성립하지 않는다 (Part 4.2).
    // 그러면 전이도 라이브러리가 해 주지 않으므로 여기서 만든다.

    /**
     * 시작 페이드, 틱. 이 구간에서 C3 기여가 0 에서 1 배로 올라간다.
     *
     * <p>C2 의 6틱보다 짧다. C2 전이는 두 <em>자세</em> 사이를 건너는 것이라 시간이 필요하지만,
     * C3 는 이미 서 있는 자세 위에 <em>얹히는</em> 값이라 0 에서 시작한다. 공격은 총 18틱이고
     * (4.8), 그중 6틱을 페이드에 쓰면 예비동작이 뭉개진다.
     */
    public static final Param ATTACK_FADE_IN = addShape("attack_fade_in", 3.0D, 0.0D, 10.0D,
            "tick", "T5", "C3 시작 페이드 길이. 0 이면 즉시 전량",
            "body", "head", "arm_right", "arm_left");
    /** 종료 페이드, 틱. 시작보다 긴 것은 4.1 원칙 5(오버슈트와 정착)의 '정착' 쪽이다. */
    public static final Param ATTACK_FADE_OUT = addShape("attack_fade_out", 5.0D, 0.0D, 10.0D,
            "tick", "T5", "C3 종료 페이드 길이. 0 이면 즉시 0",
            "body", "head", "arm_right", "arm_left");

    // C2 와 C3 가 겹치는 축에서 걷기 기여를 얼마나 남길지. 1.0 = 단순 가산 (그대로 둔다).
    //
    // 겹치는 축은 4.4.2 와 4.8 을 대조해 정한 것이다: 팔 xRot, body yRot, head yRot. 세 축 다
    // 두 동작이 같은 방향으로 크게 움직이므로, 단순 가산이 과할 수 있다 — 걷기 팔 ±10 에 공격
    // 팔 +70 이 그대로 더해지면 80 이다. 줄일지 말지는 화면으로 판정한다.
    //
    // 축마다 따로 두는 이유: 팔은 과해 보이는데 몸통 비틀기는 오히려 부족할 수 있다. 하나로
    // 묶으면 그 상태를 표현할 수 없다.

    public static final Param BLEND_WALK_ARM_X = addShape("blend_walk_arm_x", 1.0D, 0.0D, 1.0D,
            "ratio", "T5", "공격 중 걷기 팔 xRot 기여 비율. 1 = 그대로 가산, 0 = 공격이 팔을 독점",
            "arm_right", "arm_left");
    public static final Param BLEND_WALK_BODY_Y = addShape("blend_walk_body_y", 1.0D, 0.0D, 1.0D,
            "ratio", "T5", "공격 중 걷기 body yRot 기여 비율", "body");
    public static final Param BLEND_WALK_HEAD_Y = addShape("blend_walk_head_y", 1.0D, 0.0D, 1.0D,
            "ratio", "T5", "공격 중 걷기 head yRot 기여 비율", "head");

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
    public static final Param BREATH_HEAD_X = add("breath_head_x", "wg_breath_head_x", -0.25D,
            "deg", "T2", "머리 수평 유지 보정 (2.0틱 지연 = 60틱에서 -12°. 구판 44틱 -16° 환산)", "head");
    /**
     * 4.3.1 팔 zRot, <b>좌우 분리</b>. 4.0.2 "대칭 동작은 좌우를 따로 적는다" 를 파라미터에도 적용.
     *
     * <p>하나로 두면 어느 한쪽 json 식에 반드시 마이너스가 들어가고, 그것이 Part 3.6 의
     * "json 식에서 부호를 뒤집지 않는다" 를 깬다. 쪼개는 비용은 파라미터 하나가 둘이 되는
     * 것뿐이고, 좌우를 따로 조정할 수 있게 되므로 튜닝 자유도는 오히려 는다.
     */
    public static final Param BREATH_ARM_R_Z = add("breath_arm_r_z", "wg_breath_arm_r_z", 0.35D,
            "deg", "T2", "들숨에 오른어깨가 바깥으로. 바깥 방향 바이어스 (관통 방지)", "arm_right");
    public static final Param BREATH_ARM_L_Z = add("breath_arm_l_z", "wg_breath_arm_l_z", -0.35D,
            "deg", "T2", "들숨에 왼어깨가 바깥으로. 좌우 zRot 부호가 반대다 (4.0.2)", "arm_left");

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
    public static final Param SWAY_HEAD_Z = add("sway_head_z", "wg_sway_head_z", -0.7D,
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

    // ---- 4.4.2 걷기 (T5) --------------------------------------------------------------------

    /**
     * 4.4.2 상시 전방 기울기. {@code body.xRot}, 걷는 동안만.
     *
     * <p>Doc value 4.0. Exposed because "종종거린다" and "구부정하다" are the same number seen from
     * two sides and only a person looking at it can say which it is. 0 stands the torso upright
     * without touching the spec number anywhere else.
     *
     * <p><b>Negative leans forward</b> — 4.4.2 writes the forward lean as {@code body.xRot -4},
     * and Part 3.6 requires a parameter's default to carry the design doc's sign unchanged. The
     * first version of this defaulted to {@code +4.0} and put a bare {@code query.wg_walk_body_lean}
     * where the json had held {@code -4}; the minus was simply lost and the torso leaned back.
     * The range is symmetric so that overshooting either way is reachable while sweeping.
     */
    public static final Param WALK_BODY_LEAN = addShape("walk_body_lean", -4.0D, -10.0D, 10.0D,
            "deg", "T5",
            "걷기 상시 상체 기울기 (4.4.2 body.xRot -4). 음수가 앞으로 숙임. 0 이면 수직",
            "body");

    /**
     * 걷기 종료 유예, 틱. 임계 미만이 이만큼 <em>연속</em>되어야 걷기를 끝낸다.
     *
     * <p>시작은 즉시, 종료는 느리게 — 비대칭이 의도다. 걷기 시작이 늦으면 몸이 먼저 나가고
     * 다리가 따라붙어 미끄러지는 것처럼 보인다. 종료가 늦는 것은 몇 틱 더 걷는 것이라 덜
     * 눈에 띈다.
     *
     * <p>기본 6 은 {@code transitionLength} 와 같은 값이다. 시작점일 뿐 근거는 아니다.
     */
    public static final Param WALK_STOP_GRACE = addShape("walk_stop_grace", 6.0D, 0.0D, 20.0D,
            "tick", "T5", "걷기 종료 유예. 임계 미만이 이만큼 연속되어야 정지로 본다", "root");

    /**
     * 걷기로 판정하는 평균 수평 속도 임계.
     *
     * <p>GeckoLib 의 {@code getMotionAnimThreshold()} 기본값과 같은 0.015 로 시작한다. 같은
     * 양을 재지만 판정은 우리가 한다 — {@code AnimationState.isMoving()} 은
     * {@code avgVelocity >= threshold && limbSwingAmount != 0} 을 <b>이력 없이</b> 매 프레임
     * 새로 계산하므로, 경로 노드 사이 감속에서 한 틱만 내려가도 즉시 false 가 된다.
     */
    public static final Param WALK_MOVE_THRESHOLD = addShape("walk_move_threshold", 0.015D,
            0.001D, 0.1D, "블록/틱", "T5", "걷기 판정 평균 수평 속도 임계 ((|vx|+|vz|)/2)", "root");

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
