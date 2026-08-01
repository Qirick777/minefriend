package com.wardengirl.entity;

import com.wardengirl.anim.AnimRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * T8 — 4.9 소닉붐. <b>임시 검증용이며 쉽게 제거하도록 격리했다.</b>
 *
 * <h2>제거 방법</h2>
 *
 * 이 파일과 {@code WardenGirlEntity.registerGoals()} 의 {@code addGoal(2, ...)} 한 줄이면 된다.
 *
 * <h2>대상은 이 Goal 이 고르지 않는다 (T15)</h2>
 *
 * {@code targetSelector} 의 {@link WardenGirlTargetGoal} 이 고른 {@code mob.getTarget()} 을
 * <b>읽기만</b> 한다. 주변을 훑는 코드는 없다.
 *
 * <h2>바닐라 재사용</h2>
 *
 * 길이 60틱, 방출 34틱, 수평 15 · 수직 20, {@code ParticleTypes.SONIC_BOOM} 빔,
 * {@code WARDEN_SONIC_CHARGE} / {@code WARDEN_SONIC_BOOM} 은 모두 바닐라
 * {@code ai.behavior.warden.SonicBoom} 의 값과 경로를 그대로 옮긴 것이다. 빔 생성 루프도
 * 바닐라 것과 같은 식이다.
 *
 * <h2>피해·넉백·다중 판정 (T16)</h2>
 *
 * 방출 틱(age 34)에 바닐라 {@code DamageSources.sonicBoom} 으로 피해를 주고 바닐라 워든과
 * 같은 넉백을 건다. 피해량만 워든걸 자신의 강화 스택에서 나온다
 * ({@code WardenGirlEntity.getSonicDamage()}). 대상은 하나가 아니라 발사점~종점 선분을 축으로
 * 하는 반경 {@value #CAPSULE_RADIUS} 캡슐 안의 <b>유효 대상 전부</b>이며 각각 정확히 한 번씩
 * 맞는다. 자세한 것은 {@link #strike}.
 *
 * <h2>여전히 넣지 않은 것</h2>
 *
 * 새 패킷, 새 NBT, 새 SynchedEntityData, 동기화 각도, 클라이언트 피해 판정, 별도 투사체 엔티티.
 * 적중 판정은 전부 서버에서 한다.
 */
public class WardenGirlSonicBoomGoal extends Goal {

    /** 소닉붐 <b>전용</b> 쿨다운. 방출(34틱) 시점부터 센다 — 시작 간격은 34 + 100 = 134틱. */
    public static final int SONIC_COOLDOWN = 100;

    /** 바닐라 {@code SonicBoom} 과 같은 수평·수직 사거리. T15 에서 여기로 옮겼다. */
    public static final double RANGE_XZ = 15.0D;
    public static final double RANGE_Y = 20.0D;

    /** 군중으로 보는 최소 활성 위협 수. */
    public static final int CROWD = 3;

    /** 2차 설계서 9.2 — 빔 선분을 축으로 하는 피해 판정 반경. */
    public static final double CAPSULE_RADIUS = 1.0D;

    /**
     * 바닐라 워든 소닉의 넉백 수치다. {@code SonicBoom} 이
     * {@code push(dir.x*2.5*r, dir.y*0.5*r, dir.z*2.5*r)} 를 쓰고 {@code r} 은
     * {@code 1 - KNOCKBACK_RESISTANCE} 다(바이트코드 확인). 그대로 재사용한다.
     */
    private static final double KNOCKBACK_HORIZONTAL = 2.5D;
    private static final double KNOCKBACK_VERTICAL = 0.5D;

    /**
     * 소닉붐 사거리 안인가. 바닐라 {@code Warden.closerThan(target, 15, 20)} 을 그대로 쓴다.
     *
     * <p>{@code Entity.closerThan} 은 두 축 모두 <b>엄격 부등호</b>다 —
     * {@code lengthSquared(dx,dz) < square(15)} 이고 {@code square(dy) < square(20)}
     * (바이트코드 확인). 즉 실제 경계는 수평 {@code < 15} · 수직 {@code < 20} 이며 정확히
     * 15.0 · 20.0 은 사거리 <b>밖</b>이다. 사거리를 바꾸지 말라는 요구에 따라 그대로 둔다.
     */
    private boolean inBoomRange(LivingEntity target) {
        return this.mob.closerThan(target, RANGE_XZ, RANGE_Y);
    }

    /**
     * 방출 뒤 근접을 막는 시간. 34 + 26 = 60, 즉 <b>소닉 모션이 끝나는 순간</b> 근접이 풀린다.
     * 회복 구간에 주먹이 끼어드는 것만 막고 별도의 공백은 만들지 않는다.
     */
    public static final int MELEE_LOCK_AFTER_EMIT = 26;

    /**
     * T21 — 이번 회차가 어느 규칙으로 시작했는가. <b>서버 Goal 내부 runtime 상태</b>다 —
     * NBT 도, SynchedEntityData 도, 패킷도 없다. {@link #start()} 에서 확정하고 실행 도중
     * 바꾸지 않는다. {@link #stop()} 이 {@link CastMode#NONE} 으로 되돌린다.
     */
    private enum CastMode { NONE, NORMAL, RETREAT }

    private final WardenGirlEntity mob;
    private LivingEntity target;
    /** 재생 나이(틱). 음수면 재생 중이 아니다. */
    private int ticks = -1;
    private CastMode mode = CastMode.NONE;
    /**
     * RETREAT 회차에서 {@link #tick()} 이 안전을 잃었다고 판단했는가.
     *
     * <p>{@code goalSelector} 는 {@code canContinueToUse} 를 <b>두 틱에 한 번</b>만 부르는데
     * 설계서 4.3.7 은 <b>매 서버틱</b> 재검사를 요구한다. {@code tick()} 은
     * {@code requiresUpdateEveryTick()} 덕에 매 틱 도므로 거기서 판정하고, 이 깃발이 다음
     * 평가에서 Goal 을 끝낸다. 깃발이 선 틱에는 age 도 올리지 않고 방출도 하지 않으므로
     * 피해는 그 틱에 이미 막혀 있다.
     */
    private boolean retreatUnsafe;

    public WardenGirlSonicBoomGoal(WardenGirlEntity mob) {
        this.mob = mob;
        // T21.5 — MOVE 를 놓았다. 소닉은 이제 상체·조준·방출만 맡고, 실제 이동은 후퇴
        // ({@link WardenGirlRetreatGoal}) 또는 소닉 추적({@link WardenGirlSonicPursuitGoal}) 이
        // 정한다. LOOK 은 그대로 잡는다 — priority 1 이라 근접(4)·시선(7·8) 이 뺏지 못하고,
        // 그 한 가지가 "소닉 중 근접 금지" 를 flag 만으로 보장한다.
        setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    /** T21.5 — 지금 충전·재생 중인가. 소닉 추적 Goal 이 읽는다. */
    boolean isCasting() {
        return this.ticks >= 0;
    }

    /** T21.5 — 이번 회차가 NORMAL 인가. 모드는 {@link #start()} 이후 바뀌지 않는다. */
    boolean isNormalCast() {
        return this.mode == CastMode.NORMAL;
    }

    /** T21.5 — 이번 회차가 겨누고 있는 대상. 추적도 방출과 같은 대상을 쫓는다. */
    @javax.annotation.Nullable
    LivingEntity castTarget() {
        return this.target;
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
     *   유효한 현재 target && T14 시작 목줄 && 소닉 쿨다운·행동 상태 통과
     *   && target 이 수평 15 · 수직 20 안
     *   && ( 활성 위협 3 이상 || 근접으로 도달 불가 )
     * </pre>
     *
     * <p>"근접이 불가능" 은 거리 이력이 아니라 두 가지 <b>즉시 판정</b>이다 — 이미 근접 사거리
     * 안이면 T7 이 처리하므로 양보하고, 사거리 밖이면 경로가 아예 없을 때(벽 너머·높은 곳)만
     * 소닉붐이다. 상태를 남기지 않는다.
     *
     * <p>T15.5 — 다수전 조건이 <b>종류가 아니라 위협 여부</b>로 돌아왔다. T15 에서 뺐던 조건은
     * 주변의 {@code Zombie.class} 를 세는 코드여서 제거 대상이었다. 지금 세는 것은
     * {@link #activeThreats} 가 정의하는 "이 워든걸이나 소유자를 실제로 노리고 있는 개체" 이며,
     * 어떤 종류인지는 보지 않고 {@code setTarget} 도 하지 않는다.
     *
     * <p><b>시야는 조건이 아니다.</b> 벽 뒤의 적을 치는 것이 이 기능의 목적이므로
     * {@code hasLineOfSight} 같은 판정을 넣지 않는다. 방출 34틱 · 길이 60틱 · 쿨다운 100틱과
     * 사거리 15/20 도 그대로다.
     */
    @Override
    public boolean canUse() {
        // 근접 쿨다운(19틱)은 근접 모션 길이(18틱)와 사실상 같으므로, 그것을 그대로 재생 중
        // 판정으로 쓴다. 재생 중에 소닉이 끼어들어 클립을 덮어쓰지 않고, 모션이 끝나는 틱에는
        // 둘 다 풀려 우선순위(T8 2 < T7 3)대로 소닉이 먼저 선택될 수 있다. 새 타이머는 없다.
        if (this.mob.isSonicOnCooldown() || this.mob.isMeleeOnCooldown() || blocked()) {
            return false;
        }
        // T15 — 주변을 훑지 않는다. targetSelector 가 골라 둔 대상만 본다.
        LivingEntity t = this.mob.getTarget();
        if (t == null || !this.mob.isValidCombatTarget(t)) {
            return false;
        }
        // T15.5 — 사거리 판정이 <b>두 갈래 모두</b>의 선행 조건이다. 사거리 밖 대상에게는
        // 다수전이든 근접 불가든 충전을 시작하지 않는다.
        if (!inBoomRange(t)) {
            return false;
        }
        // T14 — 근접과 같은 소유자 목줄을 쓴다(시작: 워든걸-소유자 12, 대상-소유자 16).
        // 야생이거나 소유자를 찾을 수 없으면 제한하지 않는다.
        if (!this.mob.canStartLeashedCombat(t)) {
            return false;
        }
        // T21 — 후퇴 중이면 기존 다수전·근접불가 방아쇠 대신 안전 판정을 쓴다. 위협이
        // 하나뿐이어도 방출 시점까지 아무도 닿지 못하면 쏜다. 후퇴가 아니면 T15.5 그대로다.
        boolean use = this.mob.isRetreating()
                ? WardenGirlRetreatSonicSafety.evaluate(this.mob, t,
                        WardenGirlRetreatSonicSafety.requiredArrival(0)).safe()
                : activeThreats(t) >= CROWD || meleeUnreachable(t);
        if (use) {
            this.target = t;
        }
        return use;
    }

    /**
     * T15.5 — 근접으로 <b>닿을 수 없는가</b>. 벽 뒤, 올라갈 수 없는 발판, 부분 경로가 전부
     * 여기 하나로 판정된다.
     *
     * <p>{@code moveTo} 의 반환값은 쓰지 않는다 — 바닐라는 닿지 못하는 대상에도 부분 경로를
     * 만들고 {@code true} 를 돌려준다. 실제 {@code Path} 의 도달 여부를 본다(기둥 위 좀비
     * 실측: {@code n=1, reach=false, distToTgt=7.00}).
     *
     * <p>{@code null} 도 도달 불가로 센다. {@code createPath} 는 {@code canUpdatePath()} 가
     * false 인 틱(땅에 닿지 않았고 액체·탑승도 아닐 때)에 A* 를 돌리지 않고 {@code null} 을
     * 돌려주므로, 공중에 뜬 순간에는 "계산하지 않았다" 와 "길이 없다" 가 구분되지 않는다.
     * 요구사항이 {@code path == null || !path.canReach()} 로 확정됐으므로 그대로 따른다.
     */
    private boolean meleeUnreachable(LivingEntity t) {
        if (this.mob.isWithinMeleeAttackRange(t)) {
            return false;                       // 근접 사거리 안 — T7 이 한다
        }
        Path path = this.mob.getNavigation().createPath(t, 0);
        return path == null || !path.canReach();
    }

    /**
     * T15.5 — 활성 위협 수. <b>종류를 보지 않는다.</b>
     *
     * <p>현재 대상은 정의상 위협이므로 1로 센다. 그 밖에는 소닉 사거리 안에서 이 워든걸이나
     * 소유자를 <b>실제로 노리고 있는</b>({@code getTarget()}) 개체만 센다. 세기만 할 뿐
     * {@code setTarget} 은 하지 않으므로 T15 가 없앤 자동 어그로가 되살아나지 않는다.
     */
    private int activeThreats(LivingEntity current) {
        Player owner = this.mob.serverOwner();
        AABB box = this.mob.getBoundingBox().inflate(RANGE_XZ, RANGE_Y, RANGE_XZ);
        int n = 0;
        for (LivingEntity e : this.mob.level().getEntitiesOfClass(LivingEntity.class, box,
                x -> x != this.mob && this.mob.isValidCombatTarget(x) && inBoomRange(x))) {
            if (e == current) {
                n++;
            } else if (e instanceof Mob m
                    && (m.getTarget() == this.mob || (owner != null && m.getTarget() == owner))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 충전 유지 판정. {@code hurtTime} 은 여전히 보지 않는다(T8 규칙 유지) — 대신 죽음·제거·
     * 보호 대상 전환을 T12 공통 판정으로 함께 본다.
     */
    @Override
    public boolean canContinueToUse() {
        // T14 — 유지 목줄을 넘으면 충전 중이라도 끝낸다. stop() 이 ticks 를 −1 로 되돌리므로
        // "진행 중인 소닉 준비 취소" 가 여기서 함께 일어난다. 방출 전에 끊기면 피해도 없다.
        if (!this.mob.isValidCombatTarget(this.target) || cancelled()
                || !this.mob.canKeepLeashedCombat(this.target)
                || this.ticks >= AnimRegistry.SONIC_LENGTH_TICKS) {
            return false;
        }
        if (this.mode == CastMode.RETREAT) {
            // 후퇴가 풀리면 취소한다. 이미 시작한 RETREAT 회차를 NORMAL 로 바꾸지 않는다.
            return this.mob.isRetreating() && !this.retreatUnsafe;
        }
        // T20 — NORMAL 회차는 후퇴에 들어가는 순간 취소된다. RETREAT 로 갈아타지 않는다.
        return !this.mob.isRetreating();
    }

    @Override
    public void start() {
        // 모드는 여기서 확정한다. canUse 와 같은 goalSelector.tick() 안이라 상태가 같다.
        this.mode = this.mob.isRetreating() ? CastMode.RETREAT : CastMode.NORMAL;
        this.retreatUnsafe = false;
        this.ticks = 0;
        // T21.5 — 여기 있던 getNavigation().stop() 을 지웠다. 소닉이라는 이유만으로 이동을
        // 끊지 않는다. 제자리에 서야 하는 상황(대상이 이미 근접 사거리 안, 경로 없음)은
        // 소닉 추적 Goal 이 스스로 판단하고, 후퇴 중이면 후퇴 Goal 이 계속 움직인다.
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
        // 설계서 4.3.7 — RETREAT 회차는 방출 전 <b>매 서버틱</b> 다시 본다. {@code <=} 인 것이
        // 4.3.7 이 요구하는 "방출 직전 최종 게이트" 다 — age 34 인 틱에도 한 번 더 걸린다.
        if (this.mode == CastMode.RETREAT && this.ticks <= AnimRegistry.SONIC_EMIT_TICK
                && !WardenGirlRetreatSonicSafety.evaluate(this.mob, this.target,
                        WardenGirlRetreatSonicSafety.requiredArrival(this.ticks)).safe()) {
            // 이 틱에는 age 를 올리지도, 방출하지도 않는다. 피해·넉백·파티클·소리·사건 전부 없다.
            this.retreatUnsafe = true;
            return;
        }
        if (this.ticks == AnimRegistry.SONIC_EMIT_TICK) {
            emit();
        }
        this.ticks++;
    }

    /**
     * T16 — 방출. 빔 연출은 바닐라 {@code SonicBoom} 그대로이고, 여기에 <b>실제 피해와
     * 넉백</b>, 그리고 설계서 9.2 의 <b>반경 1.0 캡슐 다중 판정</b>이 붙는다.
     *
     * <p>발사점과 종점은 바닐라와 같다 — {@code position().add(0,1.6,0)} 에서 대상의
     * {@code getEyePosition()} 까지다(바이트코드 확인). 파티클은 바닐라처럼 대상 너머까지
     * 그려지지만 <b>피해 판정은 그 유한 선분만</b> 쓴다.
     */
    private void emit() {
        if (!(this.mob.level() instanceof ServerLevel server)) {
            return;
        }
        // 방출 직전 최종 재검사. 여기서 걸리면 <b>피해 판정 전체</b>를 취소한다 — 이전 위치를
        // 기억해 빈 곳으로 쏘거나 주변 개체만 때리는 경로는 없다. canContinueToUse 는
        // goalSelector 가 두 틱에 한 번만 평가하므로 방출 틱과 어긋날 수 있어 여기서 다시 본다.
        LivingEntity aim = this.target;
        if (aim == null || !this.mob.isValidCombatTarget(aim)
                || !this.mob.canKeepLeashedCombat(aim) || !inBoomRange(aim)) {
            return;
        }
        Vec3 from = this.mob.position().add(0.0D, 1.6D, 0.0D);
        Vec3 to = aim.getEyePosition();
        Vec3 delta = to.subtract(from);
        Vec3 dir = delta.normalize();
        int steps = Mth.floor(delta.length()) + 7;
        for (int i = 1; i < steps; i++) {
            Vec3 p = from.add(dir.scale(i));
            server.sendParticles(ParticleTypes.SONIC_BOOM, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        this.mob.playSound(SoundEvents.WARDEN_SONIC_BOOM, 3.0F, 1.0F);
        strike(server, from, to, dir);
        // 두 쿨다운 모두 타격 순간부터 센다 — 근접이 공격 방송 시점에 거는 것과 같은 규칙이다.
        this.mob.startSonicCooldown(SONIC_COOLDOWN);
        this.mob.startMeleeCooldown(MELEE_LOCK_AFTER_EMIT);
    }

    /**
     * 설계서 9.2 — 선분 {@code from→to} 를 축으로 하는 반경 {@value #CAPSULE_RADIUS} 캡슐 안의
     * 유효 대상 <b>전부</b>에게 각각 한 번씩 피해와 넉백을 준다.
     *
     * <h3>기하 판정</h3>
     *
     * 후보는 선분을 감싸는 AABB 를 반경만큼 부풀려 <b>한 번만</b> 조회한다. 개체별 판정은
     * 바닐라 투사체와 같은 방식이다 — 대상의 bounding box 를 반경만큼 부풀리고 선분을
     * {@code clip} 한다. 점 중심이 아니라 <b>bounding box 를 고려</b>하며, {@code clip} 이
     * 선분 밖으로 나가지 않으므로 발사자 뒤로도 대상 너머로도 연장되지 않는다.
     * 발사점이 이미 부푼 상자 안이면 {@code clip} 이 비어 나오므로 {@code contains} 로 함께 본다
     * (바닐라 {@code ProjectileUtil.getEntityHitResult} 도 같은 예외 처리를 한다).
     *
     * <p>부푼 AABB 기준이므로 판정면은 정확한 원기둥이 아니라 <b>모서리가 각진 캡슐</b>이다.
     * 축에서 수직으로 잰 경계는 {@code 대상 반폭 + 1.0} 이 된다.
     *
     * <h3>벽·중복·감쇠</h3>
     *
     * 시야도 블록 충돌도 보지 않는다 — 벽 뒤도 맞는다. 조회 결과는 개체마다 하나뿐이라
     * 중복 피해가 구조적으로 없고, 첫 적중에서 멈추지 않으며 거리 감쇠도 없다.
     * 소유자와 같은 소유자의 워든걸은 {@code isValidCombatTarget} 에서 걸러지므로 피해도
     * 넉백도 받지 않고, 걸러질 뿐이라 <b>뒤쪽 대상을 막지도 않는다</b>.
     */
    private void strike(ServerLevel server, Vec3 from, Vec3 to, Vec3 dir) {
        float damage = (float) this.mob.getSonicDamage();
        AABB span = new AABB(from, to).inflate(CAPSULE_RADIUS);
        for (LivingEntity e : server.getEntitiesOfClass(LivingEntity.class, span,
                x -> x != this.mob && this.mob.isValidCombatTarget(x))) {
            AABB hull = e.getBoundingBox().inflate(CAPSULE_RADIUS);
            if (!hull.contains(from) && hull.clip(from, to).isEmpty()) {
                continue;                       // 캡슐 밖 — 선분 종점 너머와 뒤쪽이 여기서 빠진다
            }
            e.hurt(server.damageSources().sonicBoom(this.mob), damage);
            // 바닐라 워든과 같은 넉백이다 — 수평 2.5, 수직 0.5 에 각각 넉백 저항을 곱한다.
            double resist = 1.0D - e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            e.push(dir.x() * KNOCKBACK_HORIZONTAL * resist,
                    dir.y() * KNOCKBACK_VERTICAL * resist,
                    dir.z() * KNOCKBACK_HORIZONTAL * resist);
        }
    }

    @Override
    public void stop() {
        // 서버 target 은 지우지 않는다 — 쓰는 쪽은 WardenGirlTargetGoal 하나다. 여기서
        // ticks 를 −1 로 되돌리는 것이 "진행 중인 소닉 준비 취소" 그 자체다.
        this.target = null;
        this.ticks = -1;
        this.mode = CastMode.NONE;
        this.retreatUnsafe = false;
    }
}
