package com.wardengirl.entity;

import com.wardengirl.anim.AnimRegistry;
import net.minecraft.world.entity.ai.goal.Goal;

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
 * <b>적대 기준의 절대 규칙이 아니다.</b> 대상 판정은 {@link WardenGirlHostiles} 한 곳에만 있다.
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

    /** 접근 제한 시간. 좀비가 도망가거나 길이 막히면 포기한다. */
    private static final int APPROACH_LIMIT = 200;

    /**
     * 근접 공격 시작 간격(틱). <b>모션 길이 18틱 위에 얹는 대기가 아니라 간격 그 자체다</b> —
     * 방송한 틱에 걸고 19틱 뒤에 풀리므로, 모션이 끝나는 틱에 곧바로 다음 공격이 나간다.
     * 소닉과 공유하던 100틱을 그대로 쓰던 때에는 모션 뒤에 81틱의 빈 시간이 남았다.
     */
    public static final int MELEE_COOLDOWN = 19;

    /**
     * 전투 추적 중 navigation 속도 배율. 평상시 배회는 {@code 1.0} 그대로다.
     *
     * <p>클라이언트는 이 값을 모른다 — 실제 이동량이 커지면 {@code limbSwingAmount} 가 올라
     * 보폭·다리 진폭·상체 기울임이 따라 커진다. 나중에 소유자를 따라갈 때도 같은 배율을
     * 넘기면 같은 표현이 나온다.
     */
    private static final double PURSUE_SPEED = 1.3D;

    private final WardenGirlEntity mob;
    private net.minecraft.world.entity.LivingEntity target;

    /**
     * 바닐라 근접 사거리 제곱. {@code MeleeAttackGoal.getAttackReachSqr} 와 같은 식이다 —
     * {@code (폭 × 2) × 폭 × 2 + 대상 폭}. 워든걸 0.6 / 좀비 0.6 이면 2.04, 즉 중심 간 1.428블록.
     * 고정 상수 2.4 를 쓰면 히트박스 표면 사이에 1.8블록이 남아 허공을 때리는 그림이 됐다.
     */
    private double reachSqr(net.minecraft.world.entity.LivingEntity t) {
        float w = this.mob.getBbWidth();
        return w * 2.0F * w * 2.0F + t.getBbWidth();
    }

    /** 재생 나이(틱). 음수면 아직 접근 중이다. */
    private int ticks = -1;
    private int approach;

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

    /**
     * <b>쿨다운을 여기서 보지 않는다.</b> 보던 때에는 쿨다운이 공격뿐 아니라 대상 탐색과
     * 접근까지 함께 막아, 공격 모션이 끝나면 몹이 82틱 동안 완전히 멈춰 섰다(실측: 공격 t=195,
     * 모션 종료 t=214, 다음 시작 t=296, 그 사이 활성 Goal 없음 · 경로 null · 속도 0.0000).
     * 쿨다운은 {@link #tick()} 의 사거리 도달 지점에서만 본다 — 막히는 것은 다음 모션뿐이다.
     */
    @Override
    public boolean canUse() {
        if (blocked() || this.mob.isPassenger()) {
            return false;
        }
        this.target = WardenGirlHostiles.nearest(this.mob);
        return this.target != null;
    }

    /**
     * 대상 유지 판정. 죽음·제거·보호 대상 전환을 한 번에 본다 — T12 공통 판정 재사용.
     * 대상 <b>최초 선정</b>은 {@link WardenGirlHostiles#nearest} 가 {@code TargetingConditions
     * .forCombat()} 을 통과시키면서 이미 같은 필터를 건다.
     */
    @Override
    public boolean canContinueToUse() {
        if (!this.mob.isValidCombatTarget(this.target) || blocked() || this.mob.isPassenger()) {
            return false;
        }
        if (this.ticks >= 0) {
            return true;            // 모션 재생 중. 종료는 tick() 이 스스로 한다.
        }
        return this.approach < APPROACH_LIMIT;
    }

    @Override
    public void start() {
        this.ticks = -1;
        this.approach = 0;
        if (this.target != null) {
            this.mob.getNavigation().moveTo(this.target, PURSUE_SPEED);
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
            if (this.ticks < AnimRegistry.ATTACK_LENGTH_TICKS) {
                return;
            }
            // 모션이 끝났다. Goal 을 멈췄다 다시 켜는 대신 여기서 접근 상태로 돌아간다.
            // canContinueToUse 로 끝내면 재시작이 goalSelector 의 전체 평가를 기다려야 하는데,
            // Mob.serverAiStep 은 그것을 (serverTick + entityId) % 2 == 0 인 틱에만 돌린다.
            // 그래서 19틱 쿨다운인데도 실측 간격이 20틱으로 밀렸다. tick() 은 매틱 돌므로
            // (requiresUpdateEveryTick) 여기서 끝내면 간격이 쿨다운 값과 정확히 같아진다.
            this.ticks = -1;
        }
        if (this.mob.distanceToSqr(this.target) <= reachSqr(this.target)) {
            this.mob.getNavigation().stop();
            // 사거리 안이면 접근이 막힌 것이 아니므로 포기 시계를 되돌린다.
            this.approach = 0;
            if (this.mob.isMeleeOnCooldown()) {
                return;                         // 옆에 붙어 기다린다. 추적은 끊기지 않는다.
            }
            // 방송 직전 마지막 재검사. 여기가 T12.5 에서 실제 피해를 붙일 자리이므로, 그때
            // 조건을 다시 쓰지 않도록 지금부터 이 한 줄이 관문이다. 사거리는 바로 위 if 가,
            // 죽음·제거·보호 대상 전환은 isValidCombatTarget 이 본다.
            if (!this.mob.isValidCombatTarget(this.target)) {
                return;                         // 이번 공격 회차를 시작하지 않는다.
            }
            this.ticks = 0;
            this.mob.startMeleeCooldown(MELEE_COOLDOWN);
            this.mob.level().broadcastEntityEvent(this.mob, WardenGirlEntity.EVENT_ATTACK);
            return;
        }
        this.approach++;
        if (this.approach % 10 == 0) {
            this.mob.getNavigation().moveTo(this.target, PURSUE_SPEED);
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.target = null;
        this.ticks = -1;
        this.approach = 0;
    }
}
