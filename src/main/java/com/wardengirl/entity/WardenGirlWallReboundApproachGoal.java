package com.wardengirl.entity;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * T31 — 도움닫기 출발 위치까지 걸어가는 동안만 {@code MOVE} 를 쥔다.
 *
 * <p>계획이 시작된 뒤의 MOVE 점유는 {@link WardenGirlSpecialMovementGoal} 이 그대로 맡는다 —
 * 이 Goal 은 <b>계획 시작 전</b> 접근 구간에만 존재하며, T29·T30 의
 * {@link WardenGirlGapJumpApproachGoal} 과 같은 자리·같은 방식이다.
 */
public class WardenGirlWallReboundApproachGoal extends Goal {

    private final WardenGirlEntity mob;

    public WardenGirlWallReboundApproachGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.mob.wallRebound().hasApproachTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.wallRebound().hasApproachTarget();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.mob.wallRebound().tickApproach();
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }
}
