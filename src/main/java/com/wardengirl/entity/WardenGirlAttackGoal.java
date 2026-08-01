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
 * <h2>대상은 이 Goal 이 고르지 않는다 (T15)</h2>
 *
 * T15 부터 대상 선정은 {@link WardenGirlTargetGoal} 하나가 {@code targetSelector} 에서 한다.
 * 이 Goal 은 {@code mob.getTarget()} 을 <b>읽기만</b> 하고 절대 쓰지 않는다 — 그래서 각인
 * 직후 {@code setTarget(null)} 이 들어오면 다음 틱에 곧바로 멈춘다.
 *
 * <h2>피해 (T12.5)</h2>
 *
 * 모션이 시작되는 회차에 {@code Mob.doHurtTarget} 을 <b>정확히 한 번</b> 부른다(선딜 0).
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
     * 피해가 발생하는 모션 나이는 <b>0틱</b>이다 — 사용자가 확정한 최종값이며 조절 수단은 없다.
     * 비교할 카운터가 없으므로 상수도, 회차별 상태도 두지 않고 {@link #tick()} 이 공격을
     * 방송하는 자리에서 곧바로 {@link #strike()} 를 부른다.
     *
     * <p>화면의 내려찍기가 바닥에 닿는 프레임은 클립 시간 8틱(= 배속 1.2 에서 실제 6.7틱)이지만,
     * 피해를 그 프레임까지 미루면 넉백 저항이 0인 저스택 워든걸이 그 사이 공중으로 밀려나
     * 헛공격이 된다(실측 미스 51회가 전부 공중 상태). 그래서 피해는 공격이 성립한 틱에 넣는다.
     */

    /**
     * 전투 추적 중 navigation 속도 배율과 재경로 주기는 T21.5 에서 {@link WardenGirlApproach}
     * 로 옮겼다. 값도 절차도 그대로이며, 소닉 추적({@link WardenGirlSonicPursuitGoal}) 이 같은
     * 것을 쓴다.
     */
    private final WardenGirlApproach pursuit = new WardenGirlApproach();

    private final WardenGirlEntity mob;

    /**
     * 현재 대상. <b>서버 권위 값 하나만 본다</b> — 자기 복사본을 들고 있으면 각인 직후
     * {@code setTarget(null)} 이나 목줄 이탈이 한 틱 늦게 반영된다.
     */
    @javax.annotation.Nullable
    private net.minecraft.world.entity.LivingEntity target() {
        return this.mob.getTarget();
    }

    /**
     * 바닐라 근접 사거리 제곱. {@code MeleeAttackGoal.getAttackReachSqr} 와 같은 식이다 —
     * {@code (폭 × 2) × 폭 × 2 + 대상 폭}. 워든걸 0.6 / 좀비 0.6 이면 2.04, 즉 중심 간 1.428블록.
     * 고정 상수 2.4 를 쓰면 히트박스 표면 사이에 1.8블록이 남아 허공을 때리는 그림이 됐다.
     */
    private double reachSqr(net.minecraft.world.entity.LivingEntity t) {
        return WardenGirlApproach.reachSqr(this.mob, t);
    }

    /**
     * <b>타격 판정 거리만</b> 쓰는 제곱값. 1.8블록 = 3.24 이며 시험 적용이다.
     *
     * <p>공격 <b>시작</b> 사거리와 접근·추적 기준은 {@link #reachSqr} 그대로다(0.6/0.6 이면 2.04,
     * 즉 1.4283블록). 둘을 나눈 이유는 하나다 — 저스택 워든걸은 넉백 저항이 0이라 대상의 반격에
     * 공중으로 떠오르고, 선딜이 있던 동안 밀려나 실측 미스 51회가
     * <b>전부</b> 공중 상태였다. 미스 거리는 최소 1.4418 · 중앙값 1.91 · 최대 2.6653 이었다.
     * 시작 거리까지 1.8로 넓히면 멀리서 모션을 시작해 허공을 때리는 그림이 되므로 넓히지 않는다.
     *
     * <p>거리는 {@code Mob.distanceToSqr(Entity)} 로 재며 시작 판정과 같은 척도다 —
     * 중심 간 거리의 제곱이고, 시작 시점 좌표나 과거 거리를 쓰지 않는다.
     */
    private static final double STRIKE_REACH_SQR = 3.24D;

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

    /**
     * 부호 실측 중에는 시작하지도, 계속하지도 않는다.
     *
     * <p><b>{@code hurtTime} 은 여기 없다.</b> 바닐라 {@code MeleeAttackGoal} 과 같이 피격은
     * 근접 공격의 시작도 진행도 막지 않는다. 예전에는 이 조건에 {@code hurtTime != 0} 이 있어
     * {@code canContinueToUse} 가 false 가 되고 {@link #stop()} 이 {@link #ticks} 를 −1 로
     * 지웠다 — T12.5 에서 피해를 연결한 뒤 실측하니 대상이 반격하는 순간 모션이 12틱에 닿지
     * 못해 <b>7회 시작 중 1회만 타격</b>하고 워든걸이 일반 좀비에게 죽었다.
     *
     * <p>시작용·진행용 조건을 따로 두지 않는다. 하나뿐이라 피격으로는 {@code ticks} 도
     * 쿨다운도 초기화되지 않는다. 사망·탑승·대상 무효화는 {@link #canContinueToUse()} 가
     * 그대로 본다.
     */
    private boolean blocked() {
        // T20 — 후퇴 중에는 시작도 계속도 없다. priority 1 후퇴 Goal 이 MOVE 를 선점해 이미
        // 막히지만, 우선순위 충돌 하나에만 맡기지 않는다. 이 한 줄이 canUse 와
        // canContinueToUse 양쪽에 동시에 걸리므로 진행 중인 공격도 여기서 끊긴다.
        return this.mob.isRetreating() || this.mob.isSignTest();
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
        // T15 — 주변을 훑지 않는다. targetSelector 가 이미 골라 둔 대상만 본다.
        net.minecraft.world.entity.LivingEntity found = target();
        // T14 — 소유자가 있는 워든걸은 소유자 목줄 안에서만 새 전투를 시작한다(워든걸-소유자 12
        // 이하, 대상-소유자 16 이하). 야생이거나 소유자를 찾을 수 없으면 제한하지 않는다.
        return found != null && this.mob.isValidCombatTarget(found)
                && this.mob.canStartLeashedCombat(found);
    }

    /**
     * 대상 유지 판정. 죽음·제거·보호 대상 전환을 한 번에 본다 — T12 공통 판정 재사용.
     * 대상 <b>최초 선정</b>은 {@link WardenGirlTargetGoal} 이 같은 필터를 걸어 이미 끝냈다.
     */
    @Override
    public boolean canContinueToUse() {
        net.minecraft.world.entity.LivingEntity t = target();
        if (!this.mob.isValidCombatTarget(t) || blocked() || this.mob.isPassenger()) {
            return false;
        }
        // T14 — 유지 목줄(워든걸-소유자 16, 대상-소유자 24). 시작보다 넓으므로 경계에서 전투와
        // 추종이 번갈아 켜지지 않는다. 넘으면 여기서 끝내고 stop() 이 target 해제와 navigation
        // 중단을 함께 처리한다. 모션 재생 중이어도 예외를 두지 않는다.
        if (!this.mob.canKeepLeashedCombat(t)) {
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
        this.pursuit.reset();                 // 첫 틱에 즉시 경로를 낸다
    }

    @Override
    public void tick() {
        if (target() == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(target(), 30.0F, 30.0F);
        // 접근 갱신은 모션 분기보다 <b>앞</b>이다. 뒤에 두었을 때에는 모션 재생 중 조기
        // return 때문에 재경로가 아예 실행되지 않았다(실측 재경로 63회 중 모션 중 5회).
        // 그래서 넉백으로 벌어진 거리를 18틱 내내 방치했다.
        updateApproach();
        if (this.ticks >= 0) {
            this.ticks++;
            // 모션 상태를 화면 클립이 실제로 차지하는 15틱(= 18 / 1.2)만 유지한다. 시작 간격은
            // MELEE_COOLDOWN 19틱 그대로이므로 모션이 끝난 뒤 다음 공격까지 4틱이 비어 있다.
            if (this.ticks < AnimRegistry.ATTACK_PLAY_TICKS) {
                return;
            }
            // 모션이 끝났다. Goal 을 멈췄다 다시 켜는 대신 여기서 접근 상태로 돌아간다.
            // canContinueToUse 로 끝내면 재시작이 goalSelector 의 전체 평가를 기다려야 하는데,
            // Mob.serverAiStep 은 그것을 (serverTick + entityId) % 2 == 0 인 틱에만 돌린다.
            // 그래서 19틱 쿨다운인데도 실측 간격이 20틱으로 밀렸다. tick() 은 매틱 돌므로
            // (requiresUpdateEveryTick) 여기서 끝내면 간격이 쿨다운 값과 정확히 같아진다.
            this.ticks = -1;
        }
        if (this.mob.distanceToSqr(target()) <= reachSqr(target())) {
            // 사거리 안이면 접근이 막힌 것이 아니므로 포기 시계를 되돌린다.
            this.approach = 0;
            if (this.mob.isMeleeOnCooldown()) {
                return;                         // 옆에 붙어 기다린다. 추적은 끊기지 않는다.
            }
            // 방송 직전 마지막 재검사. 여기가 T12.5 에서 실제 피해를 붙일 자리이므로, 그때
            // 조건을 다시 쓰지 않도록 지금부터 이 한 줄이 관문이다. 사거리는 바로 위 if 가,
            // 죽음·제거·보호 대상 전환은 isValidCombatTarget 이 본다.
            if (!this.mob.isValidCombatTarget(target())) {
                return;                         // 이번 공격 회차를 시작하지 않는다.
            }
            this.ticks = 0;
            this.mob.startMeleeCooldown(MELEE_COOLDOWN);
            this.mob.level().broadcastEntityEvent(this.mob, WardenGirlEntity.EVENT_ATTACK);
            // 선딜 0 — 공격이 성립한 <b>같은 서버 틱</b>에 판정한다. 이 자리는 회차당 한 번만
            // 지나가고(다음 틱부터 ticks 는 1 이상이라 이 분기에 들어오지 못한다) 다른 어떤
            // 경로도 strike() 를 부르지 않으므로 중복 피해가 구조적으로 없다.
            strike();
            return;
        }
        this.approach++;
    }

    /**
     * 대상에게 계속 붙는다. <b>공격 모션 중에도 매 틱 돈다</b> — 바닐라
     * {@code MeleeAttackGoal.tick()} 이 공격 여부와 무관하게 경로를 갱신하는 것과 같다.
     * {@code navigation.stop()} 은 {@link #stop()} 에서만 부른다 — 바닐라와 같이
     * {@code tick()} 경로에서는 한 번도 멈추지 않는다.
     *
     * <p>정지 현상을 없앤 두 트리거(간격 흔들기·조기 재시도)는 T21.5 에서
     * {@link WardenGirlApproach} 로 옮겼다. 값도 절차도 그대로다.
     */
    private void updateApproach() {
        this.pursuit.update(this.mob, target());
    }

    /**
     * 타격 순간. <b>Goal 은 서버에서만 돈다</b>({@code Mob.serverAiStep} → {@code goalSelector}),
     * 그래서 별도의 {@code isClientSide} 가드를 두지 않았다.
     *
     * <h2>정확히 한 번인 이유</h2>
     *
     * {@link #ticks} 는 방송 틱에 0 이 되고 {@link #tick()} 에서만 1씩 오른다. 이 Goal 은
     * {@code requiresUpdateEveryTick()} 이라 {@code tick()} 이 매 틱 돌므로 카운터가
     * 방송 틱에 <b>한 모션에 한 번만</b> 부른다. 중간에 Goal 이 끊기면
     * {@link #stop()} 이 −1 로 되돌리므로 그 회차의 타격은 아예 일어나지 않는다.
     *
     * <h2>타격 순간에 다시 보는 것</h2>
     *
     * 대상은 방송 시점(0틱)에 이미 검사했지만 8틱 사이에 죽거나·제거되거나·보호 대상이 되거나·
     * 도망칠 수 있다. 그래서 <b>지연 피해를 만들지 않도록</b> 여기서 다시 본다 — 생존·제거·보호·
     * 바닐라 공격 가능 판정은 T12 의 {@code isValidCombatTarget} 하나로, 거리는
     * {@link #STRIKE_REACH_SQR} 로. 둘 중 하나라도 어긋나면 이번 회차는 <b>빗나감</b>이고 피해가
     * 없다.
     *
     * <h2>바닐라 경로를 그대로 쓴다</h2>
     *
     * {@code Mob.doHurtTarget} 이 {@code ATTACK_DAMAGE} 를 읽어
     * {@code target.hurt(damageSources().mobAttack(this), 피해량)} 을 부르고, 성공하면
     * {@code ATTACK_KNOCKBACK}(기본 0) 넉백·인챈트 효과·{@code setLastHurtMob} 까지 바닐라와
     * 같은 순서로 처리한다. 우리가 피해 계산식을 새로 쓰지 않으므로 방어구·저항·무적 틱이
     * 전부 바닐라와 같게 동작한다.
     */
    private void strike() {
        if (!this.mob.isValidCombatTarget(target())) {
            return;                             // 죽음·제거·보호 대상·바닐라 거절 → 피해 없음
        }
        if (this.mob.distanceToSqr(target()) > STRIKE_REACH_SQR) {
            return;                             // 타격 틱 사이에 벗어났다 → 빗나감
        }
        this.mob.doHurtTarget(target());
    }

    @Override
    public void stop() {
        // 대상은 지우지 않는다 — 쓰는 쪽은 WardenGirlTargetGoal 하나다.
        this.mob.getNavigation().stop();
        this.ticks = -1;
        this.approach = 0;
        this.pursuit.reset();
    }
}
