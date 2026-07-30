package com.wardengirl.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

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
 *
 * <h2>T19 — 네 번째 사건원은 사건이 아니다</h2>
 *
 * 위 셋은 전부 "이미 일어난 타격"을 타임스탬프로 읽는다. {@link Source#OWNER_APPROACH} 만
 * 다르다 — 아무도 아직 맞지 않았고, 소유자를 {@code getTarget()} 으로 물고 <b>지금</b>
 * 다가오는 중인 적을 현재 상태로만 찾는다. 그래서 소인할 타임스탬프가 없고
 * {@link #MEMORY_TICKS} 기억 창에도 들어가지 않는다. 대신 10틱마다 한 번만 훑는다.
 */
public class WardenGirlTargetGoal extends Goal {

    /** 2차 설계서 8.2 — 최근 전투 사건 유효시간. */
    public static final int MEMORY_TICKS = 200;

    /** T19 — 자동 방어 탐색 주기(서버 틱). */
    public static final int APPROACH_SCAN_INTERVAL = 10;

    /** T19 — 소유자 중심 16×8×16. 수평은 XZ 반경, 수직은 Y 차이다. */
    public static final double APPROACH_HORIZONTAL = 16.0D;
    public static final double APPROACH_VERTICAL = 8.0D;
    private static final double APPROACH_HORIZONTAL_SQR = APPROACH_HORIZONTAL * APPROACH_HORIZONTAL;

    /**
     * 질의 상자만 한 칸 크게 잡는다. {@code getEntitiesOfClass} 는 <b>바운딩 박스가 닿기만
     * 해도</b> 넣어 주는데, 최종 판정은 개체 <b>중심</b>으로 하기 때문이다. 상자를 정확히
     * 16.00 으로 잡으면 중심이 16.00 인 개체가 박스 부동소수점 경계에서 빠질 수 있다.
     * 상자는 후보를 <b>놓치지 않기 위한</b> 것이고, 자르는 일은 아래 명시 필터가 한다.
     */
    private static final double QUERY_HORIZONTAL = 16.01D;
    private static final double QUERY_VERTICAL = 8.01D;

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

    private enum Source { SELF_HURT, OWNER_HURT, OWNER_HIT, OWNER_APPROACH }

    /**
     * 사건원별로 <b>마지막으로 처리한</b> 타임스탬프. 바닐라 세 Goal 이 각자 들고 있는
     * {@code timestamp} 필드와 같은 역할이고, 같은 사건을 매 틱 새 사건으로 다시 처리하지
     * 않게 만드는 유일한 장치다.
     */
    private int selfHurtHandled;
    private int ownerHurtHandled;
    private int ownerHitHandled;

    /**
     * T19 자동 방어 탐색을 다음에 허용할 {@code mob.tickCount}. deadline 방식이다.
     *
     * <p>{@code tickCount % 10} 은 쓸 수 없다. {@code Mob.serverAiStep} 은
     * {@code (서버틱 + getId()) % 2} 로 Goal 을 <b>격틱</b> 호출하므로 이 {@code canUse()} 가
     * 보는 {@code tickCount} 는 개체마다 고정된 <b>홀짝 한쪽</b>만이다. 나머지 짝에 걸리는
     * 개체는 {@code % 10} 이 영원히 참이 되지 않는다. deadline 은 그 홀짝을 그대로 넘겨받아
     * 정확히 10틱 간격을 만든다.
     */
    private int nextApproachScanTick;

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
        if (consider(owner.getLastHurtMob(), owner.getLastHurtMobTimestamp(),
                this.ownerHitHandled, owner.tickCount, Source.OWNER_HIT)) {
            return true;
        }
        // 4순위 — 아직 아무도 맞지 않았지만 소유자에게 실제로 다가오는 적(T19).
        return considerApproach(owner);
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

    /**
     * T19 — 소유자에게 다가오는 적을 10틱마다 한 번 훑는다.
     *
     * <p>여기서 찾는 것은 <b>사건</b>이 아니라 <b>현재 상태</b>다. 그래서 타임스탬프도
     * 소인도 200틱 기억도 없다. 이 판정을 매 틱 돌리면 후보마다 경로를 새로 깔게 되므로
     * 주기를 둔다.
     *
     * <p>야생 워든걸은 {@link #canUse()} 가 {@code owner == null} 에서 이미 돌아가므로
     * 여기까지 오지 않는다 — 자동 방어는 각인 개체 전용이다.
     */
    private boolean considerApproach(Player owner) {
        if (this.mob.tickCount - this.nextApproachScanTick < 0) {
            return false;                       // 아직 주기가 안 됐다
        }
        this.nextApproachScanTick = this.mob.tickCount + APPROACH_SCAN_INTERVAL;

        AABB box = new AABB(
                owner.getX() - QUERY_HORIZONTAL, owner.getY() - QUERY_VERTICAL, owner.getZ() - QUERY_HORIZONTAL,
                owner.getX() + QUERY_HORIZONTAL, owner.getY() + QUERY_VERTICAL, owner.getZ() + QUERY_HORIZONTAL);
        List<Mob> nearby = this.mob.level().getEntitiesOfClass(Mob.class, box, c -> approaching(owner, c));

        Mob best = null;
        double bestHorizontalSqr = 0.0D;
        double bestLength = 0.0D;
        for (Mob c : nearby) {
            // 후보당 정확히 한 번. 도달 판정과 경로 길이를 이 하나로 같이 쓴다.
            Path path = c.getNavigation().createPath(owner, 0);
            if (!c.isWithinMeleeAttackRange(owner) && (path == null || !path.canReach())) {
                continue;                       // 소유자에게 닿지 못한다 — 위협이 아니다
            }
            double horizontalSqr = horizontalSqr(owner, c);
            double length = pathLength(c, owner, path);
            // 소유자에게 가까운 쪽 → 경로가 짧은 쪽. 완전히 같으면 먼저 나온 쪽을 둔다.
            if (best == null || horizontalSqr < bestHorizontalSqr
                    || (horizontalSqr == bestHorizontalSqr && length < bestLength)) {
                best = c;
                bestHorizontalSqr = horizontalSqr;
                bestLength = length;
            }
        }
        if (best == null) {
            return false;
        }
        this.candidate = best;
        this.source = Source.OWNER_APPROACH;
        return true;
    }

    /**
     * 경로를 깔기 <b>전</b>에 통과해야 하는 값싼 조건들. 특정 몹 클래스 목록은 쓰지 않는다 —
     * 무엇이든 소유자를 {@code getTarget()} 으로 물고 있으면 후보다.
     *
     * <p>살아 있음·제거되지 않음·같은 차원·자기 자신 아님은 {@code isValidCombatTarget} 이
     * 이미 전부 본다(소유자·같은 소유자 워든걸·크리에이티브·관전자·{@code canAttack} 포함).
     */
    private boolean approaching(Player owner, Mob candidate) {
        if (candidate.getTarget() != owner) {
            return false;                       // 소유자를 쫓는 중이 아니다
        }
        if (!this.mob.isValidCombatTarget(candidate)) {
            return false;
        }
        // 질의 상자는 바운딩 박스로 잡혔다. 반경은 중심으로 다시 자른다.
        if (horizontalSqr(owner, candidate) > APPROACH_HORIZONTAL_SQR) {
            return false;
        }
        if (Math.abs(candidate.getY() - owner.getY()) > APPROACH_VERTICAL) {
            return false;
        }
        // T14 시작 목줄. 자동 방어 원기둥(16×8)과 T14 구(16)를 <b>둘 다</b> 통과해야 한다 —
        // 예를 들어 수평 15·수직 8 은 원기둥은 통과하지만 3D 17 이라 여기서 걸린다.
        return this.mob.canStartLeashedCombat(candidate);
    }

    /** 소유자와 후보의 <b>수평</b> 거리 제곱. Y 는 보지 않는다. */
    private static double horizontalSqr(Player owner, Mob candidate) {
        double dx = candidate.getX() - owner.getX();
        double dz = candidate.getZ() - owner.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * 후보 → 첫 노드 → … → 마지막 노드 → 소유자 의 실제 길이. 2순위 정렬에만 쓴다.
     *
     * <p>근접 사거리 예외로 통과해 경로가 {@code null} 인 후보는 길이 0 이 아니다 — 노드
     * 구간이 통째로 빠지고 후보-소유자 직선거리만 남는다. 0 으로 두면 경로가 있는 후보를
     * 전부 제치게 된다.
     */
    private static double pathLength(Mob candidate, Player owner, Path path) {
        Vec3 prev = candidate.position();
        double sum = 0.0D;
        if (path != null) {
            for (int i = 0; i < path.getNodeCount(); i++) {
                Vec3 at = path.getEntityPosAtNode(candidate, i);
                sum += prev.distanceTo(at);
                prev = at;
            }
        }
        return sum + prev.distanceTo(owner.position());
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
            // 자동 방어는 사건이 아니라 현재 상태다. 소인할 타임스탬프가 없다.
            case OWNER_APPROACH -> { }
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
