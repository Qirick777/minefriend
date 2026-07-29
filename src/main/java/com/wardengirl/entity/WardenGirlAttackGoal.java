package com.wardengirl.entity;

import com.wardengirl.anim.AnimRegistry;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * T7 — 4.8 기본 공격 모션 발동. <b>임시 검증용이며 쉽게 제거하도록 격리했다.</b>
 *
 * <h2>제거 방법</h2>
 *
 * 이 파일 하나와 {@code WardenGirlEntity.registerGoals()} 의 {@code addGoal(3, ...)} 한 줄을
 * 지우면 끝난다. 다른 어떤 파일도 이 클래스를 참조하지 않는다.
 *
 * <h2>좀비는 임시 대상이다</h2>
 *
 * <b>적대 기준의 절대 규칙이 아니다.</b> 공격 모션을 화면에서 반복해 보기 위한 표적일 뿐이며,
 * 범용 적대·진영·태그 체계를 만들지 않았다. 대상 판정은 {@link #TARGET} 한 곳에 모여 있다.
 *
 * <h2>피해가 없다</h2>
 *
 * {@code hurt} / {@code doHurtTarget} / {@code setTarget} 을 부르지 않는다. 이 Goal 이 하는 일은
 * 좀비 쪽으로 걸어가 사거리에서 <b>엔티티 사건 하나를 방송</b>하고 18틱을 세는 것뿐이다.
 *
 * <h2>구조는 4.12 킁킁과 같다</h2>
 *
 * 서버 Goal 이 발동을 결정 → {@code broadcastEntityEvent} → 클라이언트가 {@code attackTime} 을
 * 세팅하고 클립을 재생. 새 패킷도, 동기화 각도도, 순번도 없다.
 */
public class WardenGirlAttackGoal extends Goal {

    /** 좀비를 찾는 반경. */
    private static final double SEARCH = 16.0D;
    /** 이 거리 안에 들어오면 모션을 낸다. 좀비 폭 0.6 + 워든걸 폭 0.6 을 감안한 값. */
    private static final double REACH = 2.4D;
    /** 모션이 끝난 뒤 재발동 금지 틱. */
    private static final int COOLDOWN = 20;
    /** 접근 제한 시간. 좀비가 도망가거나 길이 막히면 포기한다. */
    private static final int APPROACH_LIMIT = 200;

    /** 대상 규약. 관전자·무적 제외는 바닐라 것을 그대로 쓴다. */
    private static final TargetingConditions TARGET =
            TargetingConditions.forCombat().range(SEARCH).ignoreLineOfSight();

    private final WardenGirlEntity mob;
    private Zombie target;
    /** 재생 나이(틱). 음수면 아직 접근 중이다. */
    private int ticks = -1;
    private int approach;
    private int cooldown;
    private int lastTick = Integer.MIN_VALUE;

    public WardenGirlAttackGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** 피격·부호 실측 중에는 시작하지도, 계속하지도 않는다. */
    private boolean blocked() {
        return this.mob.hurtTime != 0 || this.mob.isSignTest();
    }

    private Zombie findZombie() {
        AABB box = this.mob.getBoundingBox().inflate(SEARCH);
        return this.mob.level().getNearestEntity(Zombie.class, TARGET, this.mob,
                this.mob.getX(), this.mob.getEyeY(), this.mob.getZ(), box);
    }

    @Override
    public boolean canUse() {
        int tick = this.mob.tickCount;
        int elapsed = this.lastTick == Integer.MIN_VALUE ? 1 : tick - this.lastTick;
        this.lastTick = tick;
        if (elapsed < 0) {
            elapsed = 1;
        }
        if (this.cooldown > 0) {
            this.cooldown -= elapsed;
            return false;
        }
        if (blocked() || this.mob.isPassenger()) {
            return false;
        }
        this.target = findZombie();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.target == null || !this.target.isAlive() || blocked() || this.mob.isPassenger()) {
            return false;
        }
        if (this.ticks < 0) {
            return this.approach < APPROACH_LIMIT;
        }
        return this.ticks < AnimRegistry.ATTACK_LENGTH_TICKS;
    }

    @Override
    public void start() {
        this.ticks = -1;
        this.approach = 0;
        if (this.target != null) {
            this.mob.getNavigation().moveTo(this.target, 1.0D);
        }
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        if (this.ticks >= 0) {
            // 모션 재생 중. 서버는 틱만 센다 — 피해도, 판정도 없다.
            this.ticks++;
            return;
        }
        this.approach++;
        if (this.mob.distanceToSqr(this.target) <= REACH * REACH) {
            this.mob.getNavigation().stop();
            this.ticks = 0;
            this.mob.level().broadcastEntityEvent(this.mob, WardenGirlEntity.EVENT_ATTACK);
            return;
        }
        if (this.approach % 10 == 0) {
            this.mob.getNavigation().moveTo(this.target, 1.0D);
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.target = null;
        this.ticks = -1;
        this.approach = 0;
        this.cooldown = COOLDOWN;
    }
}
