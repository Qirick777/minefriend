package com.wardengirl.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * T21.5 — <b>NORMAL 소닉 회차 동안의 이동만</b> 맡는다. 설계서 T21.5 4.3.
 *
 * <p>T21 까지 {@link WardenGirlSonicBoomGoal} 이 MOVE 를 잡고 {@code start()} 에서
 * {@code navigation.stop()} 을 불렀다. 그래서 소닉 60틱 내내 워든걸이 굳어 있었고, 도망가는
 * 대상에게 쏘면 그 자리에 서서 헛방을 냈다. T21.5 부터 소닉은 LOOK 만 잡고 이동은 이 Goal 이
 * 정한다.
 *
 * <h2>하지 않는 것</h2>
 *
 * 소닉 시작·age 변경·피해·넉백·애니메이션·쿨다운·근접 타격·대상 선정을 하나도 하지 않는다.
 * 이 Goal 이 쓰는 것은 {@link WardenGirlSonicBoomGoal} 의 읽기 전용 상태 셋뿐이다.
 *
 * <h2>RETREAT 회차에는 돌지 않는다</h2>
 *
 * 후퇴 소닉의 이동은 T20 의 {@link WardenGirlRetreatGoal}(priority 2) 이 그대로 맡는다.
 * 이 Goal 은 priority 3 이므로 후퇴가 돌고 있으면 MOVE 를 얻지 못하고, 조건에서도
 * {@code mode == NORMAL} 과 {@code !isRetreating()} 을 함께 본다.
 *
 * <h2>근접 공격과의 관계</h2>
 *
 * 이 Goal 은 {@link WardenGirlAttackGoal} 의 <b>이동 부분만</b> 대신한다. 타격은 없다.
 * 근접이 소닉 중에 시작되지 못하는 것은 소닉이 잡은 LOOK(priority 1) 때문이며 여기서 따로
 * 막지 않는다 — 같은 규칙을 두 곳에 쓰지 않는다.
 */
public class WardenGirlSonicPursuitGoal extends Goal {

    private final WardenGirlEntity mob;
    private final WardenGirlSonicBoomGoal sonic;
    /** 속도·재경로 주기는 근접 추격과 <b>같은 것</b>을 쓴다. 설계서 7.3·7.4. */
    private final WardenGirlApproach pursuit = new WardenGirlApproach();

    public WardenGirlSonicPursuitGoal(WardenGirlEntity mob, WardenGirlSonicBoomGoal sonic) {
        this.mob = mob;
        this.sonic = sonic;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * 설계서 7.1 그대로다. 소닉이 도는 <b>동안 내내</b> 참이어야 하므로 시작·유지 조건이 같다 —
     * 대상이 근접 사거리 안에 들어와 이동을 멈추는 동안에도 이 Goal 은 살아 있어야 한다.
     * 그래야 MOVE 가 추종(5)·배회(6) 로 새지 않는다.
     */
    private boolean usable() {
        if (!this.sonic.isCasting() || !this.sonic.isNormalCast() || this.mob.isRetreating()) {
            return false;
        }
        LivingEntity t = this.sonic.castTarget();
        // T14 — 유지 목줄. 소닉 본체의 canContinueToUse 와 같은 판정을 쓴다.
        return t != null && this.mob.isValidCombatTarget(t) && this.mob.canKeepLeashedCombat(t);
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
        this.pursuit.reset();
    }

    /**
     * 설계서 7.2·7.5.
     *
     * <ul>
     *   <li>대상이 근접 사거리 <b>밖</b>이면 쫓는다.
     *   <li>사거리 <b>안</b>이면 더 밀어붙이지 않고 navigation 을 멈춘다. 대상이 다시 멀어지면
     *       같은 판정이 그대로 추적을 재개한다 — "도망 중" boolean 도 속도 예측도 두지 않는다.
     *   <li>경로가 없으면(벽 뒤 등) {@link WardenGirlApproach} 가 실패 지연을 두고 다시 시도할
     *       뿐이다. 소닉은 취소하지 않는다 — 소닉은 벽을 통과한다.
     * </ul>
     */
    @Override
    public void tick() {
        LivingEntity t = this.sonic.castTarget();
        if (t == null) {
            return;
        }
        if (this.mob.distanceToSqr(t) <= WardenGirlApproach.reachSqr(this.mob, t)) {
            if (!this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().stop();
            }
            return;
        }
        this.pursuit.update(this.mob, t);
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.pursuit.reset();
    }
}
