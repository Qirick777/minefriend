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
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
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
        // 4.12 킁킁. 플래그가 없으므로 두 시선 Goal 과 동시에 돈다 — 킁킁 중에도 고개는 계속
        // 움직인다. 우선순위는 시선보다 아래에 둔다.
        this.goalSelector.addGoal(9, new WardenGirlSniffGoal(this));
        // T7 임시 — 좀비를 향해 걸어가 사거리에서 18틱 공격 모션만 낸다. 피해 없음.
        // 지울 때는 이 한 줄과 WardenGirlAttackGoal 파일만 지우면 된다.
        this.goalSelector.addGoal(3, new WardenGirlAttackGoal(this));
        // T8 임시 — 근접이 불가하거나 전방에 좀비가 몰리면 소닉붐. 피해 없음.
        // 지울 때는 이 한 줄과 WardenGirlSonicBoomGoal 파일만 지우면 된다.
        this.goalSelector.addGoal(2, new WardenGirlSonicBoomGoal(this));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    // ---- T5 임시 이동 AI 토글 -----------------------------------------------------------------

    /**
     * The stroll goal, held so it can be added and removed. Default <b>off</b>.
     *
     * <p>P1-T5 grants this purely so the walk cycle can be looked at; it is not the phase-2
     * movement AI. Kept as one instance rather than reconstructed on each toggle so that removing
     * it removes the same object that was added — {@code GoalSelector.removeGoal} matches by
     * identity.
     */
    private WaterAvoidingRandomStrollGoal strollGoal;
    private boolean movementAiEnabled = false;

    /** @return the new state */
    public boolean setMovementAi(boolean enabled) {
        if (enabled == this.movementAiEnabled) {
            return this.movementAiEnabled;
        }
        if (this.strollGoal == null) {
            this.strollGoal = new WaterAvoidingRandomStrollGoal(this, 1.0D);
        }
        if (enabled) {
            this.goalSelector.addGoal(6, this.strollGoal);
        } else {
            this.goalSelector.removeGoal(this.strollGoal);
            this.getNavigation().stop();
        }
        this.movementAiEnabled = enabled;
        return enabled;
    }

    public boolean isMovementAiEnabled() {
        return this.movementAiEnabled;
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
        return target != null && target.isAlive() && !target.isRemoved() && canAttack(target);
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
