package com.wardengirl.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 대상에게 계속 붙는 추격 구동부. T8·T12 의 {@link WardenGirlAttackGoal} 이 쓰던 코드를
 * <b>그대로</b> 꺼낸 것이며 값도 절차도 바꾸지 않았다. T21.5 의
 * {@link WardenGirlSonicPursuitGoal} 이 같은 속도·같은 재경로 주기를 쓰기 위해 공유한다 —
 * 설계서 T21.5 7.3·7.4 가 "새 속도 상수를 중복해서 만들지 않는다", "기존 확정 주기를 그대로
 * 재사용한다" 를 요구한다.
 *
 * <h2>정지 현상을 없애는 두 조건</h2>
 *
 * 고정 10틱 간격만 쓰던 때에는, {@code moveTo} 가 만든 경로가 <b>자기 자리 한 노드</b>로
 * 즉시 완료 판정되어(실측 {@code nav[done=true nodes=1 idx=1]}) 대상이 1.6~3.9블록 앞인데도
 * 최대 10틱을 그대로 서 있었다. 그래서 바닐라와 같은 두 트리거를 쓴다.
 *
 * <ul>
 *   <li>간격을 {@value #REPATH_MIN}~{@code 10}틱으로 흔든다.
 *   <li>대상이 마지막 경로 기준점에서 {@value #REPATH_TARGET_MOVE}블록 이상 움직이거나
 *       <b>경로가 이미 끝났는데 아직 사거리 밖이면</b> 간격을 기다리지 않고 즉시 다시 낸다.
 * </ul>
 *
 * <p>경로 생성이 실패하면 {@value #REPATH_FAIL_PENALTY}틱을 더 기다린 뒤 <b>다시 시도한다</b> —
 * 영구 정지하지 않는다.
 */
final class WardenGirlApproach {

    /**
     * 전투 추적 중 navigation 속도 배율. 평상시 배회는 {@code 1.0} 그대로다.
     *
     * <p>클라이언트는 이 값을 모른다 — 실제 이동량이 커지면 {@code limbSwingAmount} 가 올라
     * 보폭·다리 진폭·상체 기울임이 따라 커진다.
     */
    static final double PURSUE_SPEED = 1.3D;

    /**
     * 재경로 최소 간격과 변동폭. 바닐라 {@code MeleeAttackGoal} 의
     * {@code ticksUntilNextPathRecalculation = 4 + random(7)} 와 같다 — 고정 10틱이던 때에는
     * 경로가 "도착"으로 끝난 뒤 최대 10틱을 그대로 서 있었다(실측 정지 구간 14개 · 83틱 ·
     * 접근 틱의 15.5%).
     */
    static final int REPATH_MIN = 4;
    static final int REPATH_SPREAD = 7;

    /** 경로 생성이 실패했을 때 다음 시도까지 더 기다리는 틱. 바닐라와 같은 값이다. */
    static final int REPATH_FAIL_PENALTY = 15;

    /** 마지막 재경로가 기준으로 삼은 대상 위치에서 이만큼 움직이면 즉시 다시 경로를 낸다. */
    static final double REPATH_TARGET_MOVE = 1.0D;

    /**
     * 경로가 끝났는데 아직 사거리 밖일 때 다음 시도까지 기다리는 상한(틱). 즉시 매 틱
     * 재경로하면 A* 를 매 틱 돌리게 되므로 상한만 낮춘다 — 정지가 최대 10틱에서 이 값으로 준다.
     */
    static final int STALL_RETRY = 2;

    /**
     * 바닐라 근접 사거리 제곱. {@code MeleeAttackGoal.getAttackReachSqr} 와 같은 식이다 —
     * {@code (폭 × 2) × 폭 × 2 + 대상 폭}. 워든걸 0.6 / 좀비 0.6 이면 2.04, 즉 중심 간 1.428블록.
     */
    static double reachSqr(Mob mob, LivingEntity target) {
        float w = mob.getBbWidth();
        return w * 2.0F * w * 2.0F + target.getBbWidth();
    }

    /** 다음 재경로까지 남은 틱. */
    private int repath;
    /** 마지막 재경로 시점의 대상 위치. 대상이 여기서 1블록 이상 벗어나면 즉시 재경로한다. */
    private double pathedX;
    private double pathedY;
    private double pathedZ;

    /** Goal 의 {@code start()} 에서 부른다. 첫 틱에 즉시 경로를 낸다. */
    void reset() {
        this.repath = 0;
        this.pathedX = Double.NaN;
        this.pathedY = Double.NaN;
        this.pathedZ = Double.NaN;
    }

    /**
     * 매 틱 부른다. {@code navigation.stop()} 은 여기서 한 번도 부르지 않는다 — 바닐라
     * {@code MeleeAttackGoal.tick()} 과 같다.
     */
    void update(Mob mob, LivingEntity target) {
        if (this.repath > 0) {
            this.repath--;
        }
        boolean moved = Double.isNaN(this.pathedX)
                || target.distanceToSqr(this.pathedX, this.pathedY, this.pathedZ)
                        >= REPATH_TARGET_MOVE * REPATH_TARGET_MOVE;
        // 경로가 "도착"으로 끝났는데 아직 사거리 밖이면 다음 시도를 앞당긴다.
        if (mob.getNavigation().isDone()
                && mob.distanceToSqr(target) > reachSqr(mob, target)
                && this.repath > STALL_RETRY) {
            this.repath = STALL_RETRY;
        }
        if (this.repath > 0 && !moved) {
            return;
        }
        this.pathedX = target.getX();
        this.pathedY = target.getY();
        this.pathedZ = target.getZ();
        this.repath = REPATH_MIN + mob.getRandom().nextInt(REPATH_SPREAD);
        boolean ok = mob.getNavigation().moveTo(target, PURSUE_SPEED);
        if (!ok) {
            this.repath += REPATH_FAIL_PENALTY;
        }
    }
}
