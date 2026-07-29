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
 * <h2>피해 (T12.5)</h2>
 *
 * 모션 한 회당 {@link #HIT_TICK} 에서 {@code Mob.doHurtTarget} 을 <b>정확히 한 번</b> 부른다.
 * {@code setTarget} 은 여전히 부르지 않는다 — 이 Goal 은 자기 {@link #target} 필드만 쓴다.
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
     * 실제 타격이 일어나는 모션 나이(틱). <b>내려찍기가 바닥에 닿는 프레임이다.</b>
     *
     * <p>{@code attack} 클립(0.9초 = 18틱)의 {@code arm_right.rotation.x} 실측:
     *
     * <pre>
     *   t=0   0°      t=2  −12°   (짧은 예비 젖힘)
     *   t=6  150°     t=7  158°   (최고 들어올림)
     *   t=9  155°  → t=11  30°    (2틱에 125° — 내려찍는 구간)
     *   t=12  18°               ← 팔이 가장 아래·가장 앞. body 도 여기서 −14° 로 최대 전방 기울임
     *   t=14  34°     t=16  8°    t=18  0°   (반동과 복귀)
     * </pre>
     *
     * 그래서 12틱이 화면에서 "맞았다"로 보이는 순간이다. 더 이르면(9~11) 팔이 아직 머리 위에
     * 있는 동안 피가 깎이고, 더 늦으면(14+) 이미 팔이 되돌아오는 중이다.
     *
     * <p>무적 틱과도 어긋나지 않는다. 타격 간격은 방송 간격과 같은 19틱인데
     * {@code LivingEntity.hurt} 는 {@code invulnerableTime > 10} 일 때만 감쇠 경로로 가고
     * (성공 시 20으로 설정, 매 틱 1씩 감소), 19틱 뒤에는 1이 되어 있으므로 <b>매 회차가 전액</b>
     * 들어간다.
     */
    public static final int HIT_TICK = 12;

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

    /** 재경로 간격(틱). 바닐라 {@code MeleeAttackGoal} 의 경로 재계산 주기와 같은 자리다. */
    private static final int REPATH_INTERVAL = 10;

    /** 재생 나이(틱). 음수면 아직 접근 중이다. */
    private int ticks = -1;
    private int approach;
    /** 다음 재경로까지 남은 틱. */
    private int repath;

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
        return this.mob.isSignTest();
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
        this.repath = REPATH_INTERVAL;
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
            this.ticks++;
            if (this.ticks == HIT_TICK) {
                strike();                       // 모션당 정확히 한 번. 아래 주석 참고.
            }
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
        // 바닐라 MeleeAttackGoal 은 공격 중에도 대상에게 계속 경로를 새로 낸다. 사거리 안에서
        // navigation 을 멈춰 두면, 우리 타격과 상대 반격의 넉백으로 벌어진 거리를 18틱 모션
        // 동안 메우지 못해 12틱 타격이 매번 사거리 밖이 된다 — 반격하는 좀비와 붙여 실측하니
        // 30회 시작 중 타격 1회, 미스 29회가 전부 reason=range 였다. 그래서 멈추지 않고
        // 10틱마다 다시 붙는다. 대상이 이미 닿아 있으면 경로가 즉시 끝나므로 제자리다.
        if (--this.repath <= 0) {
            this.repath = REPATH_INTERVAL;
            this.mob.getNavigation().moveTo(this.target, PURSUE_SPEED);
        }
        if (this.mob.distanceToSqr(this.target) <= reachSqr(this.target)) {
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
    }

    /**
     * 타격 순간. <b>Goal 은 서버에서만 돈다</b>({@code Mob.serverAiStep} → {@code goalSelector}),
     * 그래서 별도의 {@code isClientSide} 가드를 두지 않았다.
     *
     * <h2>정확히 한 번인 이유</h2>
     *
     * {@link #ticks} 는 방송 틱에 0 이 되고 {@link #tick()} 에서만 1씩 오른다. 이 Goal 은
     * {@code requiresUpdateEveryTick()} 이라 {@code tick()} 이 매 틱 돌므로 카운터가
     * {@link #HIT_TICK} 을 <b>한 모션에 한 번만</b> 통과한다. 중간에 Goal 이 끊기면
     * {@link #stop()} 이 −1 로 되돌리므로 그 회차의 타격은 아예 일어나지 않는다.
     *
     * <h2>타격 순간에 다시 보는 것</h2>
     *
     * 대상은 방송 시점(0틱)에 이미 검사했지만 12틱 사이에 죽거나·제거되거나·보호 대상이 되거나·
     * 도망칠 수 있다. 그래서 <b>지연 피해를 만들지 않도록</b> 여기서 다시 본다 — 생존·제거·보호·
     * 바닐라 공격 가능 판정은 T12 의 {@code isValidCombatTarget} 하나로, 사거리는 방송 때와 같은
     * {@link #reachSqr} 로. 둘 중 하나라도 어긋나면 이번 회차는 <b>빗나감</b>이고 피해가 없다.
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
        if (!this.mob.isValidCombatTarget(this.target)) {
            return;                             // 죽음·제거·보호 대상·바닐라 거절 → 피해 없음
        }
        if (this.mob.distanceToSqr(this.target) > reachSqr(this.target)) {
            return;                             // 12틱 사이에 벗어났다 → 빗나감
        }
        this.mob.doHurtTarget(this.target);
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.target = null;
        this.ticks = -1;
        this.approach = 0;
        this.repath = 0;
    }
}
