package com.wardengirl.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * T15 — 중립 전투 대상 선정. <b>선제공격은 없다.</b>
 *
 * <h2>왜 바닐라 Goal 3종을 그대로 쓰지 못하나</h2>
 *
 * {@code HurtByTargetGoal} 은 쓸 수 있지만 {@code alertOthers()} 가 붙어 있고,
 * {@code OwnerHurtByTargetGoal} · {@code OwnerHurtTargetGoal} 은 둘 다 생성자가
 * {@code TamableAnimal} 을 요구한다(1.20.1 확인). 워든걸은 설계서 4.1 대로 {@code tamed}
 * boolean 도 {@code TamableAnimal} 상속도 없으므로 그 두 개는 컴파일 자체가 되지 않는다.
 *
 * <p>그래서 세 Goal 이 하는 <b>판정만</b> 이 한 파일에 옮겨 담았다. 읽는 값은 전부 바닐라
 * {@code LivingEntity} 가 이미 들고 있는 것이다 — 새 전투 기록 시스템을 만들지 않았다.
 *
 * <pre>
 *   자신을 공격한 대상    mob.getLastHurtByMob()      / getLastHurtByMobTimestamp()
 *   소유자를 공격한 대상  owner.getLastHurtByMob()    / getLastHurtByMobTimestamp()
 *   소유자가 공격한 대상  owner.getLastHurtMob()      / getLastHurtMobTimestamp()
 * </pre>
 *
 * <h2>타임스탬프는 그 개체 자신의 tickCount 다</h2>
 *
 * {@code setLastHurtByMob} / {@code setLastHurtMob} 은 둘 다 {@code this.tickCount} 를
 * 타임스탬프로 넣는다(바이트코드 확인). 그래서 <b>200틱 이내</b>를 볼 때 자기 사건은
 * {@code mob.tickCount} 와, 소유자 사건은 {@code owner.tickCount} 와 비교해야 한다. 서버
 * 게임시간과 섞으면 개체가 로드된 시점 차이만큼 어긋난다.
 *
 * <h2>집단 어그로 전파는 하지 않는다</h2>
 *
 * {@code HurtByTargetGoal.alertOthers()} 는 주변 같은 종류 개체에게 {@code setTarget} 을 직접
 * 꽂으면서 개별 관계 검사를 건너뛴다. 그래서 쓰지 않는다 — 각 워든걸은 <b>자기가 실제로
 * 맞은 사건</b>만 처리한다.
 *
 * <h2>투사체</h2>
 *
 * {@code LivingEntity.hurt} 는 {@code DamageSource.getEntity()}(직접 타격체가 아니라
 * <b>원인 개체</b>)를 {@code setLastHurtByMob} 에 넣는다. 화살이면 화살이 아니라 쏜 쪽이
 * 기록되고, 쏜 쪽을 알 수 없으면 아무것도 기록되지 않아 대상이 생기지 않는다. 이 Goal 이
 * 따로 투사체를 벗겨내지 않는 이유가 그것이다.
 */
public class WardenGirlTargetGoal extends Goal {

    /** 2차 설계서 8.2 — 최근 전투 사건 유효시간. */
    public static final int MEMORY_TICKS = 200;

    /**
     * {@code HurtByTargetGoal.HURT_BY_TARGETING} 과 같은 조건이다. 시야와 투명도는 무시한다 —
     * 이미 맞았거나 소유자가 이미 싸운 상대이므로 "보였는지"를 다시 묻지 않는다.
     */
    private static final TargetingConditions COMBAT =
            TargetingConditions.forCombat().ignoreLineOfSight().ignoreInvisibilityTesting();

    private final WardenGirlEntity mob;

    /** {@link #start()} 가 실제로 꽂을 후보. */
    private LivingEntity candidate;

    /** 후보가 어느 사건원에서 왔는지. 처리한 타임스탬프를 그 자리에만 기록하기 위한 값이다. */
    private Source source;

    private enum Source { SELF_HURT, OWNER_HURT, OWNER_HIT }

    /**
     * 사건원별로 <b>마지막으로 처리한</b> 타임스탬프. 바닐라 세 Goal 이 각자 들고 있는
     * {@code timestamp} 필드와 같은 역할이고, 같은 사건을 매 틱 새 사건으로 다시 처리하지
     * 않게 만드는 유일한 장치다.
     */
    private int selfHurtHandled;
    private int ownerHurtHandled;
    private int ownerHitHandled;

    public WardenGirlTargetGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    /**
     * 새 대상을 고를지 판단한다. 이미 쓸 만한 대상이 있으면 <b>바꾸지 않는다</b> — 우선순위는
     * 대상이 없을 때만 의미가 있다.
     */
    @Override
    public boolean canUse() {
        LivingEntity current = this.mob.getTarget();
        if (current != null && this.mob.isValidCombatTarget(current)
                && this.mob.canKeepLeashedCombat(current)) {
            return false;                       // 살아 있고 목줄 안 — 굳이 갈아타지 않는다
        }
        this.candidate = null;
        this.source = null;

        // 1순위 — 자신을 최근 공격한 대상. 야생도 각인 개체도 이것은 한다.
        if (consider(this.mob.getLastHurtByMob(), this.mob.getLastHurtByMobTimestamp(),
                this.selfHurtHandled, this.mob.tickCount, Source.SELF_HURT)) {
            return true;
        }

        // 소유자 사건은 각인 개체만. 오프라인·다른 차원·사망·제거면 serverOwner() 가 null 이고,
        // 대체 플레이어를 찾는 경로는 존재하지 않는다(설계서 7.5 / 15장).
        Player owner = this.mob.serverOwner();
        if (owner == null) {
            return false;
        }
        // 2순위 — 소유자를 최근 공격한 대상.
        if (consider(owner.getLastHurtByMob(), owner.getLastHurtByMobTimestamp(),
                this.ownerHurtHandled, owner.tickCount, Source.OWNER_HURT)) {
            return true;
        }
        // 3순위 — 소유자가 최근 공격한 대상.
        return consider(owner.getLastHurtMob(), owner.getLastHurtMobTimestamp(),
                this.ownerHitHandled, owner.tickCount, Source.OWNER_HIT);
    }

    /**
     * 한 사건원을 검사한다. 통과하면 후보로 세우고 {@code true} 를 돌려준다.
     *
     * @param stamp   사건 타임스탬프. 사건 주체의 {@code tickCount} 기준이다.
     * @param handled 이 사건원에서 마지막으로 처리한 타임스탬프.
     * @param now     사건 주체의 현재 {@code tickCount}.
     */
    private boolean consider(LivingEntity found, int stamp, int handled, int now, Source from) {
        if (found == null || stamp == 0 || stamp == handled) {
            return false;                       // 없거나 이미 처리한 사건이다
        }
        if (now - stamp > MEMORY_TICKS) {
            return false;                       // 200틱보다 오래됐다
        }
        if (!this.mob.isValidCombatTarget(found) || !COMBAT.test(this.mob, found)) {
            return false;
        }
        if (!this.mob.canStartLeashedCombat(found)) {
            return false;                       // T14 시작 목줄. 야생이면 항상 통과한다.
        }
        this.candidate = found;
        this.source = from;
        return true;
    }

    /** 최종 {@code setTarget} 직전에 한 번 더 본다. */
    @Override
    public void start() {
        LivingEntity pick = this.candidate;
        this.candidate = null;
        if (pick == null || !this.mob.isValidCombatTarget(pick)) {
            return;
        }
        this.mob.setTarget(pick);
        Player owner = this.mob.serverOwner();
        // 처리한 사건은 여기서 소인한다. 실패하면 소인하지 않으므로 다음 기회에 다시 본다.
        switch (this.source) {
            case SELF_HURT -> this.selfHurtHandled = this.mob.getLastHurtByMobTimestamp();
            case OWNER_HURT -> {
                if (owner != null) {
                    this.ownerHurtHandled = owner.getLastHurtByMobTimestamp();
                }
            }
            case OWNER_HIT -> {
                if (owner != null) {
                    this.ownerHitHandled = owner.getLastHurtMobTimestamp();
                }
            }
            default -> { }
        }
    }

    /** 유지 판정. 죽음·제거·차원·관계·T14 유지 목줄을 한 자리에서 본다. */
    @Override
    public boolean canContinueToUse() {
        LivingEntity current = this.mob.getTarget();
        return current != null && this.mob.isValidCombatTarget(current)
                && this.mob.canKeepLeashedCombat(current);
    }

    /**
     * 대상을 놓는다. T14 의 "전투 종료 시 target 해제" 가 실제로 일어나는 자리다 — 근접
     * navigation 중단과 소닉 준비 취소는 각 전투 Goal 의 {@code stop()} 이 이어서 한다.
     */
    @Override
    public void stop() {
        this.mob.setTarget(null);
        this.candidate = null;
        this.source = null;
    }
}
