package com.wardengirl.anim;

/**
 * 4.6 시선 감쇠의 <b>서버 안전 순수 계산기</b>. 4.14 서버 권위 shadow 가 쓴다.
 *
 * <h2>왜 {@code client.LookDamper} 를 안 쓰는가</h2>
 *
 * 그 클래스는 {@code com.wardengirl.client} 에 있다. 로직 자체는 이미 순수하지만
 * (import 가 {@link AnimParams} 하나뿐이다) <b>서버 코드가 클라이언트 패키지를 참조하면 안 된다</b>
 * — 전용 서버에는 클라이언트 클래스가 로드되지 않을 수 있고, 그 결합은 나중에 반드시 문제가 된다.
 * 그래서 계산부만 공용 {@code anim} 패키지로 옮겼다.
 *
 * <h2>{@code LookDamper} 와 같은 것</h2>
 *
 * <ul>
 *   <li>계수 식 {@code near + (far − near) × min(1, distance/span)} 과 그 clamp — 동일</li>
 *   <li>recurrence {@code current += (target − current) × damping} — 동일</li>
 *   <li>{@code damping ≥ 1} 우회, 첫 관측 시 목표에 정착시켜 시작 — 동일</li>
 * </ul>
 *
 * <h2>{@code LookDamper} 와 <b>다른</b> 것 — 의도적이다</h2>
 *
 * <ul>
 *   <li><b>{@code partialTick} 보간 출력이 없다.</b> {@code LookDamper.output(axis, partialTick, …)}
 *       는 렌더 프레임용이다. 서버는 틱 최종값만 만들면 되고, 프레임 개념 자체가 없다.</li>
 *   <li><b>{@code tickCount} 를 받아 밀린 틱을 몰아 돌리는 보정이 없다.</b> {@code LookDamper} 는
 *       프레임마다 호출되므로 "이번 프레임이 몇 틱째인가"를 스스로 따져야 했다. 이 계산기는
 *       <b>서버 틱당 정확히 한 번</b> 불리는 것이 호출 규약이라 그 보정이 불필요하고, 넣으면
 *       오히려 규약 위반을 감춘다.</li>
 *   <li><b>{@code prev} 배열이 없다.</b> 보간 출력이 없으므로 직전값을 계산기가 들고 있을 이유가
 *       없다. 로그에 찍을 직전값은 호출부가 {@link #get} 으로 미리 읽어 둔다.</li>
 * </ul>
 */
public final class LookDamping {

    public static final int YAW = 0;
    public static final int PITCH = 1;

    /**
     * 거리에 따른 감쇠 계수. {@code client.LookDamper.dampingFor} 와 같은 식이다.
     *
     * @param distance 음수면 far 값을 돌려준다
     */
    public static double dampingFor(double distance) {
        double far = AnimParams.LOOK_DAMPING.get();
        double near = AnimParams.LOOK_DAMPING_NEAR.get();
        double span = AnimParams.LOOK_NEAR_DISTANCE.get();
        if (distance < 0 || span <= 0) {
            return clampCoefficient(far);
        }
        double t = Math.min(1.0D, distance / span);
        return clampCoefficient(near + (far - near) * t);
    }

    /** 대상이 없는 구간(랜덤 시선 / 유휴)이 쓰는 계수. */
    public static double farDamping() {
        return clampCoefficient(AnimParams.LOOK_DAMPING.get());
    }

    private static double clampCoefficient(double k) {
        if (!Double.isFinite(k)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, k));
    }

    private final double[] current = new double[2];
    private boolean started;

    public double get(int axis) {
        return this.current[axis];
    }

    public boolean started() {
        return this.started;
    }

    public void reset() {
        this.started = false;
        this.current[YAW] = 0.0D;
        this.current[PITCH] = 0.0D;
    }

    /**
     * 한 <b>서버 틱</b> 전진한다. 틱당 정확히 한 번만 불러야 한다.
     *
     * <p>NaN/Infinity 는 여기서 막는다 — 목표가 유한하지 않으면 상태를 건드리지 않는다.
     */
    public void advanceTick(double targetYaw, double targetPitch, double damping) {
        if (!Double.isFinite(targetYaw) || !Double.isFinite(targetPitch)) {
            return;
        }
        double k = clampCoefficient(damping);
        if (!this.started || k >= 1.0D) {
            // 첫 관측은 목표에 정착시켜 시작한다. 그러지 않으면 탑승 첫 프레임에 머리가 0 에서
            // 목표까지 쓸고 지나간다. damping >= 1 우회도 같은 이유로 상태를 목표에 고정한다.
            this.current[YAW] = targetYaw;
            this.current[PITCH] = targetPitch;
            this.started = true;
            return;
        }
        this.current[YAW] += (targetYaw - this.current[YAW]) * k;
        this.current[PITCH] += (targetPitch - this.current[PITCH]) * k;
    }
}
