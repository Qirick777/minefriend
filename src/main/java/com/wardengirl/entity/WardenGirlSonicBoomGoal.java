package com.wardengirl.entity;

import com.wardengirl.anim.AnimRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * T8 — 4.9 소닉붐. <b>임시 검증용이며 쉽게 제거하도록 격리했다.</b>
 *
 * <h2>제거 방법</h2>
 *
 * 이 파일과 {@code WardenGirlEntity.registerGoals()} 의 {@code addGoal(2, ...)} 한 줄이면 된다.
 * 임시 좀비 규칙은 여기에 없다 — {@link WardenGirlHostiles} 한 곳에만 있다.
 *
 * <h2>바닐라 재사용</h2>
 *
 * 길이 60틱, 방출 34틱, 수평 15 · 수직 20, {@code ParticleTypes.SONIC_BOOM} 빔,
 * {@code WARDEN_SONIC_CHARGE} / {@code WARDEN_SONIC_BOOM} 은 모두 바닐라
 * {@code ai.behavior.warden.SonicBoom} 의 값과 경로를 그대로 옮긴 것이다. 빔 생성 루프도
 * 바닐라 것과 같은 식이다.
 *
 * <h2>넣지 않은 것</h2>
 *
 * 피해, 넉백, 방어구 관통, 고정 공격력, 성장 스탯, 새 패킷, 동기화 각도, 클라이언트 판정.
 * 바닐라가 방출부에서 하는 {@code hurt} 와 넉백은 의도적으로 빠져 있다.
 */
public class WardenGirlSonicBoomGoal extends Goal {

    /** 소닉붐 <b>전용</b> 쿨다운. 방출(34틱) 시점부터 센다 — 시작 간격은 34 + 100 = 134틱. */
    public static final int SONIC_COOLDOWN = 100;

    /**
     * 방출 뒤 근접을 막는 시간. 34 + 26 = 60, 즉 <b>소닉 모션이 끝나는 순간</b> 근접이 풀린다.
     * 회복 구간에 주먹이 끼어드는 것만 막고 별도의 공백은 만들지 않는다.
     */
    public static final int MELEE_LOCK_AFTER_EMIT = 26;

    private final WardenGirlEntity mob;
    private LivingEntity target;
    /** 재생 나이(틱). 음수면 재생 중이 아니다. */
    private int ticks = -1;

    public WardenGirlSonicBoomGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** 시작을 막는 상태. 충전 <b>도중</b>에는 {@link #cancelled()} 를 쓴다. */
    private boolean blocked() {
        return this.mob.hurtTime != 0 || cancelled();
    }

    /**
     * 이미 시작한 충전을 <b>취소</b>하는 상태. {@code hurtTime} 은 여기 없다.
     *
     * <p>{@link #blocked()} 를 그대로 쓰던 때에는 충전 중 한 대만 맞아도 {@code hurtTime} 이
     * 올라가 {@code canContinueToUse} 가 false 가 되고 {@link #stop()} 이 age 를 −1 로 지웠다.
     * 무적 틱이 풀리면 {@code canUse} 가 다시 참이 되어 {@link #start()} 가 엔티티 사건을 또
     * 방송하므로, 서버 age 와 화면 클립이 <b>둘 다 0부터</b> 다시 시작했다. 여럿에게 둘러싸이면
     * 방출 틱에 영영 도달하지 못한다.
     *
     * <p>부호 실측과 탑승은 그대로 취소 조건이다 — 피격과 달리 충전을 이어갈 수 있는 상태가
     * 아니다. 사망은 여기서 명시적으로 끊는다.
     */
    private boolean cancelled() {
        return !this.mob.isAlive() || this.mob.isSignTest() || this.mob.isPassenger();
    }

    /**
     * 발동식.
     *
     * <pre>
     *   소닉 쿨다운 없음 && 근접 모션 중 아님 && 실행 가능 상태 && 대상이 수평 15 · 수직 20 안
     *   && ( 전방 군중 3마리 이상  ||  근접이 불가능 )
     * </pre>
     *
     * <p>"근접이 불가능" 은 거리 이력이 아니라 두 가지 <b>즉시 판정</b>이다 — 이미 근접 사거리
     * 안이면 T7 이 처리하므로 양보하고, 사거리 밖이면 경로가 아예 없을 때(벽 너머·높은 곳)만
     * 소닉붐이다. 상태를 남기지 않는다.
     */
    @Override
    public boolean canUse() {
        // 근접 쿨다운(19틱)은 근접 모션 길이(18틱)와 사실상 같으므로, 그것을 그대로 재생 중
        // 판정으로 쓴다. 재생 중에 소닉이 끼어들어 클립을 덮어쓰지 않고, 모션이 끝나는 틱에는
        // 둘 다 풀려 우선순위(T8 2 < T7 3)대로 소닉이 먼저 선택될 수 있다. 새 타이머는 없다.
        if (this.mob.isSonicOnCooldown() || this.mob.isMeleeOnCooldown() || blocked()) {
            return false;
        }
        LivingEntity t = WardenGirlHostiles.nearest(this.mob);
        if (t == null || !WardenGirlHostiles.inBoomRange(this.mob, t)) {
            return false;
        }
        this.target = t;
        if (WardenGirlHostiles.frontCrowd(this.mob) >= WardenGirlHostiles.CROWD) {
            return true;
        }
        float w = this.mob.getBbWidth();
        double meleeSqr = w * 2.0F * w * 2.0F + t.getBbWidth();
        if (this.mob.distanceToSqr(t) <= meleeSqr) {
            return false;                       // 근접 사거리 안 — T7 이 한다
        }
        // 도달 불가는 "경로가 있는데 대상에 닿지 못한다" 하나뿐이다.
        //
        // 바닐라 PathNavigation.createPath 는 닿지 못하는 대상에도 canReach()==false 인 부분
        // 경로를 돌려준다(기둥 위 좀비 실측: n=1, reach=false, distToTgt=7.00). null 만 보던
        // 첫 구현은 그래서 한 번도 발동하지 않았다.
        //
        // 반대로 null 은 "도달 불가" 가 아니라 "이번 틱에는 계산하지 않았다" 다 — createPath 는
        // canUpdatePath() 가 false 일 때(땅에 닿지 않았고 액체 속도 탑승 중도 아닐 때) A* 를
        // 돌리지 않고 그대로 null 을 반환한다. 스폰 직후가 정확히 그 상태여서, 평지 8블록 앞의
        // 접근 가능한 좀비에게 소닉이 오발됐다(실측 t=1 발동, 첫 접근이 t=136 까지 135틱 지연).
        // canUpdatePath() 는 protected 라 여기서 부를 수 없으므로 그 상태의 신호인 null 을
        // "발동하지 않음" 으로 읽는다. 땅에 닿는 다음 틱이면 정상 판정이 된다.
        Path path = this.mob.getNavigation().createPath(t, 0);
        return path != null && !path.canReach();
    }

    /**
     * 충전 유지 판정. {@code hurtTime} 은 여전히 보지 않는다(T8 규칙 유지) — 대신 죽음·제거·
     * 보호 대상 전환을 T12 공통 판정으로 함께 본다.
     */
    @Override
    public boolean canContinueToUse() {
        return this.mob.isValidCombatTarget(this.target) && !cancelled()
                && this.ticks < AnimRegistry.SONIC_LENGTH_TICKS;
    }

    @Override
    public void start() {
        this.ticks = 0;
        this.mob.getNavigation().stop();
        this.mob.level().broadcastEntityEvent(this.mob, WardenGirlEntity.EVENT_SONIC);
        this.mob.playSound(SoundEvents.WARDEN_SONIC_CHARGE, 3.0F, 1.0F);
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        // 엔티티 오버로드다. Vec3 판이던 때에는 position() 이 대상의 <b>발</b> 좌표라 같은 높이
        // 6블록 대상에도 서버 xRot 이 +14.31°(아래)로 고정됐고(눈 기준이면 +2.0°), 그 값이 60틱
        // 내내 유지되다가 클립의 body 젖힘이 풀리는 47~60틱에 드러나 고개가 지면을 향한 채
        // 복귀했다. LookControl.getWantedY 는 LivingEntity 에 눈높이를 쓰므로, 이 한 줄로
        // 시선 목표가 emit() 의 target.getEyePosition() 과 같아진다.
        this.mob.getLookControl().setLookAt(this.target);
        if (this.ticks == AnimRegistry.SONIC_EMIT_TICK) {
            emit();
        }
        this.ticks++;
    }

    /**
     * 바닐라 {@code SonicBoom} 방출부와 같은 빔이다 — 눈높이에서 대상 눈까지 정규화한 방향으로
     * 1블록 간격 파티클을 {@code floor(거리) + 7} 개까지 놓고 소리를 낸다. <b>피해와 넉백은 뺐다.</b>
     */
    private void emit() {
        if (!(this.mob.level() instanceof ServerLevel server)) {
            return;
        }
        // 방출 직전 마지막 재검사. T16 이 여기에 피해와 넉백을 붙이므로, 그때 조건을 새로
        // 쓰지 않도록 지금부터 이 한 줄이 관문이다. canContinueToUse 는 goalSelector 가
        // 두 틱에 한 번만 평가하므로 방출 틱과 한 틱 어긋날 수 있다 — 그 틈을 여기서 막는다.
        if (!this.mob.isValidCombatTarget(this.target)) {
            return;                             // 이번 방출 회차를 시작하지 않는다.
        }
        Vec3 from = this.mob.position().add(0.0D, 1.6D, 0.0D);
        Vec3 delta = this.target.getEyePosition().subtract(from);
        Vec3 dir = delta.normalize();
        int steps = Mth.floor(delta.length()) + 7;
        for (int i = 1; i < steps; i++) {
            Vec3 p = from.add(dir.scale(i));
            server.sendParticles(ParticleTypes.SONIC_BOOM, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        this.mob.playSound(SoundEvents.WARDEN_SONIC_BOOM, 3.0F, 1.0F);
        // 두 쿨다운 모두 타격 순간부터 센다 — 근접이 공격 방송 시점에 거는 것과 같은 규칙이다.
        this.mob.startSonicCooldown(SONIC_COOLDOWN);
        this.mob.startMeleeCooldown(MELEE_LOCK_AFTER_EMIT);
    }

    @Override
    public void stop() {
        this.target = null;
        this.ticks = -1;
    }
}
