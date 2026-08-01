package com.wardengirl.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AnimRegistry;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * WardenGirl entity — design doc Part 3.8 (phase 1 minimum spec).
 *
 * <p>Deliberately empty of behaviour. Phase 1 carries animation state only; AI goals, attack
 * resolution, damage, particles, sound and real movement are phase 2 scope (Part 2.5, Part 10.12).
 *
 * <p>{@link FloatGoal} is the exception the doc grants, purely to stop the mob drowning while it is
 * being observed. As of T3 the two vanilla <em>look</em> goals join it — see
 * {@link #registerGoals()} for why that is not a phase-2 leak.
 *
 * <p>As of T1 this implements {@link GeoEntity} so the GeckoLib renderer can drive it; T2 added the
 * C1 {@code vital} controller. C2 (locomotion) and C3 (action) are T5 and T6.
 */
public class WardenGirlEntity extends PathfinderMob implements GeoEntity {

    /**
     * Axis-verification state (design doc Part 4.0, P1-T1), encoded as {@code "<bone>:<axis>"} —
     * for example {@code "head:x"}. Empty string means the harness is off.
     *
     * <p>This is synched because the command runs on the server while the bone rotation is applied
     * on the client. It is a debug harness for T1 only and holds no gameplay meaning.
     */
    private static final EntityDataAccessor<String> DATA_AXIS_TEST =
            SynchedEntityData.defineId(WardenGirlEntity.class, EntityDataSerializers.STRING);

    /** T5 임시 — json 부호 실측 애니메이션을 재생 중인가. See {@link AnimRegistry#SIGN_TEST}. */
    private static final EntityDataAccessor<Boolean> DATA_SIGN_TEST =
            SynchedEntityData.defineId(WardenGirlEntity.class, EntityDataSerializers.BOOLEAN);

    /** T5 2라운드 — C3 직접 평가로 재생할 클립 이름. 빈 문자열이면 재생 없음. */
    private static final EntityDataAccessor<String> DATA_ACTION_CLIP =
            SynchedEntityData.defineId(WardenGirlEntity.class, EntityDataSerializers.STRING);

    /** 재생 트리거 일련번호. 같은 클립을 다시 재생하는 것과 계속 재생 중인 것을 구분한다. */
    private static final EntityDataAccessor<Integer> DATA_ACTION_SEQ =
            SynchedEntityData.defineId(WardenGirlEntity.class, EntityDataSerializers.INT);

    /**
     * 4.12 킁킁 시작 통지. <b>표준 엔티티 사건</b>이고 전용 패킷이 아니다.
     *
     * <p>바닐라가 이 상속 계통에서 쓰는 값은 {@code Entity} 53, {@code LivingEntity}
     * 3 / 29 / 30 / 46~52 / 54 / 55 / 60, {@code Mob} 20 이다 — 세 클래스의
     * {@code handleEntityEvent} switch 를 바이트코드에서 전부 확인했다. 61 은 비어 있고,
     * 이 프로젝트에서 {@code broadcastEntityEvent} 를 쓰는 다른 곳도 없다.
     *
     * <h2>엔티티 계통만 보면 부족하다 — 21 / 35 / 63 은 쓸 수 없다</h2>
     *
     * <p>{@code ClientPacketListener.handleEntityEvent} 가 {@code entity.handleEntityEvent} 를
     * 부르기 <b>전에</b> {@code lookupswitch {21, 35, 63}} 로 세 값을 가로챈다 — 각각
     * {@code GuardianAttackSoundInstance}, 토템 파티클, {@code SnifferSoundInstance} 다.
     * 가로챈 뒤 {@code goto} 로 빠져나가므로 우리 {@code handleEntityEvent} 는 아예 호출되지
     * 않고, 63 은 {@code (Sniffer) entity} 캐스트에서 {@code ClassCastException} 까지 낸다.
     * 실제로 63 을 쓴 첫 구현이 그렇게 실패했다. 61 · 62 · 64 는 이 세 값이 아니다.
     */
    public static final byte EVENT_SNIFF = 61;

    /** 4.8 기본 공격 시작 통지. 61 과 같은 이유로 62 도 비어 있다. */
    public static final byte EVENT_ATTACK = 62;

    /** 4.9 소닉붐 시작 통지. 63 은 {@code ClientPacketListener} 가 가로채므로 64 다. */
    public static final byte EVENT_SONIC = 64;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public WardenGirlEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    /**
     * Health 20, movement speed 0.23 — design doc Part 3.8.
     *
     * <p>The speed was 0.3 in the original draft; that was a spec error (0.3 is far above a
     * vanilla walker — the player is ~0.1). Corrected to 0.23, matching a zombie, by explicit
     * approval. The design doc has been updated to match.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                // 2차 설계서 5.1 의 0스택 기본값. 스택이 붙으면 개체의 AttributeInstance
                // base value 만 덮어쓴다 — 타입 전체의 기본값은 여기 이 한 벌뿐이다.
                .add(Attributes.MAX_HEALTH, PowerStats.HEALTH_BASE)
                // createMobAttributes 에는 ATTACK_DAMAGE 가 없다(FOLLOW_RANGE 와
                // ATTACK_KNOCKBACK 만 얹는다). 직접 넣지 않으면 getAttribute 가 null 이다.
                .add(Attributes.ATTACK_DAMAGE, PowerStats.ATTACK_BASE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.23D);
    }

    /**
     * Float, plus the two vanilla look goals. Design doc 4.6 / P1-T3.
     *
     * <p><b>The look behaviour is vanilla's and stays vanilla's.</b> {@code LookAtPlayerGoal} and
     * {@code RandomLookAroundGoal} drive {@code yHeadRot}, and {@code LivingEntity.tickHeadTurn}
     * already forces the body round once head and body differ by more than 50° and clamps the
     * difference at ±75°. None of that is reimplemented here — T3 only <em>renders</em> the result,
     * by adding it to the {@code head} bone (design doc 4.6, 선택지 A).
     *
     * <p>These are not the phase-2 AI goals Part 2.5 defers — there is still no pathfinding, no
     * targeting and no combat. They are here because the 4.5 headgear spring takes head rotation as
     * its input, and a head that never moves cannot verify a spring.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // T21 — 소닉이 후퇴보다 위로 올라왔다. 안전 판정을 통과한 후퇴 소닉만 후퇴의 MOVE 를
        // 잠시 선점하고, 안전하지 않으면 소닉 canUse 가 false 라 후퇴가 계속 돈다. 일반 소닉은
        // 예전처럼 근접보다 우선이다.
        // T20 — 4.2 저체력 후퇴. MOVE 하나만 잡으므로 소닉 추적(3)·근접(4)·추종(5)·배회(6) 를 전부
        // 선점하면서 시선 Goal 은 막지 않는다.
        this.goalSelector.addGoal(2, new WardenGirlRetreatGoal(this));
        // 4.12 킁킁. 플래그가 없으므로 두 시선 Goal 과 동시에 돈다 — 킁킁 중에도 고개는 계속
        // 움직인다. 우선순위는 시선보다 아래에 둔다.
        this.goalSelector.addGoal(9, new WardenGirlSniffGoal(this));
        // 4.8 근접. targetSelector 가 고른 대상에게만 걸어가 때린다 — T15 부터 이 Goal 은
        // 대상을 고르지 않는다.
        this.goalSelector.addGoal(4, new WardenGirlAttackGoal(this));
        // 4.9 소닉붐. T21 부터 우선순위 1 이다 — 위 주석 참조.
        // T21.5 — 이제 LOOK 만 잡는다. priority 1 의 LOOK 하나가 근접(4)·시선(7·8) 을 막으므로
        // "소닉 중 근접 금지" 는 그대로다. 이동은 아래 두 Goal 이 나눠 맡는다.
        WardenGirlSonicBoomGoal sonic = new WardenGirlSonicBoomGoal(this);
        this.goalSelector.addGoal(1, sonic);
        // T21.5 — NORMAL 소닉 회차의 이동. 후퇴(2) 아래, 근접(4) 위다. 후퇴가 돌고 있으면
        // MOVE 를 얻지 못하고, 조건 자체도 RETREAT 회차를 배제한다.
        this.goalSelector.addGoal(3, new WardenGirlSonicPursuitGoal(this, sonic));
        // T13 — 7.2 일반 추종. 전투(2·3·4)보다 아래, 배회보다 위다. MOVE 만 잡으므로 전투가
        // 돌고 있으면 navigation 을 빼앗지 못하고, 전투가 끝나면 별도 상태 전환 없이 다시
        // 선택된다.
        // T27 — 시험 고정 목적지 이동. 후퇴(2)·소닉 추적(3)·근접(4) 보다 아래, 일반 추종(6)·
        // 배회(7) 보다 위다. runtime 목적지가 없으면 canUse 가 false 라 평소에는 없는 Goal 과
        // 같다.
        // T28 — 특수 이동. 활성일 때만 canUse 가 참이라 평소에는 없는 Goal 과 같다. priority 1
        // 의 소닉은 LOOK 만 잡으므로 함께 실행된다.
        this.goalSelector.addGoal(1, new WardenGirlSpecialMovementGoal(this));
        this.testMoveGoal = new WardenGirlTestMoveGoal(this);
        this.goalSelector.addGoal(5, this.testMoveGoal);
        this.goalSelector.addGoal(6, new WardenGirlFollowOwnerGoal(this));
        // T15 — 중립 전투 대상 선정. 선제공격은 없다. targetSelector 에 들어가므로 goalSelector
        // 의 우선순위와 겹치지 않고, TARGET 플래그만 잡아 이동·시선 Goal 을 막지 않는다.
        this.targetSelector.addGoal(1, new WardenGirlTargetGoal(this));
        // T13 — 7.1 소유자 중심(야생이면 현재 위치 중심) 자율 배회. 항상 켜져 있다 —
        // 디버그 명령으로 켜야 하는 구조가 아니다.
        this.goalSelector.addGoal(7, new WardenGirlOwnerStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    // ---- T10 개체별 소유권·강화 데이터 (2차 설계서 4장 / 6장 / 11장) ----------------------
    //
    // 서버 정본이다. SynchedEntityData 도, 전용 패킷도, 클라이언트 캐시도 두지 않는다 —
    // 2차 설계서 11.2 대로 이 두 값은 아직 렌더링에 쓰이지 않는다. 필요해지는 태스크에서
    // 동기화를 따로 붙인다.
    //
    // 두 값 모두 <b>인스턴스 필드</b>다. static 이 하나라도 있으면 모든 워든걸이 소유자와
    // 스택을 공유하게 되므로(설계서 15장 금지 사항) 여기서 구조적으로 막는다.

    /** NBT 키. 설계서 11.1 의 이름을 그대로 쓴다. */
    private static final String TAG_OWNER = "OwnerUUID";
    private static final String TAG_POWER = "PowerStacks";

    /** 없으면 야생. 별도 {@code tamed} 플래그를 두지 않는다 — 설계서 4.1. */
    @javax.annotation.Nullable
    private java.util.UUID ownerUuid;

    /** 개체별 강화 스택. 음수가 되지 않는다. */
    private long powerStacks;

    public java.util.Optional<java.util.UUID> getOwnerUuid() {
        return java.util.Optional.ofNullable(this.ownerUuid);
    }

    public boolean hasOwner() {
        return this.ownerUuid != null;
    }

    /**
     * T13 — 지금 이 워든걸이 생활 중심으로 삼을 수 있는 소유자 엔티티, 없으면 {@code null}.
     *
     * <p>{@code Level.getPlayerByUUID} 는 <b>그 레벨의 {@code players()} 만</b> 훑는다. 그래서 이
     * 한 번의 호출이 UUID 일치 · 접속 중 · <b>같은 차원</b> 세 조건을 동시에 만족시킨다 — 다른
     * 차원의 같은 좌표를 오인할 경로도, 오프라인 소유자를 대신할 플레이어를 고를 경로도 없다.
     * 살아 있음과 제거되지 않음만 덧붙인다.
     *
     * <p>이름 비교, 가장 가까운 플레이어, 서버의 첫 플레이어, 전역·static 캐시, 마지막 위치
     * 기억은 어디에도 없다. 야생({@code ownerUuid == null})이면 곧바로 {@code null} 이다.
     */
    @javax.annotation.Nullable
    public net.minecraft.world.entity.player.Player serverOwner() {
        java.util.UUID id = this.ownerUuid;
        if (id == null) {
            return null;
        }
        net.minecraft.world.entity.player.Player p = this.level().getPlayerByUUID(id);
        if (p == null || !p.isAlive() || p.isRemoved()) {
            return null;
        }
        return p;
    }

    /**
     * 최초 각인. <b>이미 소유자가 있으면 같은 UUID 라도 거절한다</b> — 설계서 4.2 는 변경·해제·
     * 이전을 제공하지 않으므로, 같은 값 재설정을 허용하면 "다시 각인해도 된다"는 경로가 생긴다.
     *
     * @return 이번 호출로 각인됐으면 {@code true}. {@code false} 면 기존 소유자는 그대로다.
     */
    public boolean trySetInitialOwner(java.util.UUID owner) {
        if (owner == null || this.ownerUuid != null) {
            return false;
        }
        this.ownerUuid = owner;
        // T15 — 각인이 성립한 <b>이 틱</b>에 현재 대상을 다시 본다. 각인 전에는 새 소유자도,
        // 같은 소유자가 될 다른 워든걸도 평범한 대상이었으므로, 소인 직후 한 틱이라도 그 공격이
        // 이어지면 안 된다. isOwnerOrSameOwnerWardenGirl 은 방금 채운 ownerUuid 를 쓴다.
        LivingEntity current = getTarget();
        if (current != null && isOwnerOrSameOwnerWardenGirl(current)) {
            setTarget(null);
        }
        return true;
    }

    // ---- T12 소유 관계·전투 대상 공통 판정 ---------------------------------------------------
    //
    // 설계서 4.3. 목표 선정, 근접, 소닉 피해, 소닉 넉백, 복수 목표 선정이 전부 이 두 메서드를
    // 다시 부른다 — 대상마다 조건을 복사하지 않는 것이 이 태스크의 전부다.
    //
    // <b>서버 전용 판정이다.</b> owner UUID 는 SynchedEntityData 로 내보내지 않으므로 클라이언트
    // 인스턴스의 ownerUuid 는 항상 null 이다. 렌더링·클라이언트 예측에 쓰면 안 된다.

    /**
     * 이 워든걸이 <b>보호</b>하는 대상인가. 설계서 4.3 의 {@code isOwnerOrSameOwnerWardenGirl}.
     *
     * <pre>
     *   target == null                          → false
     *   이 개체가 야생(owner 없음)               → false   (보호 대상 자체가 성립하지 않는다)
     *   target 의 UUID == 이 개체의 owner UUID   → true    (소유자 본인)
     *   target 이 워든걸이고 owner UUID 가 같다  → true
     * </pre>
     *
     * <p>야생 워든걸 두 마리는 <b>false</b> 다 — 위의 야생 조기 반환이 그것까지 함께 막는다.
     * 소유자가 다른 워든걸도 false 이므로 추가 보호를 받지 않는다.
     *
     * <p>전역 owner 값도, 현재 플레이어도, 첫 번째 플레이어 검색도 쓰지 않는다. 비교는 두
     * 엔티티 인스턴스의 필드 사이에서만 일어나므로 소유자 플레이어 엔티티를 서버 전체에서
     * 찾을 필요가 없다 — 소유자가 오프라인이거나 다른 차원이어도 같은 답을 낸다.
     */
    public boolean isOwnerOrSameOwnerWardenGirl(@javax.annotation.Nullable Entity target) {
        java.util.UUID mine = this.ownerUuid;
        if (target == null || mine == null) {
            return false;
        }
        if (mine.equals(target.getUUID())) {
            return true;                        // 소유자 본인. 플레이어의 엔티티 UUID = 프로필 UUID
        }
        return target instanceof WardenGirlEntity other && mine.equals(other.ownerUuid);
    }

    /**
     * 보호 대상만 추가로 거절하고 나머지는 바닐라 판정에 맡긴다.
     *
     * <p>{@code canAttack} 은 {@code LivingEntity} 에 선언되어 있고 {@code Mob} 은 이것을
     * 재정의하지 않는다(1.20.1 확인). {@code TargetingConditions.forCombat().test(공격자, 대상)}
     * 이 내부에서 이것을 부르므로, 목표 탐색이 그 조건을 거치기만 하면 필터가 자동으로 걸린다.
     *
     * <p>호출처를 전부 확인했다 — 전부 <b>목표 선정</b> 경로다({@code TargetingConditions},
     * {@code TargetGoal}, brain 의 {@code StartAttacking} / {@code StopAttackingIfTargetInvalid}
     * 등). 피해·충돌·포션 경로에는 없다. 즉 이 재정의는 <b>들어오는 피해를 막지 않는다</b> —
     * 소유자는 여전히 자기 워든걸을 때릴 수 있다. T12 는 대상 필터이지 면역이 아니다.
     */
    @Override
    public boolean canAttack(LivingEntity target) {
        return !isOwnerOrSameOwnerWardenGirl(target) && super.canAttack(target);
    }

    /**
     * 지금 이 순간 실제로 공격할 수 있는 대상인가. 목표 선정·유지·<b>최종 방송 직전</b>이 모두
     * 이 하나를 부른다. 죽음·제거는 여기서, 보호와 바닐라 규칙은 {@link #canAttack} 에서 본다.
     */
    public boolean isValidCombatTarget(@javax.annotation.Nullable LivingEntity target) {
        if (target == null || target == this) {
            return false;                       // 자기 자신은 대상이 아니다
        }
        if (!target.isAlive() || target.isRemoved()) {
            return false;
        }
        if (target.level() != this.level()) {
            return false;                       // 다른 차원. Level 인스턴스가 곧 차원이다.
        }
        // T15 — 크리에이티브·관전자 플레이어는 제외한다. TargetingConditions 는 관전자만
        // 걸러내고 크리에이티브는 통과시키므로(바닐라 확인) 여기서 함께 본다.
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        return canAttack(target);
    }

    // ---- T14 장거리 추종·현지 생활·전투 목줄 ---------------------------------------------------
    //
    // 설계서 7.2 / 7.4. 여기 있는 두 값은 <b>서버 런타임 전용</b>이다 — NBT 에 쓰지 않고,
    // 패킷으로 보내지 않고, SynchedEntityData 에 넣지 않는다. 설계서 7.4 가 현지 생활 중심을
    // "포기 시점의 임시 생활 중심점" 으로 정의하므로 재시작·청크 언로드로 사라지면 소유자 중심
    // 생활로 돌아가는 것이 규정된 동작이다. 전역 static 도, manager 도, capability 도 없다.

    /** 설계서 7.2 — 이 거리를 넘으면 추종을 완전히 포기한다. */
    public static final double GIVE_UP_DISTANCE = 48.0D;
    /** 설계서 7.2 — 포기 후 현지 배회 반경. */
    public static final double LOCAL_RADIUS = 10.0D;
    /** 설계서 7.2 — 현지 생활 중 소유자 재합류 인식 거리. */
    public static final double REJOIN_DISTANCE = 24.0D;

    /**
     * 재합류 경로 판정에 쓰는 탐색 반경. {@link #REJOIN_DISTANCE} 보다 커야 한다.
     *
     * <p>{@code PathNavigation.createPath(Entity, accuracy)} 는 탐색 반경으로
     * {@code Attributes.FOLLOW_RANGE} 를 쓴다(바이트코드 확인). 이 엔티티는
     * {@code Mob.createMobAttributes()} 의 기본값 16 을 그대로 쓰므로, 그 판을 쓰면 24블록
     * 대상은 <b>평지에서도</b> 절대 도달 가능으로 나오지 않는다 — {@code PathFinder} 가
     * 시작점에서 탐색 반경 안에 있는 노드만 확장한다. 그래서 탐색 반경을 인자로 직접 받는
     * 공개 오버로드 {@code createPath(BlockPos, accuracy, maxRange)} 를 쓴다. FOLLOW_RANGE
     * Attribute 는 건드리지 않는다 — 그것을 올리면 목표 탐색 거리까지 함께 변한다.
     */
    private static final int REJOIN_PATH_RANGE = 26;

    /** 상태 재평가 주기(틱). 추종 재경로 주기와 같은 10틱이다. */
    private static final int STATE_CHECK_INTERVAL = 10;

    /** 설계서 8.5 — 전투 <b>시작</b> 목줄. */
    public static final double COMBAT_START_SELF = 12.0D;
    public static final double COMBAT_START_TARGET = 16.0D;
    /** 설계서 8.5 — 전투 <b>유지</b> 목줄. 시작보다 넓어 경계에서 진동하지 않는다. */
    public static final double COMBAT_KEEP_SELF = 16.0D;
    public static final double COMBAT_KEEP_TARGET = 24.0D;

    private boolean fastFollow;
    @javax.annotation.Nullable
    private net.minecraft.world.phys.Vec3 localAnchor;

    public boolean isFastFollow() {
        return this.fastFollow;
    }

    public void setFastFollow(boolean fast) {
        this.fastFollow = fast;
    }

    /** 현지 생활 중심. {@code null} 이면 현지 생활 상태가 아니다. */
    @javax.annotation.Nullable
    public net.minecraft.world.phys.Vec3 localAnchor() {
        return this.localAnchor;
    }

    /**
     * 설계서 7.4 완전 포기. 순서까지 규정대로다 — navigation 중단 → 빠른 추종 해제 →
     * 현재 위치를 현지 생활 중심으로 기록. 추종 Goal 종료는 {@code localAnchor != null} 을
     * 보는 {@code canContinueToUse} 가 다음 평가에서 처리한다.
     *
     * <p>소유자의 마지막 위치를 기억하지 않는다. 순간이동도, 강제 청크 로딩도 없다.
     */
    public void giveUpFollowAndSettleHere() {
        this.getNavigation().stop();
        this.fastFollow = false;
        this.localAnchor = this.position();
    }

    /**
     * 설계서 7.4 재합류. 현지 생활 중심을 지우고 빠른 추종 상태를 초기화한다. 그 뒤의 행동은
     * 거리만으로 갈린다 — 12 초과면 추종 Goal 이 스스로 선택되고, 12 이하면 소유자 중심 배회다.
     */
    private void rejoinOwner() {
        this.fastFollow = false;
        this.localAnchor = null;
    }

    /**
     * 소유자에게 <b>실제로 도달하는</b> 경로가 있는가. {@code moveTo} 의 반환값은 쓰지 않는다 —
     * 바닐라는 닿지 못하는 대상에도 부분 경로를 만들고 {@code true} 를 돌려준다.
     *
     * <h3>{@code createPath} 의 부작용을 피하는 조건</h3>
     *
     * {@code PathNavigation.createPath} 는 계산 결과의 목표가 있으면 {@code targetPos} 와
     * {@code reachRange} 를 <b>덮어쓴다</b>(바이트코드 확인). 그 두 필드를 읽는 곳은
     * {@code recomputePath()} 와 {@code createPath} 자신의 캐시 검사뿐이고, 전자는
     * {@code shouldRecomputePath} 가 <b>활성 경로가 있을 때만</b> 참이 되어 호출된다. 그래서
     * {@code navigation.isDone()} 일 때만 물어본다 — 그 순간에는 두 필드를 덮어써도 읽는 쪽이
     * 없고, 다음 실제 {@code moveTo} 가 정상 값으로 다시 채운다. 이 조건이 없으면 배회 경로가
     * 블록 변경 때 소유자 쪽으로 재계산되어, "포기 후 소유자 방향으로 이동하지 않는다" 가
     * 깨진다.
     *
     * <p>남는 한계 두 가지는 실측으로 보고한다. {@code createPath} 는 {@code canUpdatePath()}
     * 가 false 인 순간(공중)에 {@code null} 을 돌려주므로 그때는 "경로 없음" 으로 읽힌다 —
     * 10틱마다 다시 물어보므로 재합류가 조금 늦어질 뿐이다. 그리고 탐색 노드 상한을 넘는 긴
     * 우회로는 실제로 길이 있어도 도달 불가로 나온다.
     */
    private boolean hasRealPathTo(net.minecraft.world.entity.player.Player owner) {
        net.minecraft.world.entity.ai.navigation.PathNavigation nav = this.getNavigation();
        if (!nav.isDone()) {
            return false;
        }
        net.minecraft.world.level.pathfinder.Path path =
                nav.createPath(owner.blockPosition(), 1, REJOIN_PATH_RANGE);
        return path != null && path.canReach();
    }

    /**
     * 48 포기와 24 재합류를 여기 한 곳에서만 결정한다.
     *
     * <p>추종 Goal 안이 아니라 엔티티 쪽에 둔 이유가 있다 — 전투가 워든걸을 48블록 밖으로
     *끌고 갔을 때는 추종 Goal 이 애초에 돌지 않으므로, Goal 안에서만 판정하면 현지 생활로
     * 넘어가지 못한다. 설계서 8.5 의 "전투 이탈 후" 표가 요구하는 재평가가 이 한 곳이다.
     */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        // T23 — goalSelector.tick() 과 navigation.tick() 뒤라 여기서 보는 경로가 이번 틱에
        // 확정된 최신 경로다(바이트코드 확인). 아래 T14 상태 점검의 20틱 간격과 달리 문은
        // 매 서버틱 봐야 하므로 게이트보다 앞에 둔다.
        this.doorHelper.tick();
        if (this.tickCount % STATE_CHECK_INTERVAL != 0) {
            return;
        }
        net.minecraft.world.entity.player.Player owner = serverOwner();
        if (owner == null) {
            // 설계서 7.5 — 다른 플레이어를 대신 고르지 않는다. 이미 있는 현지 중심은 그대로 둔다.
            return;
        }
        double d = this.distanceTo(owner);
        if (this.localAnchor == null) {
            if (d > GIVE_UP_DISTANCE) {
                giveUpFollowAndSettleHere();
            }
            return;
        }
        if (d <= REJOIN_DISTANCE && hasRealPathTo(owner)) {
            rejoinOwner();
        }
    }

    /**
     * 소유자가 있는 워든걸의 전투 목줄. 소유자에게서 너무 멀어지는 전투를 막는다.
     *
     * <p>야생이거나 소유자를 찾을 수 없으면 <b>제한하지 않는다</b> — 설계서 7.5 대로 다른
     * 플레이어를 대신 기준으로 삼지 않고, 기존 자기 전투 규칙을 그대로 쓴다.
     */
    private boolean combatWithinOwnerLeash(@javax.annotation.Nullable LivingEntity target,
                                           double selfMax, double targetMax) {
        net.minecraft.world.entity.player.Player owner = serverOwner();
        if (owner == null) {
            return true;
        }
        if (this.distanceToSqr(owner) > selfMax * selfMax) {
            return false;
        }
        return target == null || target.distanceToSqr(owner) <= targetMax * targetMax;
    }

    /** 새 전투를 시작해도 되는가. 워든걸-소유자 12 이하 <b>그리고</b> 대상-소유자 16 이하. */
    public boolean canStartLeashedCombat(@javax.annotation.Nullable LivingEntity target) {
        return combatWithinOwnerLeash(target, COMBAT_START_SELF, COMBAT_START_TARGET);
    }

    /** 전투를 이어가도 되는가. 워든걸-소유자 16 이하 <b>그리고</b> 대상-소유자 24 이하. */
    public boolean canKeepLeashedCombat(@javax.annotation.Nullable LivingEntity target) {
        return combatWithinOwnerLeash(target, COMBAT_KEEP_SELF, COMBAT_KEEP_TARGET);
    }

    // ---- T20 저체력 후퇴 --------------------------------------------------------------------
    //
    // 설계서 4.2. T14 의 두 값과 마찬가지로 <b>서버 런타임 전용</b>이다 — NBT 도, 패킷도,
    // SynchedEntityData 도 없다. 상태 자체가 현재 체력의 함수라서 로드 뒤 첫 AI 평가에 그대로
    // 다시 계산된다. 클라이언트는 이 값을 결정하지 않는다.

    /** 설계서 4.2.1 — 진입·해제 경계(퍼센트). 경계값은 포함한다. */
    public static final double RETREAT_ENTER_PERCENT = 25.0D;
    public static final double RETREAT_EXIT_PERCENT = 45.0D;

    private boolean retreating;

    /**
     * 후퇴 전용 현지 중심. T14 의 {@link #localAnchor} 와 <b>다른 값</b>이고 서로 건드리지
     * 않는다. 소유자를 쓸 수 없게 된 <b>그 순간</b>의 위치를 담고, 워든걸을 따라 움직이지
     * 않는다. 후퇴가 끝나면 지운다.
     */
    @javax.annotation.Nullable
    private net.minecraft.world.phys.Vec3 retreatAnchor;

    /** 지금 후퇴 중인가. T20 의 유일한 공개 조회다. */
    public boolean isRetreating() {
        return this.retreating;
    }

    private void setRetreating(boolean value) {
        this.retreating = value;
        if (!value) {
            this.retreatAnchor = null;              // 후퇴가 끝나면 현지 중심을 버린다
        }
    }

    /**
     * 설계서 4.2.1 히스테리시스.
     *
     * <p>비율을 나눗셈으로 만들지 않는다. {@code health / maxHealth >= 0.45} 는 0.45 가 이진
     * 소수로 정확하지 않아 경계에서 실제로 어긋난다 — 예를 들어 최대 500 · 현재 225 는
     * {@code 500 * 0.45} 가 225.00000000000003 이 되어 <b>해제되지 않는다</b>. 그래서 양변에
     * 100 을 곱한 정수형 비교로 쓴다.
     */
    private void updateRetreatState() {
        float max = getMaxHealth();
        if (max <= 0.0F) {
            return;                                 // 비정상 상태에서 새로 시작하지 않는다
        }
        double hp = getHealth() * 100.0D;
        if (this.retreating) {
            if (hp >= max * RETREAT_EXIT_PERCENT) {
                setRetreating(false);
            }
        } else if (hp <= max * RETREAT_ENTER_PERCENT) {
            setRetreating(true);
        }
    }

    /**
     * 설계서 4.2.2 후퇴 행동 중심.
     *
     * <p>소유자가 같은 서버 레벨에 살아 있고 48블록 안이면 <b>현재</b> 소유자 위치다. 그 밖의
     * 모든 경우(없음·오프라인·사망·제거·다른 차원·48 초과)에는 전환하는 <b>그 순간</b>의
     * 워든걸 위치를 한 번 붙잡아 계속 쓴다. 대체 플레이어는 찾지 않는다.
     *
     * <p>{@code serverOwner()} 가 {@code Level.getPlayerByUUID} 를 쓰므로 다른 차원의 소유자는
     * 이미 {@code null} 이다. 아래 레벨 비교는 그 사실을 코드에 남겨 둔 것이다.
     */
    public net.minecraft.world.phys.Vec3 retreatCenter() {
        net.minecraft.world.entity.player.Player owner = serverOwner();
        if (owner != null && owner.level() == this.level()
                && this.distanceTo(owner) <= GIVE_UP_DISTANCE) {
            this.retreatAnchor = null;              // 소유자 중심으로 돌아왔다
            return owner.position();
        }
        if (this.retreatAnchor == null) {
            this.retreatAnchor = this.position();   // 전환 순간 고정
        }
        return this.retreatAnchor;
    }

    /** 후퇴 현지 중심. {@code null} 이면 소유자 중심을 쓰고 있다는 뜻이다. */
    @javax.annotation.Nullable
    public net.minecraft.world.phys.Vec3 retreatAnchor() {
        return this.retreatAnchor;
    }

    /**
     * 후퇴 상태를 <b>Goal 평가 직전</b>에 갱신한다.
     *
     * <p>{@code customServerAiStep()} 은 {@code goalSelector.tick()} <b>뒤</b>에 불리므로 거기
     * 두면 진입이 한 틱 늦는다. {@code Mob.serverAiStep()} 은 {@code protected final} 이라
     * 재정의할 수 없고, 그것을 부르는 곳은 {@code LivingEntity.aiStep()} 하나뿐이다(바이트코드
     * 확인). 그래서 여기가 매 서버틱 · Goal 평가 직전인 유일한 자리다.
     */
    @Override
    public void aiStep() {
        if (!level().isClientSide) {
            // T23 — 길들여짐이 바뀌었을 때만 navigation 의 문 설정을 고친다.
            this.doorHelper.syncNavigation();
            // T22 — 회복이 먼저다. 회복으로 45% 에 닿은 틱에 바로 후퇴가 풀리고, 같은
            // goalSelector 평가에서 후퇴 Goal 이 끝난다.
            passiveHealTick();
            updateRetreatState();
        }
        super.aiStep();
    }

    // ---- T22 비전투 체력 회복 (서버 runtime 전용) ---------------------------------------------
    //
    // NBT 도, SynchedEntityData 도, 패킷도, 전역 관리자도 없다. 개체마다 독립이며 저장하지
    // 않으므로 로드·청크 재로드 뒤에는 새로 280틱을 기다린다.

    /** 마지막 <b>실제</b> 피해 뒤 회복이 시작되기까지의 무피격 대기(틱). */
    public static final long HEAL_QUIET_TICKS = 200L;

    /** 회복 간격(틱). 첫 회복도 이 간격을 한 번 더 기다린 뒤에 온다. */
    public static final long HEAL_INTERVAL_TICKS = 80L;

    /** 회복량 비율. 실제 회복량은 {@code max(1.0, maxHealth × 이 값)} 이다. */
    public static final double HEAL_PERCENT = 0.01D;

    private long lastActualDamageTick;
    private long nextPassiveHealTick;
    private boolean passiveHealScheduleInitialized;

    /** 이번 개체의 다음 자연 회복 예정 서버틱. 계측·보고용 조회다. */
    public long nextPassiveHealTick() {
        return this.nextPassiveHealTick;
    }

    /** 마지막으로 <b>실제 체력이 줄어든</b> 서버틱. 계측·보고용 조회다. */
    public long lastActualDamageTick() {
        return this.lastActualDamageTick;
    }

    /** 이번 회복 주기에 더할 양. 스택별 분기 없이 현재 최대 체력 하나로 정한다. */
    public float passiveHealAmount() {
        return (float) Math.max(1.0D, getMaxHealth() * HEAL_PERCENT);
    }

    /**
     * 실제 피해가 <b>정말로</b> 있었는지 여기서 정한다.
     *
     * <p>{@code super.hurt} 전후의 {@code getHealth()} 를 비교한다. 화염 면역·관계 필터·무적
     * 프레임·최종 피해 0·흡수 체력만 감소는 전부 체력이 줄지 않으므로 자연히 제외된다 —
     * 그런 사례를 하나씩 나열하는 목록을 만들지 않는다.
     *
     * <p>스택 증가·감소로 최대 체력이 바뀌며 {@code setHealth} 가 값을 자르는 경로는 여기를
     * 지나지 않으므로 실제 피해로 기록되지 않는다(2차 설계서 6.4).
     */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (level().isClientSide) {
            return super.hurt(source, amount);
        }
        float before = getHealth();
        boolean applied = super.hurt(source, amount);
        if (getHealth() < before) {
            noteActualDamage();
        }
        return applied;
    }

    /**
     * 같은 틱에 여러 번 불려도 마지막 값이 남는다. 초기화 깃발도 함께 세워, 아직 첫 서버틱을
     * 지나지 않은 개체가 피해를 먼저 받은 경우 뒤이은 초기화가 이 기준을 <b>덮어쓰지 않게</b>
     * 한다(설계서 15).
     */
    private void noteActualDamage() {
        long now = level().getGameTime();
        this.passiveHealScheduleInitialized = true;
        this.lastActualDamageTick = now;
        this.nextPassiveHealTick = now + HEAL_QUIET_TICKS + HEAL_INTERVAL_TICKS;
    }

    /**
     * 설계서 17. 밀린 회복을 몰아서 하지 않는다 — 다음 예정 시각은 <b>지금</b> 기준으로만
     * 다시 잡는다. 시간 기준은 {@code level().getGameTime()} 이다. 개체의 {@code tickCount} 는
     * 청크 재로드로 되돌아갈 수 있고 서버 전체가 공유하는 시각이 아니라서 쓰지 않는다.
     */
    private void passiveHealTick() {
        long now = level().getGameTime();
        if (!this.passiveHealScheduleInitialized) {
            this.passiveHealScheduleInitialized = true;
            this.lastActualDamageTick = now;
            this.nextPassiveHealTick = now + HEAL_QUIET_TICKS + HEAL_INTERVAL_TICKS;
            return;                                 // 소환·로드 직후에도 새로 280틱을 기다린다
        }
        if (!isAlive() || isRemoved()) {
            return;
        }
        if (now - this.nextPassiveHealTick < 0L) {
            return;
        }
        if (getHealth() < getMaxHealth()) {
            heal(passiveHealAmount());              // 바닐라. 최대 체력 초과는 heal 이 자른다
        }
        this.nextPassiveHealTick = now + HEAL_INTERVAL_TICKS;
    }

    // ---- T28 특수 이동 controller (서버 전용, 저장 안 함) ------------------------------------

    /**
     * 특수 이동 상태·계획·실패 기억을 드는 controller. 개체마다 하나이며 안전 검사는
     * {@link WardenGirlMovementSafety}, MOVE 점유는 {@link WardenGirlSpecialMovementGoal} 이
     * 맡는다 — 여기에는 위임 API 만 둔다.
     */
    private final WardenGirlSpecialMovement specialMovement = new WardenGirlSpecialMovement(this);

    public WardenGirlSpecialMovement specialMovement() {
        return this.specialMovement;
    }

    /**
     * 특수 이동이 끝난 뒤의 route resume. 기존 Goal 의 속도·재경로 타이머를 여기서 복제하지
     * 않는다 — {@link WardenGirlTestMoveGoal} 의 다음 재경로만 즉시 가능하게 열어 주고, 나머지
     * Goal 은 다음 selector 평가에서 스스로 새 path 를 만든다.
     */
    void resumeRouteAfterSpecialMovement() {
        if (this.testMoveGoal != null) {   // registerGoals 는 서버에서만 돈다
            this.testMoveGoal.allowImmediateRepath();
        }
    }

    // ---- T27 4차 검증용 runtime 상태 (서버 전용, 저장 안 함) --------------------------------
    //
    // 설계서 4차 Part 3.11 — 전부 runtime only 다. NBT·SynchedEntityData·SavedData·전역
    // static 컬렉션·플레이어 persistent data 어디에도 쓰지 않는다. 청크 재로드나 재접속으로
    // 개체가 새로 만들어지면 필드 기본값(null)이라 자동으로 해제된 상태가 된다.

    /** 회피 확률 override 분류. 문자열 키를 여기저기서 비교하지 않기 위한 타입이다. */
    public enum DodgeType {
        BACK, DIAGONAL, PLAYER;

        /** 명령 인수 문자열 → 분류. 알 수 없으면 {@code null}. */
        @javax.annotation.Nullable
        public static DodgeType parse(String raw) {
            for (DodgeType t : values()) {
                if (t.name().equalsIgnoreCase(raw)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** T27 시험 이동 Goal. route resume 때 재경로만 열어 주기 위해 참조를 둔다. */
    private WardenGirlTestMoveGoal testMoveGoal;

    /** 시험용 고정 목적지. 명령 실행 <b>순간</b>의 좌표이며 이후 따라 움직이지 않는다. */
    @javax.annotation.Nullable
    private net.minecraft.world.phys.Vec3 testDestination;

    /** 시험용 근접 피해 override. {@code null} 이면 현재 살아 있는 ATTACK_DAMAGE 를 쓴다. */
    @javax.annotation.Nullable
    private Double testAttackDamageOverride;

    /** 회피 확률 override(%). 값이 없는 칸은 {@code null} 이다. */
    private final java.util.EnumMap<DodgeType, Double> testDodgeChance =
            new java.util.EnumMap<>(DodgeType.class);

    public void setTestDestination(net.minecraft.world.phys.Vec3 pos) {
        this.testDestination = pos;
    }

    public void clearTestDestination() {
        this.testDestination = null;
    }

    @javax.annotation.Nullable
    public net.minecraft.world.phys.Vec3 getTestDestination() {
        return this.testDestination;
    }

    public void setTestAttackDamageOverride(double value) {
        this.testAttackDamageOverride = value;
    }

    public void clearTestAttackDamageOverride() {
        this.testAttackDamageOverride = null;
    }

    @javax.annotation.Nullable
    public Double getTestAttackDamageOverride() {
        return this.testAttackDamageOverride;
    }

    public void setTestDodgeChanceOverride(DodgeType type, double percent) {
        this.testDodgeChance.put(type, percent);
    }

    public void clearTestDodgeChanceOverride(DodgeType type) {
        this.testDodgeChance.remove(type);
    }

    public void clearAllTestDodgeChanceOverrides() {
        this.testDodgeChance.clear();
    }

    @javax.annotation.Nullable
    public Double getTestDodgeChanceOverride(DodgeType type) {
        return this.testDodgeChance.get(type);
    }

    /**
     * T28 이후의 특수 이동 취소가 <b>여기 한 곳</b>에 연결된다. 지금은 시험 목적지를 지우고
     * navigation 을 멈추는 것이 전부다. 소유자·target·PowerStacks 는 건드리지 않는다.
     */
    public void cancelTestMovement() {
        // T28 — 특수 이동 취소가 여기 한 곳에 연결된다. 실패 기억은 새로 만들지 않는다.
        this.specialMovement.cancel(WardenGirlSpecialMovement.Reason.ADMIN_CANCEL);
        clearTestDestination();
        getNavigation().stop();
    }

    /**
     * 지금 이 개체의 <b>유효 근접 공격력</b>. 일반 근접 공격이 실제로 쓰는 단 하나의 값이며,
     * T36·T37 의 대각선 카운터도 같은 것을 쓰게 된다.
     *
     * <p>override 가 있으면 그 값, 없으면 현재 살아 있는 {@code ATTACK_DAMAGE} 다. 소닉 피해는
     * 여기를 지나지 않는다 — {@link #getSonicDamage()} 가 따로 계산한다.
     */
    public double effectiveMeleeDamage() {
        Double override = this.testAttackDamageOverride;
        return override != null ? override : getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    /** 시험 override 를 실을 때 쓰는 고정 UUID. 한 호출 안에서 붙였다가 곧바로 뗀다. */
    private static final java.util.UUID TEST_DAMAGE_MODIFIER =
            java.util.UUID.fromString("8f1a3c4e-2b6d-4f70-9c11-5d2e7a0b3c48");

    /**
     * 일반 근접 타격의 <b>단일 진입점</b>. 평소에는 바닐라 {@code Mob.doHurtTarget} 을 그대로
     * 부른다 — 인챈트 보정·넉백·화염·{@code setLastHurtMob}·방어구·무적 틱이 전부 바닐라다.
     *
     * <p>시험 override 가 있을 때만 그 한 번의 호출 동안 {@code ATTACK_DAMAGE} 에
     * <b>transient</b> modifier 를 실어 최종값을 override 로 맞추고 {@code finally} 에서 뗀다.
     * base value 를 바꾸지 않으므로 {@link #recalculatePowerStats} 도, 저장되는 성장값도
     * 영향을 받지 않는다. transient modifier 는 저장 대상이 아니며 같은 호출 안에서 사라지므로
     * NBT 에 남을 수도 없다.
     */
    public boolean performMeleeAttack(Entity target) {
        Double override = this.testAttackDamageOverride;
        AttributeInstance instance = getAttribute(Attributes.ATTACK_DAMAGE);
        if (override == null || instance == null) {
            return doHurtTarget(target);
        }
        net.minecraft.world.entity.ai.attributes.AttributeModifier mod =
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        TEST_DAMAGE_MODIFIER, "wardengirl test attack_damage",
                        override - instance.getValue(),
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION);
        instance.addTransientModifier(mod);
        try {
            return doHurtTarget(target);
        } finally {
            instance.removeModifier(mod);
        }
    }

    // ---- T23 나무문 길찾기·개폐 --------------------------------------------------------------

    /**
     * 문 보조 controller. Goal 이 아니라 현재 경로를 읽어 필요한 문만 여닫는 도우미다.
     * 개체마다 하나이고 서버 runtime 전용이다 — NBT 도 패킷도 없다.
     */
    private final WardenGirlDoorHelper doorHelper = new WardenGirlDoorHelper(this);

    /** 계측·보고용. 지금 추적 중인 "자신이 연 문" 수. */
    public int trackedDoorCount() {
        return this.doorHelper.recordCount();
    }

    public long getPowerStacks() {
        return this.powerStacks;
    }

    /**
     * 음수는 0 으로 보정한다. 반환형이 {@code void} 라 거절을 알릴 방법이 없으므로 설계서 6.1
     * 의 두 선택지("0 으로 보정하거나 거절") 중 보정을 택했다. 거절은 명령어 쪽에서 파싱 단계에
     * 맡긴다 — 거기서는 사람에게 이유를 돌려줄 수 있다.
     */
    public void setPowerStacks(long stacks) {
        long next = Math.max(0L, stacks);
        // 실제로 늘어났을 때만 회복한다. 같은 값 재설정, 감소, add 0 은 전부 회복 없음이다.
        boolean grew = next > this.powerStacks;
        this.powerStacks = next;
        recalculatePowerStats(grew);
    }

    /**
     * 포화 덧셈. 설계서 6.1 은 게임 설계상 상한을 두지 않지만 {@code long} 의 기술적 한계는
     * 인정하므로, 넘치면 {@link Long#MAX_VALUE} 에서 멈추고 모자라면 0 에서 멈춘다.
     * {@code Math.addExact} 로 던지지 않는 이유는 정상 플레이에서 도달할 수 없는 값 때문에
     * 예외 경로를 만들 필요가 없어서다.
     */
    public void addPowerStacks(long amount) {
        long sum = this.powerStacks + amount;
        if (amount > 0L && sum < this.powerStacks) {
            sum = Long.MAX_VALUE;                   // 위로 넘침
        } else if (amount < 0L && sum > this.powerStacks) {
            sum = 0L;                               // 아래로 넘침
        }
        // 재계산과 회복은 setPowerStacks 한 곳에서만 일어난다 — 두 번 적용될 경로가 없다.
        setPowerStacks(sum);
    }

    /** 이 개체의 스택으로 계산한 소닉붐 피해. 전역 값도, 저장된 값도 아니다. */
    public double getSonicDamage() {
        return PowerStats.sonic(this.powerStacks);
    }

    /**
     * 스택 → Attribute base value. <b>몇 번 불러도 같은 스택에서 같은 결과다.</b>
     *
     * <p>modifier 를 붙이지 않고 개체 {@code AttributeInstance} 의 base value 를 목표값으로
     * 그냥 <em>설정</em>한다. 그래서 반복 호출이 누적되지 않고, 포션·장비가 얹은 modifier 는
     * 별도 레이어라 그대로 남는다. 다른 워든걸의 {@code AttributeMap} 은 건드리지 않는다.
     *
     * @param healGrowthDelta {@code true} 면 최대 체력이 <b>실제로 늘어난 만큼만</b> 현재
     *                        체력을 올린다. 로드·복구·같은 스택 재계산에서는 반드시
     *                        {@code false} 여야 한다(2차 설계서 6.4).
     */
    public void recalculatePowerStats(boolean healGrowthDelta) {
        if (level().isClientSide) {
            return;                                 // 서버 정본. 클라이언트는 동기화된 체력만 본다.
        }
        float before = getMaxHealth();
        setBase(Attributes.MAX_HEALTH, PowerStats.health(this.powerStacks));
        setBase(Attributes.ATTACK_DAMAGE, PowerStats.attack(this.powerStacks));
        setBase(Attributes.KNOCKBACK_RESISTANCE,
                PowerStats.knockbackResistance(this.powerStacks));
        float after = getMaxHealth();

        float health = getHealth();
        if (healGrowthDelta && after > before) {
            health += after - before;
        }
        // setHealth 가 [0, 최대] 로 자른다 — 스택이 줄어 최대가 내려간 경우가 여기서 처리된다.
        setHealth(health);
    }

    private void setBase(Attribute attribute, double value) {
        AttributeInstance instance = getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) {
            instance.setBaseValue(value);
        }
    }

    /**
     * 길들여진 워든걸은 거리로 사라지지 않는다.
     *
     * <h2>왜 필요한가 — 실측 경로</h2>
     *
     * {@code ServerLevel.tickNonPassenger} 가 매 틱 {@code Mob.checkDespawn} 을 부르고, 거기서
     * {@code isPersistenceRequired() || requiresCustomPersistence()} 가 둘 다 거짓이면 거리
     * 판정으로 들어간다. 이 엔티티는 {@code MobCategory.CREATURE}(despawn 128 / noDespawn 32)
     * 이고, {@code Mob.removeWhenFarAway} 는 기본이 {@code return true} 이며 우리는 그것도
     * {@code setPersistenceRequired()} 도 쓰지 않았다. 즉 <b>소유자가 있든 없든 가장 가까운
     * 플레이어가 128블록을 넘으면 즉시 {@code discard}</b> 됐다. 지금까지 검증에서 살아남은
     * 것은 시험 중 플레이어가 늘 가까이 있었기 때문이지 구조가 막고 있어서가 아니었다.
     *
     * <h2>{@code setPersistenceRequired()} 대신 이 방법을 쓴 이유</h2>
     *
     * {@code persistenceRequired} 는 {@code Mob} 이 {@code PersistenceRequired} 키로 <b>따로
     * 저장</b>하는 독립 상태다. 각인 시점에 켜는 방식이면 그 키가 없는 기존 저장본 — 이미
     * {@code OwnerUUID} 만 가진 개체 — 을 로드할 때 영구성이 복구되지 않아 로드 경로에 조건을
     * 한 번 더 넣어야 한다. 여기서 파생값으로 답하면 저장할 것이 없고, 로드 직후에도 자동으로
     * 맞으며, 명령이든 나중의 길들이기 상호작용이든 같은 결과가 나온다.
     *
     * <p>야생 워든걸의 정책은 건드리지 않는다 — 소유자가 없으면 {@code Mob} 의 기본
     * ({@code isPassenger()}) 을 그대로 돌려준다.
     */
    @Override
    public boolean requiresCustomPersistence() {
        return hasOwner() || super.requiresCustomPersistence();
    }

    /**
     * 소유자와 스택만 저장한다. 계산 가능한 스탯은 저장하지 않는다 — 설계서 11.1.
     * 소유자가 없으면 키 자체를 쓰지 않는다(가짜 UUID 금지).
     */
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.ownerUuid != null) {
            tag.putUUID(TAG_OWNER, this.ownerUuid);
        }
        tag.putLong(TAG_POWER, this.powerStacks);
    }

    /**
     * 키가 없으면 야생·0스택이다 — {@code getLong} 이 없는 키에 0 을 돌려주므로 이전 저장본이
     * 그대로 열린다. 손상되거나 손으로 고친 음수는 0 으로 복구한다.
     */
    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        // 저장된 Health 를 super 보다 먼저 붙잡는다.
        //
        // LivingEntity.readAdditionalSaveData 의 실제 순서는 AbsorptionAmount → Attributes
        // → ActiveEffects → Health 다(바이트코드 확인). 그래서 보통은 Attributes 가 먼저
        // 복원되어 setHealth 가 제 최대치로 자르지만, Attributes NBT 가 없거나 스택과 어긋난
        // 저장본에서는 그 시점의 낮은 최대치로 잘려 버린다 — 100스택 400/500 이 30 으로
        // 남는 경우가 그것이다. PowerStacks 가 성장의 유일한 정본이므로, 재계산으로 최대치를
        // 세운 뒤 저장값을 그대로 되돌려 놓는다.
        //
        // 이것은 <b>회복이 아니라 저장값 복구</b>다. 회복은 recalculatePowerStats(true) 하나뿐이고
        // 여기서는 false 로 부른다.
        float savedHealth = tag.contains("Health", 99) ? tag.getFloat("Health") : Float.NaN;

        super.readAdditionalSaveData(tag);
        this.ownerUuid = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
        this.powerStacks = Math.max(0L, tag.getLong(TAG_POWER));
        recalculatePowerStats(false);

        if (!Float.isNaN(savedHealth)) {
            // setHealth 가 새 최대치로 자르므로, 저장 체력이 더 클 때만 제한된다.
            setHealth(savedHealth);
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_AXIS_TEST, "");
        this.entityData.define(DATA_SIGN_TEST, false);
        this.entityData.define(DATA_ACTION_CLIP, "");
        this.entityData.define(DATA_ACTION_SEQ, 0);
    }

    // ---- axis verification harness (T1 only) -------------------------------------------------

    /** @return {@code "<bone>:<axis>"}, or an empty string when no axis test is active. */
    public String getAxisTest() {
        return this.entityData.get(DATA_AXIS_TEST);
    }

    public void setAxisTest(String value) {
        this.entityData.set(DATA_AXIS_TEST, value);
    }

    public boolean isSignTest() {
        return this.entityData.get(DATA_SIGN_TEST);
    }

    public void setSignTest(boolean value) {
        this.entityData.set(DATA_SIGN_TEST, value);
    }

    // ---- C3 액션 (직접 평가. 컨트롤러 없음) ------------------------------------------------------

    /** Clip name currently requested, or empty. Rendered by {@code ActionMotion}, not a controller. */
    public String getActionClip() {
        return this.entityData.get(DATA_ACTION_CLIP);
    }

    /**
     * Bumped on every trigger, so the client can tell "play again" from "still playing".
     *
     * <p>A boolean cannot express a re-trigger while the previous playback is still running, and an
     * action chain does exactly that — the second attack does not wait for the first to end.
     */
    public int getActionSeq() {
        return this.entityData.get(DATA_ACTION_SEQ);
    }

    /** Server side. Empty clip name stops playback. */
    public void playAction(String clip) {
        this.entityData.set(DATA_ACTION_CLIP, clip);
        this.entityData.set(DATA_ACTION_SEQ, getActionSeq() + 1);
    }

    // ---- GeoEntity --------------------------------------------------------------------------

    /**
     * Two controllers. Design doc Part 4.2.
     *
     * <p>The first is unconditional and always returns {@link PlayState#CONTINUE}. There is no code
     * path that stops it, by design: Part 4.1 원칙 6 and Part 10.5 both forbid stopping C1.
     *
     * <p>What it plays is now a switch. C1 moved to {@link com.wardengirl.client.VitalMotion} in T5
     * because two controllers writing the same bone means the later one erases the earlier — so
     * this controller normally plays {@link AnimRegistry#BASE_LOOP}, a zero clip whose job is to
     * reset the channels Java adds to. {@code c1_source = 0} puts the old Molang clip back for
     * A/B measurement.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, AnimRegistry.CONTROLLER_VITAL, 0,
                state -> state.setAndContinue(AnimParams.C1_SOURCE.get() >= 0.5D
                        ? AnimRegistry.BASE_LOOP : AnimRegistry.VITAL_LOOP)));
        registrar.add(new AnimationController<>(this, AnimRegistry.CONTROLLER_LOCOMOTION,
                AnimRegistry.TRANSITION_TICKS, this::locomotionPredicate));
    }

    /**
     * C2 — walk while moving, nothing while standing. Design doc 4.4.1 / 4.4.2.
     *
     * <p>{@code isMoving()} is GeckoLib's own limb-swing test, not a velocity threshold of our own:
     * it reads {@code limbSwingAmount}, which vanilla already computes from the entity's actual
     * horizontal travel and already smooths. Writing a {@code getDeltaMovement().horizontalDistance()
     * > eps} test instead would flicker at the threshold every time the pathfinder eased off, and
     * would also fire while the mob was being pushed rather than walking.
     *
     * <p>Returning {@link PlayState#STOP} for idle rather than playing an idle clip is 4.4.1's
     * "C1만 재생" — see {@link AnimRegistry#TRANSITION_TICKS}.
     */
    private PlayState locomotionPredicate(AnimationState<WardenGirlEntity> state) {
        if (isSignTest()) {
            return state.setAndContinue(AnimRegistry.SIGN_TEST_LOOP);
        }
        // c2_source 0 restores the pre-migration path for A/B measurement. See AnimParams.
        if (AnimParams.C2_SOURCE.get() < 0.5D && isWalkingForAnimation()) {
            return state.setAndContinue(AnimRegistry.WALK_LOOP);
        }
        // The walk cycle is NOT played by this controller any more - it is evaluated by
        // LocomotionMotion + ClipSampler and added in setCustomAnimations, so that its playhead can
        // be driven by distance rather than by GeckoLib's speed-scaled clock (문제 3).
        //
        // idle is still played, and unconditionally. It assigns all seven locomotion channels every
        // frame, which is what keeps Java's += writes from accumulating. And because this controller
        // never changes animation any more, it has no transition - so the snapshot pollution that
        // broke the stop transition cannot happen at all.
        return state.setAndContinue(AnimRegistry.IDLE_LOOP);
    }

    // ---- 걷기 판정 (자체, 이력 있음) ------------------------------------------------------------

    /** Last tick the entity was measurably moving, or {@link Integer#MIN_VALUE} if never. */
    private int lastMovingTick = Integer.MIN_VALUE;
    private boolean walkingForAnimation = false;

    /**
     * Whether to play the walk cycle. Replaces {@code AnimationState.isMoving()}.
     *
     * <h2>Why not the library's</h2>
     *
     * {@code AnimationState.isMoving()} is
     * {@code avgVelocity >= getMotionAnimThreshold() && limbSwingAmount != 0}, recomputed from
     * scratch every frame with <b>no history</b>, and {@code getDeltaMovement()} is an
     * <em>instantaneous</em> velocity. A pathfinding mob decelerates between path nodes and stops
     * dead on reaching a destination before picking the next one, so the average dips below the
     * 0.015 default for a tick or two at a time while still visibly walking. Each dip ended the
     * animation and the next frame restarted it from tick 0.
     *
     * <h2>Asymmetric hysteresis</h2>
     *
     * Start immediately, stop only after {@code walk_stop_grace} consecutive ticks below the
     * threshold. A late start looks like the body sliding out from under the legs; a late stop is
     * a couple of extra steps and reads as far less wrong.
     */
    /**
     * Read-only view of the walk state, for measurement.
     *
     * <p>Separate from {@link #isWalkingForAnimation()} because that method <em>advances</em> the
     * hysteresis. A verifier that called it would be driving the thing it is measuring, and would
     * do so at frame rate rather than tick rate.
     */
    /**
     * Removed: the per-tick walk distance.
     *
     * <p>It handed the phase a whole tick's travel on that tick's first frame and zero on every
     * other, which made the drawn leg a staircase — measured at 3.78 steps per cycle, costing 75%
     * of the foot-slip shortfall. The phase now runs off the <em>rendered</em> position every frame
     * instead; see {@code LocomotionMotion#frameDistance}. The finding that survives from here is
     * that {@code getDeltaMovement()} is not the travel — it reported 0.051 against a measured
     * 0.093 blocks/tick, so the position delta is the only truthful source. Part 11.
     */

    public boolean walkStateForReport() {
        return this.walkingForAnimation;
    }

    /** The raw per-tick threshold decision, before the hysteresis. Diagnostic only. */
    public boolean rawMovingForReport() {
        return this.rawMoving;
    }

    /** Last tick the raw decision was true. Diagnostic only. */
    public int lastMovingTickForReport() {
        return this.lastMovingTick;
    }

    private boolean rawMoving = false;

    /**
     * 4.13 좌석 높이. {@code Boat.positionRider} 가 이 값을 더한다 — 승객 높이는 쓰지 않는다.
     *
     * <p>서버와 클라이언트가 모두 {@code positionRider} 를 부르고 둘 다 같은 파라미터를 읽으므로
     * 불일치가 생기지 않는다. 파라미터는 {@code ParamSyncPacket} 으로 동기화된다.
     */
    @Override
    public double getMyRidingOffset() {
        return isPassenger() ? AnimParams.SIT_RIDING_OFFSET.get() : super.getMyRidingOffset();
    }

    /**
     * 4.14. {@code Boat.clampRotation} 이 지운 {@code yHeadRot} 하나를 되살린다.
     *
     * <h2>값이 죽는 지점 (바이트코드 확인)</h2>
     *
     * {@code Entity.rideTick} 은 {@code setDeltaMovement(ZERO)} → {@code if (canUpdate()) tick()}
     * → {@code if (isPassenger()) getVehicle().positionRider(this)} 순서다.
     * {@code Boat.positionRider} 는 오프셋 184 에서 {@code clampRotation} 을 부르고, 그 메서드는
     * <ol>
     *   <li>{@code passenger.setYBodyRot(boat.getYRot())} — 몸통을 배에 맞춘다,</li>
     *   <li>{@code yRot} 을 배 기준 ±105° 로 자른다,</li>
     *   <li>{@code passenger.setYHeadRot(passenger.getYRot())} — <b>여기서만 값이 죽는다</b></li>
     * </ol>
     * 를 한다. {@code xRot} 은 건드리지 않으므로 pitch 는 손댈 것이 없다. 몸통 정렬도 1번이 이미
     * 하므로 별도 추종을 만들지 않는다.
     *
     * <h2>왜 이 두 지점인가</h2>
     *
     * {@link #tick()} 은 {@code super.rideTick()} <b>안에서</b> 불리므로, 거기서 보존한 값은
     * 바닐라 Goal → {@code LookControl} (서버) 또는 {@code lerpHeadTo} 보간 (클라이언트) 이 막
     * 계산해 넣은 값이다. {@code positionRider} 는 그 뒤에 오고, 복원은 그보다 더 뒤다.
     *
     * <p>서버·클라이언트를 가르지 않는다. {@code positionRider} 는 양쪽에서 모두 돌아 양쪽의
     * {@code yHeadRot} 을 똑같이 지우므로, 한쪽만 고치면 다른 쪽이 어긋난다.
     *
     * <h2>전달은 바닐라가 한다</h2>
     *
     * {@code ServerLevel.tick} 은 {@code ServerChunkCache.tick}(추적자 방송, 오프셋 267)을
     * {@code entityTickList.forEach}(엔티티 틱, 오프셋 400)보다 <b>먼저</b> 부른다. 그래서 틱 N
     * 끝에 복원한 값이 틱 N+1 방송이 읽는 값이다. {@code ServerEntity.sendChanges} 의 승객 분기는
     * 회전 패킷을 보낸 뒤 {@code goto} 로 머리 블록에 합류하므로 탑승 중에도
     * {@code ClientboundRotateHeadPacket} 이 나가고, 클라이언트는 {@code lerpHeadTo(f, 3)} 으로
     * 3틱에 걸쳐 보간한다 — 모든 바닐라 몹과 같은 격자다.
     */
    private float ridingHeadYaw;

    @Override
    public void rideTick() {
        super.rideTick();
        if (isPassenger()) {
            setYHeadRot(Mth.rotateIfNecessary(this.ridingHeadYaw, this.yBodyRot,
                    getMaxHeadYRot()));
        }
    }

    /**
     * 4.12 킁킁 재생 시간. <b>클라이언트 표시 상태다.</b>
     *
     * <p>4.11 피격의 {@code hurtTime} 과 같은 규약 — 서버가 사건 하나를 방송하면 클라이언트가
     * 카운터를 세팅하고 매 틱 줄인다. 동기화 필드도, 순번도, 전용 패킷도 아니다.
     */
    private int sniffTime;

    public int getSniffTime() {
        return this.sniffTime;
    }

    /** 4.8 공격 재생 시간. {@link #sniffTime} 과 같은 규약이다. */
    private int attackTime;

    public int getAttackTime() {
        return this.attackTime;
    }

    /** 4.9 소닉붐 재생 시간. 같은 규약. */
    private int sonicTime;

    public int getSonicTime() {
        return this.sonicTime;
    }

    /**
     * 공격 쿨다운 두 개. <b>서버 정본이고 여기 말고는 어디에도 없다</b> — Goal 은 카운터를 갖지
     * 않고 이 값을 읽고 쓰기만 한다. 서버에서만 감소한다.
     *
     * <p>하나로 합쳐 쓰던 때에는 소닉붐용 100틱이 근접에도 걸려, 18틱 모션이 끝난 뒤 사거리
     * 안에 붙어 선 채 81틱을 아무 것도 하지 않았다(실측: 공격 t=108 · 208 · 308, 간격 100틱).
     * 두 동작의 적정 간격이 다르므로 두 값으로 나눈다.
     *
     * <ul>
     *   <li>{@code meleeCooldown} — 근접 공격 방송 시점에 19틱. 모션이 18틱이므로 이 값이 곧
     *       <b>공격 시작 간격</b>이고 추가 대기가 아니다. 소닉 방출 시점에도 26틱이 걸린다 —
     *       방출(34틱) + 26 = 60틱, 즉 소닉 모션이 끝나는 순간 근접이 풀린다.</li>
     *   <li>{@code sonicCooldown} — <b>소닉 방출 시점에만</b> 100틱. 근접은 이 값을 건드리지
     *       않으므로 근접을 아무리 반복해도 소닉 주기가 밀리지 않는다. 시작 간격은
     *       34 + 100 = 134틱이다.</li>
     * </ul>
     */
    private int meleeCooldown;
    private int sonicCooldown;

    public boolean isMeleeOnCooldown() {
        return this.meleeCooldown > 0;
    }

    public void startMeleeCooldown(int ticks) {
        this.meleeCooldown = ticks;
    }

    public boolean isSonicOnCooldown() {
        return this.sonicCooldown > 0;
    }

    public void startSonicCooldown(int ticks) {
        this.sonicCooldown = ticks;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_SNIFF) {
            this.sniffTime = (int) Math.round(AnimRegistry.SNIFF_LENGTH_TICKS);
        } else if (id == EVENT_ATTACK) {
            this.attackTime = (int) Math.round(AnimRegistry.ATTACK_PLAY_TICKS);
        } else if (id == EVENT_SONIC) {
            this.sonicTime = (int) Math.round(AnimRegistry.SONIC_LENGTH_TICKS);
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (isPassenger()) {
            // super.rideTick() 안에서 불렸다면 positionRider 는 아직 오지 않았다. 지상에서는
            // 이 분기가 그냥 최신값을 들고 있을 뿐이고 복원도 일어나지 않는다.
            this.ridingHeadYaw = getYHeadRot();
        }
        if (level().isClientSide) {
            if (this.sniffTime > 0) {
                this.sniffTime--;
            }
            if (this.attackTime > 0) {
                this.attackTime--;
            }
            if (this.sonicTime > 0) {
                this.sonicTime--;
            }
        } else {
            if (this.meleeCooldown > 0) {
                this.meleeCooldown--;
            }
            if (this.sonicCooldown > 0) {
                this.sonicCooldown--;
            }
        }
    }

    public boolean isWalkingForAnimation() {
        // 4.13. 탈것에 타면 몹 좌표가 탈것을 따라 움직여 이동으로 잡힌다. 별도 게이트를 두면
        // 조건이 둘로 갈라져 어긋나므로 여기 하나로 막는다.
        if (isPassenger()) {
            this.walkingForAnimation = false;
            this.rawMoving = false;
            return false;
        }
        // Verification stimulus. Placed before the hysteresis so that clearing it hands control
        // straight back to the real test - which is what makes walk_force 1 -> 0 a clean,
        // repeatable idle transition rather than one that has to wait out the grace period.
        if (AnimParams.WALK_FORCE.get() >= 0.5D) {
            this.lastMovingTick = this.tickCount;
            this.walkingForAnimation = true;
            this.rawMoving = true;
            return true;
        }
        double vx = Math.abs(getDeltaMovement().x);
        double vz = Math.abs(getDeltaMovement().z);
        double avg = (vx + vz) / 2.0D;
        this.rawMoving = avg >= AnimParams.WALK_MOVE_THRESHOLD.get();
        if (avg >= AnimParams.WALK_MOVE_THRESHOLD.get()) {
            this.lastMovingTick = this.tickCount;
            this.walkingForAnimation = true;
        } else if (this.walkingForAnimation && this.lastMovingTick != Integer.MIN_VALUE
                && this.tickCount - this.lastMovingTick >= AnimParams.WALK_STOP_GRACE.get()) {
            this.walkingForAnimation = false;
        }
        return this.walkingForAnimation;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
