package com.wardengirl.entity;

import com.wardengirl.anim.AnimRegistry;
import com.wardengirl.anim.MeleeDebug;
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
     * <p>선딜이 12틱이던 때에는, 넉백 저항이 0인 저스택 워든걸이 대상의 반격에 맞아 공중으로
     * 떠오른 채 12틱을 흘려보내 <b>미스의 92%</b>가 발생했다(실측: 미스 회차의 92%가 창 안에서
     * 피격·공중, 워든걸 이동 1.80블록 대 타격 회차 0.43블록). 그래서 클립 자체를 다시 타이밍
     * 조정해 충돌을 앞당겼다 — 사거리를 넓히거나 저항 수치를 바꾸지 않았다.
     *
     * <p>재조정한 {@code attack} 클립(여전히 0.9초 = 18틱)의 실측 키값. 충돌 근처 6~10틱은
     * 정수 틱마다 키가 있어 보간이 최저점을 옮길 수 없다.
     *
     * <pre>
     *   틱     0     1     4     5     6     7     8     9    10    12    14    16    18
     *   팔 x   0   −14   150   158   120    55    16    22    38    26    12     4     0
     *   몸 x   0    −3     8   9.5     7    −6   −15   −13    −8    −5    −2  −0.5     0
     * </pre>
     *
     * 5틱에 최고로 들어올렸다가 8틱에서 <b>팔이 들어올림 이후 첫 국소최저(16°)</b>가 되고
     * <b>몸통도 같은 틱에 최대 전방 기울임(−15°)</b>이다. 9틱부터 22 → 38 로 되돌아오는 반동
     * 구간이므로 8틱이 화면에서 "맞았다"로 보이는 순간이다. 1틱의 −14° 는 예비 젖힘이지
     * 내려찍기가 아니다.
     *
     * <p>무적 틱과도 어긋나지 않는다. 타격 간격은 방송 간격과 같은 19틱인데
     * {@code LivingEntity.hurt} 는 {@code invulnerableTime > 10} 일 때만 감쇠 경로로 가고
     * (성공 시 20으로 설정, 매 틱 1씩 감소), 19틱 뒤에는 1이 되어 있으므로 <b>매 회차가 전액</b>
     * 들어간다.
     *
     * <p><b>현재 실제로 쓰이는 값은 {@link MeleeDebug#windup()} 이다</b> — 체감 조절용 임시
     * 명령이 이 상수를 기본값으로 삼아 덮어쓴다. 임시 도구를 제거하면 {@link #motionWindup} 을
     * 이 상수로 되돌린다.
     */
    public static final int HIT_TICK = 8;

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

    /**
     * <b>타격 판정 거리만</b> 쓰는 제곱값. 1.8블록 = 3.24 이며 시험 적용이다.
     *
     * <p>공격 <b>시작</b> 사거리와 접근·추적 기준은 {@link #reachSqr} 그대로다(0.6/0.6 이면 2.04,
     * 즉 1.4283블록). 둘을 나눈 이유는 하나다 — 저스택 워든걸은 넉백 저항이 0이라 대상의 반격에
     * 공중으로 떠오르고, 스윙한 뒤 {@link #HIT_TICK}까지 8틱 동안 밀려나 실측 미스 51회가
     * <b>전부</b> 공중 상태였다. 미스 거리는 최소 1.4418 · 중앙값 1.91 · 최대 2.6653 이었다.
     * 시작 거리까지 1.8로 넓히면 멀리서 모션을 시작해 허공을 때리는 그림이 되므로 넓히지 않는다.
     *
     * <p>거리는 {@code Mob.distanceToSqr(Entity)} 로 재며 시작 판정과 같은 척도다 —
     * 중심 간 거리의 제곱이고, 시작 시점 좌표나 과거 거리를 쓰지 않는다.
     */
    private static final double STRIKE_REACH_SQR = 3.24D;

    /**
     * 재경로 최소 간격과 변동폭. 바닐라 {@code MeleeAttackGoal} 의
     * {@code ticksUntilNextPathRecalculation = 4 + random(7)} 와 같다 — 고정 10틱이던 때에는
     * 경로가 "도착"으로 끝난 뒤 최대 10틱을 그대로 서 있었다(실측 정지 구간 14개 · 83틱 ·
     * 접근 틱의 15.5%).
     */
    private static final int REPATH_MIN = 4;
    private static final int REPATH_SPREAD = 7;

    /** 경로 생성이 실패했을 때 다음 시도까지 더 기다리는 틱. 바닐라와 같은 값이다. */
    private static final int REPATH_FAIL_PENALTY = 15;

    /** 마지막 재경로가 기준으로 삼은 대상 위치에서 이만큼 움직이면 즉시 다시 경로를 낸다. */
    private static final double REPATH_TARGET_MOVE = 1.0D;

    /**
     * 경로가 끝났는데 아직 사거리 밖일 때 다음 시도까지 기다리는 상한(틱). 즉시 매 틱
     * 재경로하면 A* 를 매 틱 돌리게 되므로 상한만 낮춘다 — 정지가 최대 10틱에서 이 값으로 준다.
     */
    private static final int STALL_RETRY = 2;

    /**
     * 이 회차의 선딜레이와 모션 종료 틱. 회차가 <b>시작될 때 한 번</b> 읽어 고정한다 — 진행 중인
     * 공격에 임시 조절값을 소급 적용하지 않는다.
     *
     * <p>고정하는 것이 안전한 이유는 두 가지다. 첫째, 비교 대상이 카운터 밑에서 움직이지 않으므로
     * {@code ticks == motionWindup} 이 한 회차에 정확히 한 번만 성립한다 — 재생 중에 값을 낮추면
     * 이미 지나간 틱과 같아질 일이 없고, 높이면 종료 틱을 넘겨 아예 타격이 사라질 일이 없다.
     * 둘째, 클라이언트는 공격 시작 시점에 {@code EVENT_ATTACK} 한 번만 받으므로 재생 중 서버
     * 타이밍을 바꾸면 화면과 서버가 그 회차 내내 어긋난다.
     *
     * <p>{@link #motionEnd} 는 {@code max(클립 18틱, 선딜)} 이다. 선딜이 클립보다 길면 값을 몰래
     * 깎지 않고 <b>실제로 그 틱까지 모션 상태를 유지</b>한 뒤 타격한다 — 화면의 클립은 18틱에
     * 끝나 있고 피해는 그 뒤에 들어가는, 설정한 그대로의 의미다. 그 사이 접근·재경로는 계속
     * 돌고({@link #updateApproach()} 가 분기 앞에 있다) 다음 공격 시작만 늦어진다. 시작 간격은
     * {@link #MELEE_COOLDOWN} 틱보다 짧아지지 않는다 — 늘어날 뿐이다.
     */
    private int motionWindup = MeleeDebug.DEFAULT_WINDUP;
    private int motionEnd = (int) AnimRegistry.ATTACK_LENGTH_TICKS;

    /** 재생 나이(틱). 음수면 아직 접근 중이다. */
    private int ticks = -1;
    private int approach;
    /** 다음 재경로까지 남은 틱. */
    private int repath;
    /** 마지막 재경로 시점의 대상 위치. 대상이 여기서 1블록 이상 벗어나면 즉시 재경로한다. */
    private double pathedX;
    private double pathedY;
    private double pathedZ;

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
        this.repath = 0;                        // 첫 틱에 즉시 경로를 낸다
        this.pathedX = Double.NaN;
        this.pathedY = Double.NaN;
        this.pathedZ = Double.NaN;
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        // 접근 갱신은 모션 분기보다 <b>앞</b>이다. 뒤에 두었을 때에는 모션 재생 중 조기
        // return 때문에 재경로가 아예 실행되지 않았다(실측 재경로 63회 중 모션 중 5회).
        // 그래서 넉백으로 벌어진 거리를 18틱 내내 방치했다.
        updateApproach();
        if (this.ticks >= 0) {
            this.ticks++;
            if (this.ticks == this.motionWindup) {
                strike();                       // 모션당 정확히 한 번. 아래 주석 참고.
            }
            if (this.ticks < this.motionEnd) {
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
            this.motionWindup = MeleeDebug.windup();
            this.motionEnd = Math.max((int) AnimRegistry.ATTACK_LENGTH_TICKS, this.motionWindup);
            this.mob.startMeleeCooldown(MELEE_COOLDOWN);
            this.mob.level().broadcastEntityEvent(this.mob, WardenGirlEntity.EVENT_ATTACK);
            if (this.motionWindup == 0) {
                // 선딜 0 — 공격 시작과 <b>같은 서버 틱</b>에 판정한다. 다음 틱부터 ticks 는 1
                // 이상이므로 ticks == 0 이 다시 성립하지 않아 중복 피해가 구조적으로 없다.
                strike();
            }
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
    private void updateApproach() {
        if (this.repath > 0) {
            this.repath--;
        }
        boolean moved = Double.isNaN(this.pathedX)
                || this.target.distanceToSqr(this.pathedX, this.pathedY, this.pathedZ)
                        >= REPATH_TARGET_MOVE * REPATH_TARGET_MOVE;
        // 경로가 "도착"으로 끝났는데 아직 사거리 밖이면 다음 시도를 앞당긴다.
        if (this.mob.getNavigation().isDone()
                && this.mob.distanceToSqr(this.target) > reachSqr(this.target)
                && this.repath > STALL_RETRY) {
            this.repath = STALL_RETRY;
        }
        if (this.repath > 0 && !moved) {
            return;
        }
        this.pathedX = this.target.getX();
        this.pathedY = this.target.getY();
        this.pathedZ = this.target.getZ();
        this.repath = REPATH_MIN + this.mob.getRandom().nextInt(REPATH_SPREAD);
        boolean ok = this.mob.getNavigation().moveTo(this.target, PURSUE_SPEED);
        if (!ok) {
            this.repath += REPATH_FAIL_PENALTY;
        }
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
        if (!this.mob.isValidCombatTarget(this.target)) {
            return;                             // 죽음·제거·보호 대상·바닐라 거절 → 피해 없음
        }
        if (this.mob.distanceToSqr(this.target) > STRIKE_REACH_SQR) {
            return;                             // 타격 틱 사이에 벗어났다 → 빗나감
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
        this.pathedX = Double.NaN;
        this.pathedY = Double.NaN;
        this.pathedZ = Double.NaN;
    }
}
