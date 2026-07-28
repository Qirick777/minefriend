package com.wardengirl.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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
     * 4.13 문제 3. 탑승 중 방향을 탈것에 맞춘다. <b>클라이언트에서만 돈다.</b>
     *
     * <h2>왜 super 뒤인가</h2>
     *
     * {@code Entity.rideTick} 은 {@code tick()} → {@code getVehicle().positionRider(this)} 순서다
     * (바이트코드 확인). {@code tick()} 안에서 서버가 보낸 {@code lerpYRot} 보간이 {@code yRot} 을
     * 되돌리고, 그 뒤 {@code Boat.clampRotation} 이 {@code yBodyRot = 탈것yaw},
     * {@code yHeadRot = yRot} 을 쓴다. 그래서 <b>둘 다 끝난 뒤</b>에 밀어야 우리 값이 살아남는다.
     * 매 틱 다시 밀리므로 한 번 쓰고 마는 것이 아니라 계속 보간이 이어진다.
     *
     * <h2>왜 yRot 인가 — 측정 결과</h2>
     *
     * 보트를 0°→45°→90° 로 돌린 900틱 창에서 {@code yBodyRot} 은 탈것 {@code yRot} 과 모든 행에서
     * 일치했고(보간 중인 +31.5 / +63.0 포함), {@code yRot} 은 +0.000 에 얼어 있었다. 화면에서
     * 옆을 보는 것은 몸통이 아니라 고개이고, 그 각도는
     * {@code netHeadYaw = yHeadRot − yBodyRot = yRot − 탈것yaw} 라는 <b>상수</b>였다. 그래서
     * {@code yRot} 을 탈것 쪽으로 밀면 그 상수가 0 으로 수렴한다. {@code yBodyRot} 을 밀면 이미
     * 같은 값이라 아무 일도 일어나지 않는다.
     *
     * <h2>서버 판정 무영향</h2>
     *
     * 몹의 회전은 서버 → 클라이언트 단방향이다. 클라이언트가 쓴 {@code yRot} / {@code yHeadRot} 은
     * 어떤 패킷으로도 서버에 올라가지 않으므로 서버의 {@code getViewVector}, 넉백 방향,
     * {@code clampRotation} 의 ±105 기준은 전부 그대로다.
     */
    @Override
    public void rideTick() {
        super.rideTick();
        if (!level().isClientSide || !isPassenger()) {
            return;
        }
        double follow = AnimParams.SIT_BODY_FOLLOW.get();
        if (follow <= 0.0D) {
            return;
        }
        net.minecraft.world.entity.Entity vehicle = getVehicle();
        if (vehicle == null) {
            return;
        }
        // 탈것 종류를 가리지 않는다. 보트는 자체 yRot 을 갖고, 말/마인카트도 Entity.getYRot() 이
        // 진행 방향이다. 다만 말처럼 LivingEntity 인 탈것은 GeckoLib 이 렌더 단계에서 몸통을
        // 탈것의 yBodyRot 으로 따로 덮으므로 이 보정과 겹친다 - 보트에서 통과한 뒤 본다.
        float rate = (float) (AnimParams.SIT_BODY_FOLLOW_RATE.get() * follow);
        // 4.14 (2). 결과를 반드시 정규화한다. 없으면 고정점이 0 이 아니라 +360 이다 -
        // wrapDegrees(0 - 359.99) 가 +0.01 이라 359.99 에서도 계속 위로 올라가기 때문이다.
        // 측정에서 yRot 이 +360.000 에 park 했고, 그 값이 clampRotation 을 통해 yHeadRot 으로
        // 흘러 GeckoLib 의 netHeadYaw = headRot - bodyRot = 360 이 됐다. GeckoLib 은 비-LivingEntity
        // 탈것에서 그 차를 감싸지 않으므로 ±75 클램프가 -75 로 때려박았고, 화면에서는 고개가
        // 0 과 -75 를 오갔다.
        float next = net.minecraft.util.Mth.wrapDegrees(getYRot()
                + net.minecraft.util.Mth.wrapDegrees(vehicle.getYRot() - getYRot()) * rate);
        setYRot(next);
        // 4.14 (1). setYHeadRot(next) 를 뺐다. 그것이 (2) 의 증상을 만든 경로였고, 이제 head 본을
        // 우리가 직접 쓰므로 엔티티 yHeadRot 을 몸통에 정렬시킬 이유가 없다. 우리 코드에서
        // yHeadRot 을 읽는 곳은 계측 덤프뿐이고 - 4.12 정면 각도 판정은 yBodyRot 기준이다 -
        // 바닐라 쪽은 Boat.clampRotation 이 매 틱 yHeadRot = yRot 로 덮어쓰므로 결과가 같다.
    }

    /**
     * 4.14 조사용. 탑승 중 서버의 {@code yHeadRot} 을 <b>두 지점에서</b> 찍는다.
     *
     * <p>{@code aiStep()} 직후는 Goal → {@code LookControl} 이 막 쓴 값이고,
     * {@code tick()} 끝은 오프셋 386 의 {@code tickHeadTurn} 까지 지난 값이다. 둘을 비교하면
     * "Goal 이 안 돈다"와 "돌지만 뒤에서 되돌린다"가 갈린다. <b>계측 전용이다.</b>
     */
    private float headAfterAiStep = Float.NaN;
    private int probeLogged = 0;

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && isPassenger()) {
            this.headAfterAiStep = getYHeadRot();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && isPassenger() && this.probeLogged < 200) {
            this.probeLogged++;
            com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "[probe] t=%d aiStep=%.3f tickEnd=%.3f yRot=%.3f yBodyRot=%.3f vehYRot=%.3f",
                    this.tickCount, this.headAfterAiStep, getYHeadRot(), getYRot(), this.yBodyRot,
                    getVehicle() == null ? Float.NaN : getVehicle().getYRot()));
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
