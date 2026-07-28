package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;

/**
 * T6 반응형 idle 의 트리거 상태기계. 엔티티 하나당 하나.
 *
 * <h2>지우기 쉽게 만들었다</h2>
 *
 * T6 는 화면에서 부자연스러우면 통째로 폐기될 수 있다. 그래서 이 클래스는 <b>상태와 판정만</b>
 * 갖고, 본을 만지지도 클립을 알지도 못한다. 모델 쪽 결합은 {@code applyActionMotion} 안의
 * 분기 하나뿐이고, {@code sniff_distance} 를 0 으로 두면 {@link #tickSniff} 가 항상 false 를
 * 돌려주므로 코드를 지우지 않고도 완전히 꺼진다.
 *
 * <h2>거리 히스테리시스</h2>
 *
 * 들어오는 문턱과 나가는 문턱이 다르다. 같으면 플레이어가 경계에서 한 발짝씩 움직일 때
 * dwell 이 매 틱 리셋되었다 다시 차오르기를 반복해 발동이 튄다. 나가는 문턱은
 * {@code sniff_distance × }{@value #EXIT_FACTOR} 다 — 이 배율은 사양에 없어서 내가 골랐고,
 * 화면에서 튀면 바꿀 값이다.
 *
 * <h2>왜 클라이언트인가</h2>
 *
 * C3 는 이미 클라이언트에서 직접 평가된다(T5). 트리거를 서버에 두면 새 패킷과 새 동기화
 * 상태가 생기고, 그것은 "지우기 쉽게" 와 정반대다. 대신 <b>각 클라이언트가 자기 플레이어를
 * 기준으로 판정</b>하므로 멀티플레이에서 두 사람이 서로 다른 순간에 킁킁을 본다. 1차에서는
 * 그것이 오히려 자연스럽고, 문제가 되면 그때 서버로 올린다.
 */
public final class IdleReaction {

    /** 나가는 문턱 배율. 사양에 없다 — 내가 고른 값이다. */
    public static final double EXIT_FACTOR = 1.25D;

    /** 회전 단계. 목표 각도 계산과 적용을 분리하기 위한 상태다. */
    public enum Turn { NONE, TURNING, HOLD, RETURNING }

    private Turn turn = Turn.NONE;
    /** 이번 반응이 돌아야 하는 총 각도(도). 부호는 MC yaw 기준. */
    private double turnDelta;
    /** 0..1. TURNING 이면 오르고 RETURNING 이면 내린다. */
    private double turnProgress;
    private int abandoned;
    private int lastTurnTick = Integer.MIN_VALUE;
    /** 뒤·옆(정면 기준 45° 밖)에서 발동한 횟수. 측정 유효 조건. */
    private int firesFromBehind;
    private double pendingDelta;

    public int firesFromBehind() {
        return this.firesFromBehind;
    }

    private int dwell;
    private int cooldown;
    private boolean inside;
    private int lastTick = Integer.MIN_VALUE;

    // ---- 측정용. 상태기계를 건드리지 않는다 -----------------------------------------------
    private boolean turnReady;
    private int elapsedTicks;

    public int elapsedTicks() {
        return this.elapsedTicks;
    }

    public boolean inside() {
        return this.inside;
    }

    public void setTurnReady(boolean ready) {
        this.turnReady = ready;
    }

    private int fires;
    private int hysteresisFlips;
    private int cooldownBlocked;
    private double lastDistance = Double.NaN;

    public int fires() {
        return this.fires;
    }

    public int hysteresisFlips() {
        return this.hysteresisFlips;
    }

    public int cooldownBlocked() {
        return this.cooldownBlocked;
    }

    public int dwell() {
        return this.dwell;
    }

    public int cooldown() {
        return this.cooldown;
    }

    public double lastDistance() {
        return this.lastDistance;
    }

    public int abandoned() {
        return this.abandoned;
    }

    public Turn turn() {
        return this.turn;
    }

    /**
     * 지금 프레임에 적용해야 할 몸통 회전, <b>도</b>. MC yaw 부호다.
     *
     * <h2>계산과 적용을 나눠 둔 이유</h2>
     *
     * 이 값을 어디에 쓰느냐가 D안과 A안의 차이 전부다.
     * <ul>
     *   <li><b>D (지금)</b> — 렌더러가 {@code root.yRot} 에 가산한다. 클라이언트 전용이고
     *       서버는 이 회전을 모른다.</li>
     *   <li><b>A (전환 시)</b> — 서버에서 {@code setYRot(yBodyRot + 이 값)} 을 부른다.</li>
     * </ul>
     * 판정(거리·dwell·쿨다운·각도 상한)과 이 값의 계산은 양쪽에서 그대로 쓰인다.
     *
     * <p><b>A 로 갈 때 주의.</b> {@code setYBodyRot} 만 불러서는 안 된다 —
     * {@code BodyRotationControl.clientTick} 이 이동 중이면 {@code yBodyRot = getYRot()} 로
     * 매 틱 덮어쓴다. {@code yRot} 을 함께 세워야 한다. (아래 Part 11 기록 참조)
     */
    public double turnYawDeg() {
        return this.turnDelta * this.turnProgress;
    }

    /**
     * 회전 상태를 한 틱 전진시킨다. {@link #tickSniff} 보다 먼저 부른다.
     *
     * @param desiredYawDeg 플레이어를 향하는 MC yaw
     * @param bodyYawDeg    지금 몸통 yaw
     * @param near          플레이어가 발동 거리 안에 있는가 (히스테리시스 적용 후)
     * @param playing       킁킁이 재생 중인가
     * @return 회전이 끝나 발동해도 되는 상태면 true
     */
    public boolean tickTurn(double desiredYawDeg, double bodyYawDeg, boolean near,
                            boolean playing, boolean walking, int tickCount) {
        if (tickCount == this.lastTurnTick) {
            return this.turn == Turn.HOLD;
        }
        int elapsed = this.lastTurnTick == Integer.MIN_VALUE ? 1 : tickCount - this.lastTurnTick;
        this.lastTurnTick = tickCount;
        if (elapsed < 0) {
            elapsed = 1;
        }
        // 걷는 중에는 개입하지 않는다. BodyRotationControl 이 이미 매 틱
        // yBodyRot = getYRot() 로 몸을 진행 방향에 맞춰 놓으므로, 그 위에 더하면 몸이 진행
        // 방향에서 비틀린다. 이미 돌아가 있던 각은 되돌린다.
        if (walking) {
            if (this.turn != Turn.NONE) {
                this.turn = Turn.RETURNING;
            }
        }
        if (AnimParams.SNIFF_DISTANCE.get() <= 0.0D || Double.isNaN(desiredYawDeg)) {
            this.turn = Turn.NONE;
            this.turnDelta = 0.0D;
            this.turnProgress = 0.0D;
            return false;
        }
        double step = AnimParams.SNIFF_TURN_TICKS.get() <= 0.0D ? 1.0D
                : elapsed / AnimParams.SNIFF_TURN_TICKS.get();
        switch (this.turn) {
            case NONE -> {
                if (!near) {
                    return false;
                }
                double delta = wrap(desiredYawDeg - bodyYawDeg);
                if (Math.abs(delta) > AnimParams.SNIFF_TURN_MAX_ANGLE.get()) {
                    // 발이 크게 제자리 회전하는 그림을 피한다. 발동을 포기한다.
                    this.abandoned++;
                    return false;
                }
                if (Math.abs(delta) < 1.0D) {
                    this.pendingDelta = delta;
                    return true;      // 이미 보고 있다. 돌 것이 없다
                }
                this.turnDelta = delta;
                this.pendingDelta = delta;
                this.turnProgress = 0.0D;
                this.turn = Turn.TURNING;
                return false;
            }
            case TURNING -> {
                if (!near) {
                    this.turn = Turn.RETURNING;   // 헛돌지 않는다
                    return false;
                }
                this.turnProgress = Math.min(1.0D, this.turnProgress + step);
                if (this.turnProgress >= 1.0D) {
                    this.turn = Turn.HOLD;
                    return true;
                }
                return false;
            }
            case HOLD -> {
                if (!playing) {
                    this.turn = Turn.RETURNING;
                }
                return false;
            }
            case RETURNING -> {
                this.turnProgress = Math.max(0.0D, this.turnProgress - step);
                if (this.turnProgress <= 0.0D) {
                    this.turnDelta = 0.0D;
                    this.turn = Turn.NONE;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private static double wrap(double deg) {
        double d = deg % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    /**
     * 틱당 한 번만 전진한다.
     *
     * <p>{@code setCustomAnimations} 는 프레임마다 돌므로, 그대로 세면 dwell 이 프레임률에 따라
     * 다른 속도로 차오른다. T5 에서 같은 실수를 이미 한 번 했다.
     *
     * @param distance 가장 가까운 플레이어까지의 거리. 없으면 {@link Double#NaN}
     * @return 킁킁을 <b>시작해야 하는 그 한 틱</b>에만 true
     */
    public boolean tickSniff(double distance, int tickCount) {
        if (tickCount == this.lastTick) {
            return false;
        }
        int elapsed = this.lastTick == Integer.MIN_VALUE ? 1 : tickCount - this.lastTick;
        this.lastTick = tickCount;
        if (elapsed < 0) {
            elapsed = 1;
        }
        this.lastDistance = distance;
        this.elapsedTicks = elapsed;

        double enter = AnimParams.SNIFF_DISTANCE.get();
        if (enter <= 0.0D) {
            // 꺼져 있다. 회전도 함께 꺼진다 - 상태를 비워 둔다.
            this.dwell = 0;
            this.inside = false;
            this.turn = Turn.NONE;
            this.turnDelta = 0.0D;
            this.turnProgress = 0.0D;
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldown -= elapsed;
        }

        boolean near;
        if (Double.isNaN(distance)) {
            near = false;
        } else if (this.inside) {
            near = distance <= enter * EXIT_FACTOR;
        } else {
            near = distance <= enter;
        }
        if (near != this.inside) {
            this.hysteresisFlips++;
        }
        this.inside = near;

        if (!near) {
            this.dwell = 0;
            return false;
        }
        this.dwell += elapsed;
        if (this.dwell < AnimParams.SNIFF_DWELL.get()) {
            return false;
        }
        if (!this.turnReady) {
            return false;      // 아직 몸이 안 돌았다
        }
        if (this.cooldown > 0) {
            this.cooldownBlocked++;
            return false;
        }
        this.dwell = 0;
        this.cooldown = (int) Math.round(AnimParams.SNIFF_COOLDOWN.get());
        this.fires++;
        if (Math.abs(this.pendingDelta) > 45.0D) {
            this.firesFromBehind++;
        }
        return true;
    }
}
