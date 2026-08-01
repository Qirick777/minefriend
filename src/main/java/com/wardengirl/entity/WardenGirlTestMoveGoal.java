package com.wardengirl.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * T27 — 시험용 <b>고정 목적지</b> 이동. 4차 설계서 Part 3.4.
 *
 * <p>{@code /wardengirl test move to_player} 가 명령 실행 순간의 플레이어 발 위치를
 * {@link WardenGirlEntity#setTestDestination} 으로 넣으면 이 Goal 이 그 좌표 하나로 걸어간다.
 * <b>플레이어를 따라가지 않는다</b> — 목적지는 명령 시점에 고정된 값이고 이후 갱신되지 않는다.
 *
 * <h2>이 Goal 이 하지 않는 것</h2>
 *
 * 목적지를 스스로 정하지 않고, target 을 만들지 않으며, 매 틱 {@code moveTo} 를 부르지 않는다.
 * 특수 이동(T28 이후)도 여기 없다 — 지금은 기존 바닐라 navigation 하나만 쓴다.
 *
 * <h2>우선순위</h2>
 *
 * priority 5, flag 는 MOVE 하나다. 후퇴(2)·소닉 추적(3)·근접(4) 이 MOVE 를 잡으면 그쪽이
 * 선점하고, 일반 추종(6)·배회(7) 는 이 Goal 이 도는 동안 선점당한다. 설계서가 요구한
 * "후퇴·소닉·전투보다 낮고 추종·배회보다 높은" 자리다.
 */
public class WardenGirlTestMoveGoal extends Goal {

    /** 이동 속도 배율. 일반 순항 추종과 같은 값이라 새 수치를 만들지 않았다. */
    public static final double SPEED = WardenGirlFollowOwnerGoal.CRUISE_SPEED;

    /** 재경로 주기(틱). 기존 추종과 같은 값이다. */
    private static final int REPATH_INTERVAL = 10;

    /**
     * 도착 판정 거리. 바닐라 navigation 의 도착 판정(경로 종료)과 함께 쓰며, 여기서 지나치게
     * 정밀한 기준을 새로 만들지 않는다.
     */
    private static final double ARRIVE_DISTANCE = 1.5D;

    private final WardenGirlEntity mob;
    private int nextRepathTick;

    public WardenGirlTestMoveGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * 설계서 3.4 의 양보 조건. 전투 target 이 생기거나 후퇴에 들어가면 시험 이동을 멈춘다 —
     * MOVE 우선순위만으로도 선점되지만 조건에도 넣어 두 겹으로 막는다. 목적지가 다른 차원인
     * 경우는 명령이 애초에 거절하므로 여기서는 같은 레벨만 확인한다.
     */
    private boolean usable() {
        Vec3 dest = this.mob.getTestDestination();
        if (dest == null || this.mob.isPassenger()) {
            return false;
        }
        if (this.mob.isRetreating()) {
            return false;
        }
        if (this.mob.getTarget() != null && this.mob.isValidCombatTarget(this.mob.getTarget())) {
            return false;                       // 실제 전투가 시작됐다
        }
        return this.mob.position().distanceTo(dest) > ARRIVE_DISTANCE;
    }

    @Override
    public boolean canUse() {
        return usable();
    }

    @Override
    public boolean canContinueToUse() {
        return usable();
    }

    @Override
    public void start() {
        this.nextRepathTick = 0;                // 첫 tick 에서 바로 경로를 만든다
    }

    @Override
    public void tick() {
        Vec3 dest = this.mob.getTestDestination();
        if (dest == null) {
            return;
        }
        if (this.mob.tickCount - this.nextRepathTick < 0) {
            return;
        }
        this.nextRepathTick = this.mob.tickCount + REPATH_INTERVAL;
        this.mob.getNavigation().moveTo(dest.x, dest.y, dest.z, SPEED);
    }

    /**
     * 도착했거나 양보로 끝났다. 목적지는 <b>도착했을 때만</b> 지운다 — 전투·후퇴에 자리를 내준
     * 경우에는 그 상황이 끝나면 남은 목적지로 다시 걸어가야 한다. 명시적 취소는
     * {@link WardenGirlEntity#cancelTestMovement()} 가 한다.
     */
    @Override
    public void stop() {
        Vec3 dest = this.mob.getTestDestination();
        if (dest != null && this.mob.position().distanceTo(dest) <= ARRIVE_DISTANCE) {
            this.mob.clearTestDestination();
        }
        this.mob.getNavigation().stop();
        this.nextRepathTick = 0;
    }
}
