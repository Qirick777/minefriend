package com.wardengirl.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
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
                .add(Attributes.MAX_HEALTH, 20.0D)
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
            this.attackTime = (int) Math.round(AnimRegistry.ATTACK_LENGTH_TICKS);
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
