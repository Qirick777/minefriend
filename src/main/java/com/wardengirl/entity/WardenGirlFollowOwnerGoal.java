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
 *   유지: 거리 &gt; 5      (T13.5 — 배회 반경 8과 분리했다)
 * </pre>
 *
 * 시작 문턱과 종료 문턱이 다르므로 5~12 구간에서는 <b>현재 상태가 유지</b>된다. 문턱이 하나면
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
 * 걸어가는 중이면 따라잡지 못해도 걸리지 않는다. T13.6 에서 최고속도가 1.8로 낮아져 달리는
 * 소유자를 쫓는 구간이 더 길어졌지만, 그 구간은 워든걸이 실제로 전진하므로 이 판정에 걸리지
 * 않는다.
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

    /**
     * T13.5 — 추종 <b>완료</b> 거리. 5블록 이하면 끝난다.
     *
     * <p>배회 반경 8과는 <b>다른 값이고 다른 뜻이다</b>. 8은
     * {@link WardenGirlOwnerStrollGoal#OWNER_RADIUS} 즉 생활 반경이고, 5는 "따라붙기를 끝내도
     * 되는 거리"다. 둘을 같은 값으로 두던 때에는 생활 반경 경계에 닿자마자 추적이 끝나
     * 체감상 계속 낙오했다. 여기서 끝나면 배회 Goal 이 반경 2~8 안에서 다음 행동을 고른다.
     */
    public static final double STOP_DISTANCE = 5.0D;

    /**
     * T13.5 — 거리별 추종 속도. 상태를 저장하지 않고 <b>매 재경로마다 현재 거리로만</b> 고른다.
     * T13.6 에서 최고속도를 낮췄다.
     *
     * <pre>
     *   d &gt; 20      1.8   먼 거리 추격 (T13.6: 2.8 → 1.8)
     *   8 &lt; d ≤ 20  1.6   정상 추격   (T13.6: 2.0 → 1.6)
     *   5 &lt; d ≤ 8   1.3   감속 접근 — 소유자 몸속으로 돌진하지 않는다 (변경 없음)
     * </pre>
     *
     * <p>T13.5 의 2.8·2.0 은 실제로는 따라잡았지만 눈으로 보면 미끄러지듯 순간이동하는 속도였다.
     * 다리 모션은 T9 의 실제 이동속도 기반 walk/run 을 그대로 쓰므로, 화면상의 부자연스러움을
     * 없애는 방법은 <b>실제 이동속도를 낮추는 것</b> 하나뿐이다. 모션을 빠르게 재생해 속도를
     * 가리거나 추종 전용 애니메이션 상태를 만들지 않는다.
     *
     * <p>{@code 20} 은 여기서 <b>속도 전환 경계일 뿐</b>이다. T14 의 빠른 추종 상태(20/16)와는
     * 무관하며 전환 상태나 저장값을 만들지 않는다. 이동속도 Attribute 의 base value 를 바꾸거나
     * modifier 를 붙이지 않는다 — {@code PathNavigation.moveTo} 의 속도 배율 인자만 쓴다.
     */
    public static final double SPRINT_DISTANCE = 20.0D;
    public static final double CRUISE_DISTANCE = 8.0D;
    public static final double SPRINT_SPEED = 1.8D;
    public static final double CRUISE_SPEED = 1.6D;
    public static final double CLOSE_SPEED = 1.3D;

    private static final double START_SQR = START_DISTANCE * START_DISTANCE;
    private static final double STOP_SQR = STOP_DISTANCE * STOP_DISTANCE;
    private static final double SPRINT_SQR = SPRINT_DISTANCE * SPRINT_DISTANCE;
    private static final double CRUISE_SQR = CRUISE_DISTANCE * CRUISE_DISTANCE;

    /**
     * 거리제곱으로 바로 고른다. 제곱근을 뽑지 않으므로 경계가 정확히 {@code 20}·{@code 8} 이다 —
     * {@code d > 20} 은 {@code d² > 400}, {@code d > 8} 은 {@code d² > 64} 와 같은 판정이다.
     */
    private static double followSpeed(double distSqr) {
        if (distSqr > SPRINT_SQR) {
            return SPRINT_SPEED;
        }
        if (distSqr > CRUISE_SQR) {
            return CRUISE_SPEED;
        }
        return CLOSE_SPEED;
    }

    /**
     * T13.6 — 경로 갱신 간격. 단위는 <b>서버 틱</b>이다. {@code mob.tickCount} 로 직접 잰다.
     *
     * <h3>왜 "감소 카운터 10"이 10틱이 아니었나</h3>
     *
     * {@code Mob.serverAiStep} 은 {@code MinecraftServer.getTickCount() + getId()} 가 홀수이고
     * {@code tickCount > 1} 이면 {@code goalSelector.tickRunningGoals(false)} 만 부르고, 그
     * 경우 {@code WrappedGoal.requiresUpdateEveryTick()} 이 참인 Goal 만 {@code tick()} 된다.
     * 이 Goal 은 그것을 재정의하지 않으므로 <b>두 틱에 한 번</b>만(개체마다 고정된 홀짝으로)
     * tick 된다. 그래서 틱마다 1씩 줄이는 카운터 10은 실제로 <b>20 서버 틱</b>이었다.
     *
     * <p>고치는 방법은 두 가지였다. {@code requiresUpdateEveryTick()} 을 참으로 바꾸면 카운터
     * 단위가 실제 틱과 같아지지만, 함께 있는 "소유자가 {@value #OWNER_MOVE}블록 움직이면 즉시
     * 갱신" 검사까지 두 배로 자주 돌아 달리는 소유자를 쫓을 때 재경로가 매 틱 쪽으로 몰린다.
     * 그래서 tick 주기는 그대로 두고 <b>기준을 카운터에서 {@code tickCount} 시각으로</b> 바꿨다.
     * Goal 의 tick 홀짝이 개체마다 고정이므로 {@code tickCount + 10} 은 정확히 10틱 뒤의 tick
     * 호출에 걸린다. 엔진의 tick 주기가 바뀌어도 이 값의 뜻은 변하지 않는다.
     */
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
    /** 이 {@code mob.tickCount} 이상이 되면 다음 재경로를 만든다. */
    private int nextRepathTick;
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
        this.nextRepathTick = 0;                // 첫 tick 에서 바로 경로를 만든다
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
        boolean moved = Double.isNaN(this.pathedX)
                || this.owner.distanceToSqr(this.pathedX, this.pathedY, this.pathedZ)
                        >= OWNER_MOVE * OWNER_MOVE;
        if (this.mob.tickCount < this.nextRepathTick && !moved) {
            return;
        }
        this.nextRepathTick = this.mob.tickCount + REPATH_INTERVAL;
        this.pathedX = this.owner.getX();
        this.pathedY = this.owner.getY();
        this.pathedZ = this.owner.getZ();
        // 이번 재경로의 속도는 <b>지금 거리</b>로 고른다. 경계를 넘어도 Goal 을 다시 시작하거나
        // navigation 을 멈추지 않는다 — 다음 정상 갱신부터 새 배율이 실릴 뿐이다.
        double now = this.mob.distanceToSqr(this.owner);
        boolean ok = this.mob.getNavigation().moveTo(this.owner, followSpeed(now));
        // 가까워졌는가. moveTo 가 true 라도 부분 경로면 거리가 줄지 않는다.
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
     * {@value #STOP_DISTANCE}블록 안으로 들어왔거나 소유자가 사라졌거나 경로를 못 만들어 끝나는
     * 자리다. 어느 경우든
     * navigation 을 멈춰 자율행동에 넘긴다 — 남은 경로가 있으면 배회 Goal 의
     * {@code canContinueToUse}({@code !navigation.isDone()}) 가 엉뚱하게 참이 된다.
     */
    @Override
    public void stop() {
        boolean gaveUp = this.fails >= FAIL_LIMIT;
        this.owner = null;
        this.nextRepathTick = 0;
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
