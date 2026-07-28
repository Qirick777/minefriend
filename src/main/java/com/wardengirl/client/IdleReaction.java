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

        double enter = AnimParams.SNIFF_DISTANCE.get();
        if (enter <= 0.0D) {
            // 꺼져 있다. 상태도 함께 비워 둔다 - 다시 켰을 때 낡은 dwell 로 즉시 터지면 안 된다.
            this.dwell = 0;
            this.inside = false;
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
        if (this.cooldown > 0) {
            this.cooldownBlocked++;
            return false;
        }
        this.dwell = 0;
        this.cooldown = (int) Math.round(AnimParams.SNIFF_COOLDOWN.get());
        this.fires++;
        return true;
    }
}
