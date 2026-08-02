package com.wardengirl.entity;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * T29 보정 — 유격 도약 위치까지 <b>평소 이동</b>으로 접근하는 동안에만 MOVE 를 잡는 Goal.
 *
 * <p>이동 논리는 없다. {@link WardenGirlGapJump} 가 계산한 도약 목표로 {@code MoveControl} 을
 * 향하게 하는 것이 전부다. 접근 중에는 T28 Plan 도 {@code SPRINT_GAP_JUMP} 상태도 시작하지
 * 않고, 새 애니메이션 상태도 없다.
 *
 * <p>priority 4 — 근접 전투(4)와 같은 자리이며 그 뒤에 등록되므로 전투가 먼저 잡고,
 * TestMove(5)·Follow(6)·Stroll(7) 은 이 Goal 이 도는 동안 선점된다. 특수 이동(1)이 시작되면
 * {@code canUse} 가 false 가 되어 즉시 물러난다.
 */
public class WardenGirlGapJumpApproachGoal extends Goal {

    private final WardenGirlEntity mob;

    public WardenGirlGapJumpApproachGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        return this.mob.gapJump().hasApproachTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.gapJump().hasApproachTarget();
    }

    @Override
    public void tick() {
        this.mob.gapJump().tickApproach();
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }
}
