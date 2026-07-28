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


    private int dwell;
    private int cooldown;
    private boolean inside;
    private int lastTick = Integer.MIN_VALUE;

    // ---- 측정용. 상태기계를 건드리지 않는다 -----------------------------------------------
    private int elapsedTicks;

    public int elapsedTicks() {
        return this.elapsedTicks;
    }

    public boolean inside() {
        return this.inside;
    }

    private boolean frontInside;
    private int frontFlips;
    private int frontRejected;
    private int blockedRejected;
    private int interrupts;
    /** 이번 재생에서 이미 터뜨린 소리 개수. 재생마다 0 으로 돌아간다. */
    private int soundsFired;

    public int frontFlips() {
        return this.frontFlips;
    }

    public int frontRejected() {
        return this.frontRejected;
    }

    public int blockedRejected() {
        return this.blockedRejected;
    }

    public int interrupts() {
        return this.interrupts;
    }

    public int soundsFired() {
        return this.soundsFired;
    }

    public void noteInterrupt() {
        this.interrupts++;
    }

    /** 새 재생이 시작될 때. 소리 카운터를 비운다. */
    public void resetSounds() {
        this.soundsFired = 0;
    }

    /**
     * 재생 나이가 문턱을 넘은 첫 프레임에만 true. 같은 재생 안에서 중복 발화하지 않는다.
     *
     * <p>중단되면 {@link #resetSounds} 가 아니라 <b>카운터를 끝까지 밀어</b> 남은 발화를
     * 취소한다 — 중단된 재생에서 나중 소리가 터지면 몸은 멈췄는데 소리만 나는 그림이 된다.
     */
    public boolean claimSound(double age, double[] thresholds) {
        int i = this.soundsFired;
        if (i < thresholds.length && age >= thresholds[i]) {
            this.soundsFired = i + 1;
            return true;
        }
        return false;
    }

    public void cancelRemainingSounds(int total) {
        this.soundsFired = total;
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

    /**
     * 틱당 한 번만 전진한다.
     *
     * <p>{@code setCustomAnimations} 는 프레임마다 돌므로, 그대로 세면 dwell 이 프레임률에 따라
     * 다른 속도로 차오른다. T5 에서 같은 실수를 이미 한 번 했다.
     *
     * @param distance 가장 가까운 플레이어까지의 거리. 없으면 {@link Double#NaN}
     * @return 킁킁을 <b>시작해야 하는 그 한 틱</b>에만 true
     */
    public boolean tickSniff(double distance, double frontAngleDeg, boolean blocked,
                             int tickCount) {
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
            this.frontInside = false;
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldown -= elapsed;
        }

        // 각도도 거리와 같은 히스테리시스를 쓴다. 경계에서 몸을 흔들 때 dwell 이 리셋됐다
        // 차오르기를 반복하면 발동이 튄다.
        double front = AnimParams.SNIFF_FRONT_ANGLE.get();
        boolean frontOk;
        if (front >= 180.0D) {
            frontOk = true;                       // 제한 없음
        } else if (Double.isNaN(frontAngleDeg)) {
            frontOk = false;
        } else if (this.frontInside) {
            frontOk = frontAngleDeg <= front * EXIT_FACTOR;
        } else {
            frontOk = frontAngleDeg <= front;
        }
        if (frontOk != this.frontInside) {
            this.frontFlips++;
        }
        this.frontInside = frontOk;

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
        if (!frontOk) {
            this.dwell = 0;
            this.frontRejected++;
            return false;
        }
        if (blocked) {
            // 공격 중이거나 피격 중이다. 킁킁은 C3 에서 가장 낮은 우선순위다.
            this.dwell = 0;
            this.blockedRejected++;
            return false;
        }
        this.dwell += elapsed;
        if (this.dwell < AnimParams.SNIFF_DWELL.get()) {
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldownBlocked++;
            return false;
        }
        this.dwell = 0;
        this.cooldown = (int) Math.round(AnimParams.SNIFF_COOLDOWN.get());
        this.fires++;
        this.soundsFired = 0;
        return true;
    }
}
