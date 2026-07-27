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
    }

    // ---- axis verification harness (T1 only) -------------------------------------------------

    /** @return {@code "<bone>:<axis>"}, or an empty string when no axis test is active. */
    public String getAxisTest() {
        return this.entityData.get(DATA_AXIS_TEST);
    }

    public void setAxisTest(String value) {
        this.entityData.set(DATA_AXIS_TEST, value);
    }

    // ---- GeoEntity --------------------------------------------------------------------------

    /**
     * C1 only. Design doc Part 4.2.
     *
     * <p>{@code vital} is registered as an unconditional loop that always returns
     * {@link PlayState#CONTINUE}. There is no code path that stops it, by design: Part 4.1 원칙 6
     * and Part 10.5 both forbid stopping C1. C2 (locomotion) and C3 (action) are separate
     * controllers and arrive in T5 and T6.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, AnimRegistry.CONTROLLER_VITAL, 0,
                state -> state.setAndContinue(AnimRegistry.VITAL)));
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
        if (state.isMoving()) {
            return state.setAndContinue(AnimRegistry.WALK_LOOP);
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
