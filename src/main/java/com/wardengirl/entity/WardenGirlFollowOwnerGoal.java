package com.wardengirl.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * T13 — 7.2 / 7.3 일반 추종. <b>순간이동은 없다.</b>
 *
 * <h2>바닐라 {@code FollowOwnerGoal} 을 쓰지 않는 이유</h2>
 *
 * 그 Goal 은 {@code TamableAnimal} 을 요구하고, 경로 생성이 실패하면
 * {@code teleportToAroundBlockPos} 로 <b>순간이동</b>한다. 설계서 7.4와 15장이 둘 다 금지하는
 * 동작이라 상속·복사 대신 필요한 최소 Goal 만 여기 둔다. 앉기·길들이기 상태도 없다.
 *
 * <h2>히스테리시스</h2>
 *
 * <pre>
 *   시작: 거리 &gt; 12
 *   유지: 거리 &gt; 8
 * </pre>
 *
 * 시작 문턱과 종료 문턱이 다르므로 8~12 구간에서는 <b>현재 상태가 유지</b>된다. 문턱이 하나면
 * 그 값 근처에서 {@code canUse} 와 {@code canContinueToUse} 가 매 틱 엇갈려 Goal 이 켜졌다
 * 꺼지고, 그때마다 {@link #stop()} 의 {@code navigation.stop()} 이 경로를 지운다.
 *
 * <h2>경로 실패</h2>
 *
 * 실패는 두 가지이고 <b>같은 카운터</b>로 센다.
 *
 * <ul>
 *   <li>{@code moveTo} 가 {@code false} — 경로를 아예 만들지 못했다.
 *   <li>경로는 생겼는데 <b>제자리다</b>. 실측에서 발견한 쪽이다: 소유자를 40블록 위 공중에 두면
 *       {@code moveTo} 는 계속 {@code true} 를 돌려준다(바닐라가 닿지 못하는 대상에도 부분
 *       경로를 준다). 그것만 보고 있으면 워든걸이 소유자 아래에 도착한 뒤로도 이 Goal 이
 *       {@code MOVE} 를 영원히 쥐고 있어 배회·대기·시선·킁킁이 전부 막힌다.
 * </ul>
 *
 * <p>{@code Path.canReach()} 로 판정하지 않는다 — A* 는 멀지만 도달 가능한 대상에도 부분 경로를
 * 주므로 그걸 실패로 세면 정상적인 장거리 추종이 끊긴다. 거리만 보고 판정하지도 않는다: 달리는
 * 소유자를 못 따라잡는 것과 제자리에 굳은 것은 다른 상태인데 거리 기준은 둘을 구별하지 못한다.
 * 그래서 요구사항 그대로 <b>"제자리"</b>를 본다 — 재경로 사이에 워든걸이 스스로
 * {@value #STUCK_STEP}블록도 움직이지 않았고 소유자와도 가까워지지 않았을 때만 실패다.
 * 걸어가는 중이면 따라잡지 못해도 걸리지 않는다.
 *
 * <p>어느 쪽이든 연속 {@value #FAIL_LIMIT}회면 이 Goal 을 <b>끝낸다</b>. 끝내야 플래그가 풀린다.
 * 끝낸 뒤에는 {@value #RETRY_DELAY}틱 동안 다시 시작하지 않는다 — 그러지 않으면 다음 틱에
 * {@code canUse} 가 또 참이 되어 시작·실패·종료를 매 틱 반복하며 결국 플래그를 독점한다. 대기
 * 시간이 지나면 아무 상태도 남기지 않고 평범하게 재시도하므로, 환경이 다시 유효해지면 그대로
 * 추종이 재개된다(영구 포기 상태와 현지 anchor 는 T14 범위다).
 */
public class WardenGirlFollowOwnerGoal extends Goal {

    /** 2차 설계서 7.2 — 일반 추종 시작. 거리 12블록 <b>초과</b>. */
    public static final double START_DISTANCE = 12.0D;

    /** 2차 설계서 7.2 — 일반 추종 종료. 거리 8블록 <b>이하</b>. */
    public static final double STOP_DISTANCE = 8.0D;

    /** 2차 설계서 7.3 — 일반 추종 속도. */
    public static final double FOLLOW_SPEED = 1.0D;

    private static final double START_SQR = START_DISTANCE * START_DISTANCE;
    private static final double STOP_SQR = STOP_DISTANCE * STOP_DISTANCE;

    /** 경로 갱신 간격(틱). 바닐라 {@code FollowOwnerGoal} 과 같은 값이다. */
    private static final int REPATH_INTERVAL = 10;

    /** 소유자가 마지막 경로 기준점에서 이만큼 벗어나면 간격을 기다리지 않고 갱신한다. */
    private static final double OWNER_MOVE = 1.0D;

    private static final int FAIL_LIMIT = 3;
    private static final int RETRY_DELAY = 40;

    /** 이만큼은 줄어야 "가까워졌다"로 본다. 부동소수점 잡음을 걸러낸다. */
    private static final double PROGRESS_EPSILON = 0.5D;

    /** 재경로 사이에 워든걸이 스스로 이만큼도 움직이지 않았으면 "제자리"다. */
    private static final double STUCK_STEP = 0.5D;

    private final WardenGirlEntity mob;
    private Player owner;
    private int repath;
    private int fails;
    /** 이 틱 이전에는 다시 시작하지 않는다. {@code mob.tickCount} 기준이다. */
    private int retryAtTick;
    /** 시작 이후 기록한 최소 거리제곱. */
    private double bestSqr;
    /** 지난 재경로 때의 워든걸 위치. 제자리 판정용이다. */
    private double lastSelfX;
    private double lastSelfY;
    private double lastSelfZ;
    /** 마지막 재경로가 기준으로 삼은 소유자 위치. */
    private double pathedX;
    private double pathedY;
    private double pathedZ;

    public WardenGirlFollowOwnerGoal(WardenGirlEntity mob) {
        this.mob = mob;
        // MOVE 만 잡는다. LOOK 은 잡지 않으므로 기존 시선 Goal 2종이 추종 중에도 그대로 돈다 —
        // 소유자에게 시선을 강제로 고정하지 않는다.
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.retryAtTick || this.mob.isPassenger()) {
            return false;
        }
        Player found = this.mob.serverOwner();
        if (found == null) {
            return false;                       // 야생·오프라인·다른 차원·사망 → 추종하지 않는다
        }
        if (this.mob.distanceToSqr(found) <= START_SQR) {
            return false;                       // 12 이하에서는 시작하지 않는다
        }
        this.owner = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.owner == null || this.mob.isPassenger() || this.fails >= FAIL_LIMIT) {
            return false;
        }
        // 매 틱 다시 찾는다 — 도중에 로그아웃하거나 차원을 옮기면 그 순간 null 이 되어 끝난다.
        // 소유자의 마지막 위치를 기억하지 않으므로 사라진 좌표를 향해 걸어가지 않는다.
        if (this.mob.serverOwner() != this.owner) {
            return false;
        }
        return this.mob.distanceToSqr(this.owner) > STOP_SQR;
    }

    @Override
    public void start() {
        this.repath = 0;
        this.fails = 0;
        this.bestSqr = Double.MAX_VALUE;
        this.lastSelfX = Double.NaN;
        this.pathedX = Double.NaN;
        this.pathedY = Double.NaN;
        this.pathedZ = Double.NaN;
    }

    @Override
    public void tick() {
        if (this.owner == null) {
            return;
        }
        if (this.repath > 0) {
            this.repath--;
        }
        boolean moved = Double.isNaN(this.pathedX)
                || this.owner.distanceToSqr(this.pathedX, this.pathedY, this.pathedZ)
                        >= OWNER_MOVE * OWNER_MOVE;
        if (this.repath > 0 && !moved) {
            return;
        }
        this.repath = REPATH_INTERVAL;
        this.pathedX = this.owner.getX();
        this.pathedY = this.owner.getY();
        this.pathedZ = this.owner.getZ();
        boolean ok = this.mob.getNavigation().moveTo(this.owner, FOLLOW_SPEED);
        // 가까워졌는가. moveTo 가 true 라도 부분 경로면 거리가 줄지 않는다.
        double now = this.mob.distanceToSqr(this.owner);
        boolean closer = now < this.bestSqr - PROGRESS_EPSILON;
        if (closer) {
            this.bestSqr = now;
        }
        // 제자리인가. 걸어가는 중이면 따라잡지 못해도 실패가 아니다.
        boolean stuck = !Double.isNaN(this.lastSelfX)
                && this.mob.distanceToSqr(this.lastSelfX, this.lastSelfY, this.lastSelfZ)
                        < STUCK_STEP * STUCK_STEP;
        this.lastSelfX = this.mob.getX();
        this.lastSelfY = this.mob.getY();
        this.lastSelfZ = this.mob.getZ();
        if (ok && (closer || !stuck)) {
            this.fails = 0;
        } else {
            this.fails++;                       // FAIL_LIMIT 에 닿으면 canContinueToUse 가 끝낸다
        }
    }

    /**
     * 8블록 안으로 들어왔거나 소유자가 사라졌거나 경로를 못 만들어 끝나는 자리다. 어느 경우든
     * navigation 을 멈춰 자율행동에 넘긴다 — 남은 경로가 있으면 배회 Goal 의
     * {@code canContinueToUse}({@code !navigation.isDone()}) 가 엉뚱하게 참이 된다.
     */
    @Override
    public void stop() {
        boolean gaveUp = this.fails >= FAIL_LIMIT;
        this.owner = null;
        this.repath = 0;
        this.fails = 0;
        this.bestSqr = Double.MAX_VALUE;
        this.lastSelfX = Double.NaN;
        this.pathedX = Double.NaN;
        this.pathedY = Double.NaN;
        this.pathedZ = Double.NaN;
        this.mob.getNavigation().stop();
        if (gaveUp) {
            this.retryAtTick = this.mob.tickCount + RETRY_DELAY;
        }
    }
}
