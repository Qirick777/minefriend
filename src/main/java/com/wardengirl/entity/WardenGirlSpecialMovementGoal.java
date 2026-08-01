package com.wardengirl.entity;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * T28 — 특수 이동이 <b>실제로 활성일 때만</b> MOVE 를 잡는 Goal. 4차 설계서 2.4.
 *
 * <p>이동 논리는 하나도 없다. {@link WardenGirlSpecialMovement} controller 를 매 틱 굴리고,
 * controller 가 {@code NONE} 이면 {@code canUse} 가 false 라 selector 에서 사라진다. 그래서
 * 특수 이동이 없을 때의 Goal 선택·navigation·속도는 T27 과 완전히 같다.
 *
 * <p>flag 는 MOVE 하나다. priority 1 로 소닉과 같은 자리인데, 소닉은 T21.5 부터 LOOK 만
 * 잡으므로 둘은 동시에 실행된다. Retreat(2)·SonicPursuit(3)·Attack(4)·TestMove(5)·
 * Follow(6)·Stroll(7) 의 MOVE 는 전부 이 Goal 이 선점한다.
 */
public class WardenGirlSpecialMovementGoal extends Goal {

    private final WardenGirlEntity mob;

    public WardenGirlSpecialMovementGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        return this.mob.specialMovement().isActive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.specialMovement().isActive();
    }

    @Override
    public void tick() {
        this.mob.specialMovement().tick();
    }
}
