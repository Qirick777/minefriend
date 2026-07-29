package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AnimRegistry;
import com.wardengirl.anim.AxisConvention;
import com.wardengirl.anim.Bones;
import com.wardengirl.anim.ClipSampler;
import com.wardengirl.anim.ClipStructureCheck;
import com.wardengirl.anim.JsonAxisConvention;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.molang.MolangParser;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GeoModel for the WardenGirl. Design doc Part 3.5.
 *
 * <p>Three things happen here, in this order, and the order is load-bearing:
 * <ol>
 *   <li>{@link #applyMolangQueries} publishes {@code query.wg_*} so the json formulas have their
 *       amplitudes and their clock;</li>
 *   <li>GeckoLib runs the animation controllers, which is where C1 {@code vital} is applied;</li>
 *   <li>{@link #setCustomAnimations} runs <em>after</em> that and adds the static pose offsets and
 *       the T1 axis-test harness.</li>
 * </ol>
 * Step 3 running last was verified from GeckoLib's bytecode
 * ({@code GeoModel.handleAnimations}: {@code preAnimationSetup} → {@code tickAnimation} →
 * {@code setCustomAnimations}), which is what makes "상시 가산" in 4.3.4 implementable at all —
 * the offsets are added to whatever C1 produced rather than replacing it.
 */
public class WardenGirlModel extends GeoModel<WardenGirlEntity> {

    private static final ResourceLocation MODEL =
            new ResourceLocation(WardenGirlMod.MOD_ID, "geo/warden_girl.geo.json");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(WardenGirlMod.MOD_ID, "textures/entity/warden_girl.png");
    private static final ResourceLocation ANIMATION =
            new ResourceLocation(WardenGirlMod.MOD_ID, "animations/warden_girl.animation.json");

    @Override
    public ResourceLocation getModelResource(WardenGirlEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(WardenGirlEntity animatable) {
        return TEXTURE;
    }

    /**
     * Translucent, not cutout — the skin is a player skin and has partial alpha.
     *
     * <p>Measured: {@code warden_girl.png} contains alpha values 12, 16, 129, 160, 177 and 240 as
     * well as 0 and 255. GeckoLib's default {@code entityCutoutNoCull} is alpha-<em>tested</em>: it
     * discards below the cutoff and draws everything else fully opaque, so all six of those partial
     * values would render as solid. Vanilla draws player skins with
     * {@code RenderType.entityTranslucent} for exactly this reason.
     *
     * <h2>The tendrils are in this pass too, and they still need no-cull</h2>
     *
     * <p>They are zero-depth planes: without back-face culling off, each one would vanish when
     * viewed from behind. {@code entityTranslucent} covers that — verified in the 1.20.1 bytecode
     * rather than assumed. {@code RenderType.entityTranslucent} calls
     * {@code setCullState(RenderStateShard.f_110110_)}, the same field {@code entityCutoutNoCull}
     * passes, and {@code f_110110_} is {@code new CullStateShard(false)} whose setup runnable is
     * {@code RenderSystem.disableCull()}. The builder's default is {@code f_110158_} =
     * {@code CullStateShard(true)}, which is what the separate {@code entity_translucent_cull}
     * variant falls through to — its existence as a distinct type is the corroborating evidence
     * that plain {@code entity_translucent} does not cull.
     */
    @Override
    public RenderType getRenderType(WardenGirlEntity animatable, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public ResourceLocation getAnimationResource(WardenGirlEntity animatable) {
        return ANIMATION;
    }

    /**
     * The single point where a json keyframe's sign becomes the Part 4.0 bone convention.
     *
     * <p>See {@link JsonAxisConvention} for the measurement and for why this is not in
     * {@code AxisConvention}. This is the only call site, and it is on the json path only — the
     * static offsets, the look addition and the headgear spring never come through here.
     *
     * <p>Cached because GeckoLib calls this every time a controller resolves an animation by name,
     * which is once per {@code setAndContinue} — i.e. every tick the predicate runs. Rebuilding
     * the whole keyframe tree that often would be wasteful, and handing out a fresh object each
     * time would also defeat the controller's own "is this the same animation" identity check.
     */
    @Override
    public software.bernie.geckolib.core.animation.Animation getAnimation(
            WardenGirlEntity animatable, String name) {
        return this.convertedAnimations.computeIfAbsent(name,
                k -> JsonAxisConvention.convert(super.getAnimation(animatable, k)));
    }

    private final Map<String, software.bernie.geckolib.core.animation.Animation>
            convertedAnimations = new HashMap<>();

    /**
     * Publishes the tuning parameters and the animation clock to Molang.
     *
     * <p>{@code query.wg_time} is <b>ticks, and it does not rewind.</b> GeckoLib's own
     * {@code query.anim_time} is seconds and resets at each loop boundary; C1 layers three periods
     * that do not share a common multiple (60 / 53 / 79 ticks), so a resetting clock would snap all
     * three back into phase on every loop — the exact repetition 4.3.3 is designed to avoid.
     * Feeding a monotonic tick count sidesteps that, and has the side benefit that the json
     * formulas read identically to the tables in Part 4.3.
     */
    @Override
    public void applyMolangQueries(WardenGirlEntity animatable, double animTime) {
        super.applyMolangQueries(animatable, animTime);

        MolangParser parser = MolangParser.INSTANCE;
        float partialTick = Minecraft.getInstance().getPartialTick();
        double timeTicks = animatable.tickCount + partialTick;
        parser.setValue("query.wg_time", () -> timeTicks);

        for (AnimParams.Param p : AnimParams.all().values()) {
            if (p.molang != null) {
                parser.setValue("query." + p.molang, p::get);
            }
        }
        // 4.4.2 상체 기울기. addShape 는 molang 이름을 갖지 않으므로 여기서 직접 노출한다 —
        // 이 값은 크기가 아니라 자세라서 프리셋 스윕 대상이 아니고, 그것이 addShape 를 쓴 이유다.
        parser.setValue("query.wg_walk_body_lean", AnimParams.WALK_BODY_LEAN::get);
    }

    /**
     * Runs {@link ClipStructureCheck} once, the first frame a WardenGirl is drawn.
     *
     * <p>Not at mod init: {@code GeckoLibCache} has not loaded the animation file yet at that point,
     * and a check that silently finds nothing to check is worse than no check. Here the clips are
     * guaranteed resolvable, and the flag makes it cost one boolean read per frame afterwards.
     *
     * <p>Once per model instance, not once per resource reload — {@link #convertedAnimations} is
     * never cleared, so an F3+T edit does not re-trigger this. Re-checking after a reload would be
     * better and is not implemented.
     */
    private boolean structureChecked = false;

    private void checkClipStructure(WardenGirlEntity animatable) {
        if (this.structureChecked) {
            return;
        }
        this.structureChecked = true;
        // walk -> idle is the transition that was measured broken. idle -> walk is checked too:
        // it happens to be covered today, but "happens to be" is not a guarantee anyone stated.
        ClipStructureCheck.verifyTransitionCoverage(
                AnimRegistry.WALK, getAnimation(animatable, AnimRegistry.WALK),
                AnimRegistry.IDLE, getAnimation(animatable, AnimRegistry.IDLE));
        ClipStructureCheck.verifyTransitionCoverage(
                AnimRegistry.IDLE, getAnimation(animatable, AnimRegistry.IDLE),
                AnimRegistry.WALK, getAnimation(animatable, AnimRegistry.WALK));
        // base no longer carries the locomotion channels (it polluted the transition snapshot), so
        // the "nothing accumulates" guarantee now depends on which locomotion clip is playing.
        // Each one is checked separately: a gap that exists only during signtest is still a gap.
        software.bernie.geckolib.core.animation.Animation base =
                getAnimation(animatable, AnimRegistry.BASE);
        for (String clip : new String[]{AnimRegistry.WALK, AnimRegistry.IDLE,
                AnimRegistry.SIGN_TEST}) {
            ClipStructureCheck.verifyAccumulationCoverage(base, clip,
                    getAnimation(animatable, clip));
        }
    }

    @Override
    public void setCustomAnimations(WardenGirlEntity animatable, long instanceId,
                                    AnimationState<WardenGirlEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        checkClipStructure(animatable);

        // The axis harness is a measuring tool: when it is on it owns the whole rig, so the static
        // offsets are deliberately skipped. Otherwise a "+45° on one axis" reading would silently
        // be 45° plus a 4° offset, and the number on screen would not be the number reported.
        if (applyAxisTest(animatable)) {
            for (HeadgearSpring spring : springsFor(animatable)) {
                spring.reset();
            }
            return;
        }
        // Every verifier in this method keeps its state in statics, and setCustomAnimations runs
        // once per RENDERED ENTITY. A second warden girl in the world therefore interleaves a
        // second animation state into the same accumulators - measured, and it produced a full page
        // of plausible numbers. EntityLock pins the measurement to one entity and reports the rest.
        // Computed here because applyLocomotionMotion already feeds BoneTrace.
        boolean measured = EntityLock.accepts(animatable.getId());
        // C2 first, exactly where the controller used to write it, so every layer below sits on
        // top of the same value it always did. walkOnly is what C2 contributed on the four axes it
        // shares with C3 - now known directly instead of read back off the bone.
        double[] walkOnly = AnimParams.C2_SOURCE.get() < 0.5D
                ? readBlendAxes()               // 이관 전: 컨트롤러가 이미 본에 썼다
                : applyLocomotionMotion(animatable, animationState);
        VitalMotion.Contribution vital = applyVitalMotion(animatable);
        if (measured && VitalCheck.isRunning()) {
            VitalCheck.sample(readAllBones(), readAllPositions(), animatable.tickCount,
                    Minecraft.getInstance().getPartialTick());
        }
        ActionInfo action = applyActionMotion(animatable, walkOnly);
        applyStaticOffsets();
        applyLook(animationState);
        applyTurnLean(animatable);
        applyHeadgearSpring(animatable);
        applyOverlayVisibility();
        reportParamChange();
        if (measured && BlendCheck.isRunning()) {
            BlendCheck.sample(walkOnly,
                    vital == null ? null : new double[]{vital.bodyRotY()},
                    action.c3(), action.eff(), action.weight(), readAllBones(),
                    animatable.walkStateForReport(), animatable.tickCount);
        }
        if (measured && BoneTrace.isRunning()) {
            // Read-only: isWalkingForAnimation() advances the hysteresis, and calling it here would
            // run that state machine at frame rate on top of its normal per-frame call.
            // 12px lever, sole at y=0, /16 to blocks. The drawn angle, so it includes every
            // amplitude scale actually applied.
            double legDeg = getBone(Bones.LEG_RIGHT)
                    .map(b -> AxisConvention.toDeg(b.getRotX())).orElse(0.0D);
            double footArc = 12.0D * Math.sin(Math.toRadians(legDeg)) / 16.0D;
            BoneTrace.noteMovement(animatable.getX(), animatable.getZ(),
                    animatable.getDeltaMovement().horizontalDistance(),
                    footArc, animatable.walkStateForReport(), animatable.tickCount);
            // The slip ratio is measured at frame rate, against the same rendered position the
            // phase is driven from - numerator and denominator then describe one motion.
            float pt = Minecraft.getInstance().getPartialTick();
            BoneTrace.noteFrameSlip(
                    net.minecraft.util.Mth.lerp(pt, animatable.xOld, animatable.getX()),
                    net.minecraft.util.Mth.lerp(pt, animatable.zOld, animatable.getZ()),
                    footArc, legDeg, animatable.walkStateForReport(), animatable.tickCount);
            BoneTrace.noteWalkState(animatable.walkStateForReport(),
                    animatable.rawMovingForReport(), animatable.lastMovingTickForReport(),
                    animatable.tickCount);
            BoneTrace.sample(readAllBones(), readAllPositions(), animatable.tickCount);
        }
    }



    // ---- 4.6 시선 (선택지 A: Java 가산) ----------------------------------------------------------

    /**
     * Adds the vanilla look direction to {@code head}, on top of C1 and the static offsets.
     *
     * <h2>Why this has to exist at all</h2>
     *
     * GeckoLib computes the look for us and hands it over as {@code EntityModelData}, but
     * <b>nothing consumes it</b> unless the model extends {@code DefaultedEntityGeoModel} — and
     * that class <em>assigns</em> {@code head.setRotX/setRotY}, which would delete C1's breathing
     * correction every frame. So the data arrives and is dropped. Measured before this method
     * existed: the entity's {@code yHeadRot} moved through 374° while {@code head}'s bone yRot held
     * exactly one distinct value, {@code -0.000}, across 729 samples.
     *
     * <h2>Added, not assigned</h2>
     *
     * Same rule as 4.3.4. C1 has already written this frame's values into {@code head}; assigning
     * here would erase them.
     *
     * <h2>No sign flip</h2>
     *
     * {@code EntityModelData} is built from {@code -netHeadYaw} and {@code -headPitch} (verified in
     * the 4.8.4 bytecode), i.e. GeckoLib has already put them in the bone convention this project
     * uses. Per 4.0.1 nothing is negated again anywhere.
     *
     * <h2>4.14 — 여기서 다시 계산하지 않는다</h2>
     *
     * clamp 도 감쇠도 없다. 그 둘은 바닐라가 이미 한 일을 렌더 프레임에서 되풀이하는 것이었다:
     * 야우 한계 75°는 {@code Mob.getMaxHeadYRot()} 기본값과 같은 수이고 {@code LookControl} 이
     * 서버에서 이미 자르며, 감쇠는 {@code LookControl.rotateTowards}(틱당 10°)와
     * {@code lerpHeadTo(f, 3)} 이 하는 일이다. 탑승 여부에 따른 별도 출처도 없다 —
     * {@code WardenGirlEntity.rideTick} 이 {@code yHeadRot} 을 되살리므로
     * {@code EntityModelData} 하나로 지상과 탑승이 같은 경로를 탄다.
     *
     * <p>렌더 clamp 를 없애면 {@code netHeadYaw} 이 ±180 을 넘는 언랩 값일 때의 문제도 사라진다.
     * 순수 회전이라 350°와 −10°가 같은 그림이고, 문제는 그 값을 <em>자를 때</em>만 생겼다.
     *
     * <p>{@code look_gain} 은 남는다. 배율 1.0 은 항등이지만 {@code BlendCheck} 가 head.yRot
     * 잔차를 판정하려면 시선을 0 으로 끌 수 있어야 한다 — 그 유효 조건이 이 노브다.
     */
    private void applyLook(AnimationState<WardenGirlEntity> animationState) {
        EntityModelData look = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
        if (look == null) {
            return;
        }
        double gain = AnimParams.LOOK_GAIN.get();
        addRotY(Bones.HEAD, look.netHeadYaw() * gain);
        addRotX(Bones.HEAD, look.headPitch() * gain);
    }


    // ---- 4.4.3 방향 전환 기울임 --------------------------------------------------------------

    /** Per-entity turn state, keyed like the springs and the damper. */
    private final Map<Integer, TurnLean> turnLeans = new HashMap<>();

    /**
     * Leans {@code hip} into the turn. Design doc 4.4.3.
     *
     * <p>On {@code hip}, not {@code body}, and that is a deliberate exception to 4.0.5's "hip must
     * not rotate". 4.0.5 forbids hip rotation <em>in the idle pose</em>, because a degree of hip
     * roll swings the sole through a visible arc at the end of a 12px lever while the feet are
     * supposed to be planted. During a turn the feet are already travelling, and leaning the whole
     * lower body — legs included — is exactly what the centrifugal compensation is meant to look
     * like. Leaning {@code body} instead would tilt the torso off a stationary pelvis, which reads
     * as the mob folding at the waist rather than banking.
     *
     * <p>Sign: 4.4.3 says 좌회전 +3, 우회전 −3. A left turn is yaw <em>decreasing</em> in
     * Minecraft's convention (yaw 0 = +Z, 90 = −X), so the raw per-tick yaw delta is negated to get
     * the lean direction.
     */
    private void applyTurnLean(WardenGirlEntity animatable) {
        TurnLean lean = this.turnLeans.computeIfAbsent(animatable.getId(), k -> new TurnLean());
        if (this.turnLeans.size() > MAX_TRACKED_ENTITIES) {
            this.turnLeans.clear();
        }
        float partialTick = Minecraft.getInstance().getPartialTick();
        double deg = lean.advanceTo(animatable.tickCount, animatable.yBodyRot,
                animatable.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6D, partialTick);
        BoneTrace.noteTurnLean(deg, lean.rawRate());
        addRotZ(Bones.HIP, deg);
    }

    // ---- 4.5 headgear 스프링 -------------------------------------------------------------------

    /**
     * One spring per entity.
     *
     * <p>The model is created once by the renderer and shared by every WardenGirl on screen, so a
     * single spring instance would have all of them driving the same state — two mobs looking in
     * different directions would fight over one decoration angle. Keyed by entity id instead.
     */
    private final Map<Integer, HeadgearSpring[]> springs = new HashMap<>();

    /** Bounded so despawned entities cannot leak; springs are cheap to re-seed. */
    private static final int MAX_TRACKED_ENTITIES = 64;


    /** {right, left}, in the order of {@link Bones#HEADGEAR}. */
    private HeadgearSpring[] springsFor(WardenGirlEntity animatable) {
        if (this.springs.size() > MAX_TRACKED_ENTITIES) {
            this.springs.clear();
        }
        return this.springs.computeIfAbsent(animatable.getId(), k -> new HeadgearSpring[]{
                new HeadgearSpring(1.0D),
                new HeadgearSpring(1.0D - AnimParams.HEADGEAR_ASYMMETRY.get())});
    }

    private void applyHeadgearSpring(WardenGirlEntity animatable) {
        Optional<GeoBone> maybeHead = getBone(Bones.HEAD);
        if (maybeHead.isEmpty()) {
            return;
        }
        // head's rotation *after* C1, the static offset and the look addition — the decoration is
        // lagging behind the pose actually being drawn, not behind some earlier stage of it.
        double[] target = AxisConvention.readDegrees(maybeHead.get());
        float partialTick = Minecraft.getInstance().getPartialTick();
        HeadgearSpring[] pair = springsFor(animatable);

        for (int side = 0; side < 2; side++) {
            Optional<GeoBone> maybeBone = getBone(Bones.HEADGEAR.get(side));
            if (maybeBone.isEmpty()) {
                continue;
            }
            HeadgearSpring spring = pair[side];
            spring.advanceTo(animatable.tickCount, target);
            GeoBone bone = maybeBone.get();

            // Base pose. This used to be baked into the cube's `rotation` in geo.json, which is
            // read once at model load — no command could move it, and getting the shape right cost
            // a full rebuild-and-capture round trip each time. Here it is a parameter.
            //
            // Signs: +zRot moves the point above the pivot toward the mob's LEFT (+X), so outward
            // is negative on the right tendril and positive on the left. +xRot moves it toward the
            // BACK (-Z), so a positive tilt leans the tips backward. Part 4.0.1; nothing is negated
            // anywhere else.
            double outward = side == 0 ? -1.0D : 1.0D;
            double splay = AnimParams.HEADGEAR_SPLAY.get() * outward;
            double tilt = AnimParams.HEADGEAR_TILT.get();
            double roll = AnimParams.HEADGEAR_ROLL.get() * outward;

            // ASSIGNED, not added — see HeadgearSpring's class doc. This is what lets the tendrils
            // keep their Part 10.4 status of having no animation channel at all. The base pose is
            // part of the assigned value, not a second write on top of it.
            bone.setRotX(AxisConvention.toRad(tilt + spring.output(0, partialTick, target[0])));
            bone.setRotY(AxisConvention.toRad(roll + spring.output(1, partialTick, target[1])));
            bone.setRotZ(AxisConvention.toRad(splay + spring.output(2, partialTick, target[2])));

            // Nothing touches visibility. The tendrils are ordinary cubes on the one texture the
            // model already draws, so there is no second pass to hide anything from.

            // Attachment point, as a translation of the whole bone. The render applies
            // T(pos)·T(pivot)·R·T(-pivot), so a root vertex sitting on the pivot lands at
            // pivot + pos whatever the rotation — moving the bone is exactly equivalent to moving
            // the pivot, and unlike the pivot it is settable at runtime.
            // Scale is applied between translateToPivotPoint and translateAwayFromPivotPoint, so
            // it is centred on the pivot and the root stays attached to the head.
            float scale = (float) AnimParams.HEADGEAR_SCALE.get();
            bone.setScaleX(scale);
            bone.setScaleY(scale);
            bone.setScaleZ(scale);

            AxisConvention.setPositionPx(bone,
                    AnimParams.HEADGEAR_POS_X.get() * outward,
                    AnimParams.HEADGEAR_POS_Y.get(),
                    AnimParams.HEADGEAR_POS_Z.get());
            BoneTrace.noteSpring(side, spring.angles(), spring.velocities(),
                    spring.effectiveStiffness());
        }
    }

    private long lastParamGeneration = -1L;

    /**
     * Prints the measured bone rotations on the first frame after any parameter change.
     *
     * <p>Part 6.3 wants {@code param set} to report "다음 프레임 영향 본 값", and Part 6.2 wants that
     * to be an observation rather than a restatement of the request. The command runs on the
     * server and cannot see a bone, so the measurement has to happen here — after C1 and after the
     * static offsets, i.e. on the values actually about to be drawn.
     */
    private void reportParamChange() {
        long generation = AnimParams.generation();
        if (generation == this.lastParamGeneration) {
            return;
        }
        boolean first = this.lastParamGeneration < 0;
        this.lastParamGeneration = generation;
        if (first) {
            return;
        }
        ParamChangeReporter.report(readAllBones(), readAllPositions());
    }

    /**
     * 4.3 C1 상시 레이어 — the Java addition that replaced the {@code vital} controller.
     *
     * <p>Why it is here rather than on a controller: {@link VitalMotion}'s class doc. The short
     * version is that {@code AnimationProcessor} adds each controller's value to the bone's
     * <em>initial snapshot</em>, not to the previous controller's result, so C2 erased C1 outright
     * — measured, seven axes at exactly 0.000 oscillation. {@code setCustomAnimations} runs after
     * every controller, so nothing can overwrite what is written here.
     *
     * <p>Skipped while {@code signtest} is playing, for the same reason
     * {@link #applyAxisTest} owns the whole rig: signtest reads a bone and expects to see the
     * constant the json put there. Up to 2° of breathing on top would make a ±0.001 comparison
     * meaningless.
     */
    private VitalMotion.Contribution applyVitalMotion(WardenGirlEntity animatable) {
        if (AnimParams.C1_SOURCE.get() < 0.5D || animatable.isSignTest()) {
            return null;
        }
        float partialTick = Minecraft.getInstance().getPartialTick();
        VitalMotion.Contribution c = VitalMotion.evaluate(animatable.tickCount + partialTick);

        addRotX(Bones.BODY, c.bodyRotX());
        addRotY(Bones.BODY, c.bodyRotY());
        addRotZ(Bones.BODY, c.bodyRotZ());
        addPosY(Bones.BODY, c.bodyPosY());
        addRotX(Bones.HEAD, c.headRotX());
        addRotZ(Bones.HEAD, c.headRotZ());
        addRotZ(Bones.ARM_RIGHT, c.armRightRotZ());
        addRotZ(Bones.ARM_LEFT, c.armLeftRotZ());
        // root.pos.y overlaps C2's walk bounce, and position is pure assignment rather than
        // addition, so C2 wins outright there. Harmless while bounce_amplitude is 0, and left
        // unresolved on purpose — Part 11.
        addPosY(Bones.ROOT, c.rootPosY());
        return c;
    }

    // ---- C2 로코모션 (직접 평가) -------------------------------------------------------------

    private final Map<Integer, LocomotionMotion> locomotions = new HashMap<>();

    /**
     * Adds the walk cycle, cross-faded against idle. Design doc 4.4.1 / 4.4.2.
     *
     * <p>See {@link LocomotionMotion} for why this is not a controller any more. The clip, the
     * keyframes and the easing are unchanged — {@link ClipSampler} reads the same converted
     * {@code walk} animation the controller was reading, through the same library code.
     *
     * @return C2's contribution on {@link #readBlendAxes}'s four axes, in that order
     */
    private double[] applyLocomotionMotion(WardenGirlEntity animatable,
                                          AnimationState<WardenGirlEntity> animationState) {
        if (this.locomotions.size() > MAX_TRACKED_ENTITIES) {
            this.locomotions.clear();
        }
        LocomotionMotion loco = this.locomotions.computeIfAbsent(animatable.getId(),
                k -> new LocomotionMotion());
        float partialTick = Minecraft.getInstance().getPartialTick();
        double now = animatable.tickCount + partialTick;
        // signtest owns the rig while it is on, the same way axistest does - the whole point is to
        // read back the constant the json put there, and a walk cycle on top would bury it.
        boolean walking = !animatable.isSignTest() && animatable.isWalkingForAnimation();

        // 4.4.2 거리 기반. 진폭은 limbSwingAmount (평활된 속도), 위상은 그 진폭이 정하는 보폭.
        //
        // limbSwingAmount is vanilla's walkAnimation.speed(), which GeckoLib interpolates for us.
        // limbSwing (walkAnimation.position()) is its integral, so either would give a
        // distance-locked phase - but the position delta is used instead because it is the one
        // quantity already proven truthful: getDeltaMovement() reported half the real travel, and
        // limbSwing carries vanilla's own min(dist*4, 1) clamp on top. Part 11.
        double swingAmount = Math.min(1.0D, Math.max(0.0D, animationState.getLimbSwingAmount()));
        double legAmp = LocomotionMotion.LEG_AMPLITUDE_DEG
                * AnimParams.WALK_LEG_AMP_SCALE.get() * swingAmount;
        // Per FRAME, from the rendered position - not per tick. See LocomotionMotion#frameDistance
        // for the measurement that forced this: a tick-quantised phase made the drawn leg a
        // 3.78-step staircase per cycle and cost 75% of the foot-slip shortfall.
        double distance = loco.frameDistance(
                net.minecraft.util.Mth.lerp(partialTick, animatable.xOld, animatable.getX()),
                net.minecraft.util.Mth.lerp(partialTick, animatable.zOld, animatable.getZ()));
        if (AnimParams.WALK_FORCE.get() >= 0.5D) {
            // walk_force makes no real movement, so a distance-driven phase would simply stop.
            // Feeding it a synthetic cruise speed keeps every window measured with it meaningful -
            // and lets a slow or fast walk be reproduced on demand. Part 6.2.
            distance = AnimParams.WALK_FORCE_SPEED.get() * loco.lastFrameTicks(now);
            swingAmount = 1.0D;
            legAmp = LocomotionMotion.LEG_AMPLITUDE_DEG * AnimParams.WALK_LEG_AMP_SCALE.get();
        }
        double weight = loco.advance(now, walking, distance, legAmp);
        // 4.13 앉기. 걷기와 배타이므로 다리에 두 자세가 겹칠 수 없다 - riding 이면 위의
        // walking 이 이미 false 다. 전이는 걷기와 같은 6틱이다.
        boolean riding = animatable.isPassenger();
        loco.advanceSit(riding, loco.lastDt());
        double sit = loco.sitWeight();
        if (sit > 0.0D) {
            double lx = AnimParams.SIT_LEG_X.get() * sit;
            double ly = AnimParams.SIT_LEG_Y.get() * sit;
            addRotX(Bones.LEG_RIGHT, lx);
            addRotY(Bones.LEG_RIGHT, ly);
            addRotX(Bones.LEG_LEFT, lx);
            addRotY(Bones.LEG_LEFT, -ly);
            double ax = AnimParams.SIT_ARM_X.get() * sit;
            double az = AnimParams.SIT_ARM_Z.get() * sit;
            addRotX(Bones.ARM_RIGHT, ax);
            addRotZ(Bones.ARM_RIGHT, az);
            addRotX(Bones.ARM_LEFT, ax);
            addRotZ(Bones.ARM_LEFT, -az);
        }
        if (BoneTrace.isRunning() && EntityLock.isSubject(animatable.getId())) {
            BoneTrace.noteSit(riding, sit);
        }
        boolean traced = BoneTrace.isRunning() && EntityLock.isSubject(animatable.getId());
        if (traced) {
            BoneTrace.noteFade(weight, loco.lastDt(), now);
        }
        if (weight <= 0.0D) {
            if (traced) {
                BoneTrace.noteLocoState(weight, swingAmount, distance, loco.phase(), legAmp,
                        Double.NaN);
            }
            return new double[4];
        }
        software.bernie.geckolib.core.animation.Animation clip =
                getAnimation(animatable, AnimRegistry.WALK);
        if (clip == null) {
            return new double[4];
        }
        ClipSampler.Pose pose = ClipSampler.sample(clip, loco.phase());
        if (traced) {
            // The clip's own leg_right x at this phase, before any amplitude scaling. Column [3] of
            // the residual attribution needs it to separate "the clip is not +-18" from "something
            // scaled it".
            double[] legRaw = pose.rotationsDeg().get(Bones.LEG_RIGHT);
            BoneTrace.noteLocoState(weight, swingAmount, distance, loco.phase(), legAmp,
                    legRaw == null ? Double.NaN : legRaw[0]);
        }
        // Amplitude scales with speed, vanilla-style. Legs and arms get their own user scale on top
        // because raising the stride without raising the arm swing reads as a limp - the two are
        // judged together on screen.
        double armScale = swingAmount * AnimParams.WALK_ARM_AMP_SCALE.get();
        double legScale = swingAmount * AnimParams.WALK_LEG_AMP_SCALE.get();
        for (Map.Entry<String, double[]> e : pose.rotationsDeg().entrySet()) {
            double amp = weight * boneAmplitude(e.getKey(), swingAmount, armScale, legScale);
            addRotX(e.getKey(), e.getValue()[0] * amp);
            addRotY(e.getKey(), e.getValue()[1] * amp);
            addRotZ(e.getKey(), e.getValue()[2] * amp);
        }
        for (Map.Entry<String, double[]> e : pose.positionsRaw().entrySet()) {
            addPositionRaw(e.getKey(), e.getValue(),
                    weight * boneAmplitude(e.getKey(), swingAmount, armScale, legScale));
        }
        double[] armR = pose.rotationsDeg().get(Bones.ARM_RIGHT);
        double[] armL = pose.rotationsDeg().get(Bones.ARM_LEFT);
        double[] body = pose.rotationsDeg().get(Bones.BODY);
        double[] head = pose.rotationsDeg().get(Bones.HEAD);
        double armAmp = weight * armScale;
        double bodyAmp = weight * swingAmount;
        // Index 4 is body.xRot, which is NOT one of the four C2/C3 shared axes - it is there for
        // 4.11's blend_walk_body_x_hurt alone. applyWalkBlend and BlendCheck index 0..3 only.
        return new double[]{
                armR == null ? 0.0D : armR[0] * armAmp,
                armL == null ? 0.0D : armL[0] * armAmp,
                body == null ? 0.0D : body[1] * bodyAmp,
                head == null ? 0.0D : head[1] * bodyAmp,
                body == null ? 0.0D : body[0] * bodyAmp};
    }

    /**
     * Per-bone amplitude multiplier for the walk clip.
     *
     * <p>Everything scales with speed (a slow walk should not twist the torso as hard as a fast
     * one — vanilla does the same). Only the arms and legs carry an extra user scale, because those
     * are the two the silhouette is judged by.
     */
    private static double boneAmplitude(String bone, double swingAmount, double armScale,
                                        double legScale) {
        return switch (bone) {
            case Bones.ARM_RIGHT, Bones.ARM_LEFT -> armScale;
            case Bones.LEG_RIGHT, Bones.LEG_LEFT -> legScale;
            default -> swingAmount;
        };
    }

    // ---- C3 액션 레이어 (직접 평가) ---------------------------------------------------------

    private final Map<Integer, ActionMotion> actions = new HashMap<>();
    /** Previous frame's hurtTime, per entity — 4.11's trigger is its rising edge. */
    private final Map<Integer, Integer> lastHurtTime = new HashMap<>();
    /** 직전 프레임의 {@code attackTime}, 엔티티별. {@link #lastSniffTime} 과 같은 규약. */
    private final Map<Integer, Integer> lastAttackTime = new HashMap<>();
    /** 직전 프레임의 {@code sonicTime}, 엔티티별. 같은 규약. */
    private final Map<Integer, Integer> lastSonicTime = new HashMap<>();
    /**
     * 직전 프레임의 {@code sniffTime}, 엔티티별. 4.11 피격의 {@link #lastHurtTime} 과 같은 규약 —
     * 서버가 올린 값의 <b>상승 에지</b>가 재생 시작이다. 거리·각도·확률은 여기서 보지 않는다.
     */
    private final Map<Integer, Integer> lastSniffTime = new HashMap<>();
    /** 중단이 시작된 시각(틱). 있으면 페이드 아웃 중이다. */
    private final Map<Integer, Double> sniffInterrupt = new HashMap<>();

    /** The four axes where C2 and C3 both write. Order is fixed; see {@link #applyWalkBlend}. */
    private double[] readBlendAxes() {
        return new double[]{
                getBone(Bones.ARM_RIGHT).map(b -> AxisConvention.toDeg(b.getRotX())).orElse(0.0D),
                getBone(Bones.ARM_LEFT).map(b -> AxisConvention.toDeg(b.getRotX())).orElse(0.0D),
                getBone(Bones.BODY).map(b -> AxisConvention.toDeg(b.getRotY())).orElse(0.0D),
                getBone(Bones.HEAD).map(b -> AxisConvention.toDeg(b.getRotY())).orElse(0.0D),
                // c2_source 0 is the pre-migration A/B path; the bone's body.xRot there is C1 and
                // C2 summed and cannot be separated, so 4.11's walk-share blend reports zero
                // rather than subtracting something it does not know. Default keep is 1.0 anyway.
                0.0D};
    }

    /**
     * Adds the C3 clip's pose, and rescales C2's share on the axes the two share.
     *
     * <h2>Why C3 is evaluated here instead of on a controller</h2>
     *
     * 걷기 + 기본 공격 동시 재생은 필수 요구사항이다 (Part 3.2 항목 4). A third controller cannot
     * deliver that — measured, the later controller erases the earlier on any shared bone. This is
     * the only place a layer survives alongside C2.
     *
     * <h2>Blend weights are applied to C2, not to C3</h2>
     *
     * {@code blend_walk_*} answers "how much of the walk survives during an attack", so it scales
     * the walk's own contribution rather than shrinking the attack. Shrinking the attack instead
     * would make the swing itself smaller, which is a different thing and would defeat the 4.8
     * amplitudes the doc fixes.
     *
     * <p>The rescale is applied as a delta ({@code c2 × (w−1)}) because the walk value is already
     * on the bone and cannot be un-written. It rides the same fade envelope as C3, so it cannot
     * snap: at envelope 0 the effective weight is exactly 1 and this is a no-op.
     */
    /**
     * What C3 did this frame, on the four axes it shares with C2. Order matches
     * {@link #readBlendAxes}: arm_right.xRot, arm_left.xRot, body.yRot, head.yRot.
     *
     * @param c3     the clip's contribution after the fade envelope, degrees
     * @param eff    the effective walk weight actually used, per channel
     * @param weight the fade envelope
     */
    private record ActionInfo(double[] c3, double[] eff, double weight) {
    }

    private ActionInfo applyActionMotion(WardenGirlEntity animatable, double[] walkOnly) {
        if (this.actions.size() > MAX_TRACKED_ENTITIES) {
            this.actions.clear();
        }
        ActionMotion action = this.actions.computeIfAbsent(animatable.getId(),
                k -> new ActionMotion());
        float partialTick = Minecraft.getInstance().getPartialTick();
        double now = animatable.tickCount + partialTick;

        // 4.11 피격 움찔. hurtTime 은 서버가 동기화하는 클라이언트 가시 값이므로, C3 의 서버 구동
        // 경로(getActionClip)를 거치지 않고 여기서 직접 에지를 잡는다. 재생 중에 또 맞으면
        // syncTo 의 seq 가 달라져 처음부터 다시 시작한다 - 독처럼 반복되는 피해가 그렇게 읽힌다.
        int hurt = animatable.hurtTime;
        Integer prevHurt = this.lastHurtTime.put(animatable.getId(), hurt);
        boolean hurtStarted = prevHurt != null && prevHurt == 0 && hurt > 0;
        if (this.lastHurtTime.size() > MAX_TRACKED_ENTITIES) {
            this.lastHurtTime.clear();
        }
        // ===== T6 반응형 idle. 판정은 서버 WardenGirlSniffGoal 이 한다 =====================
        // 여기는 표시만 한다 - 거리도, 정면각도, 확률도 보지 않는다. 서버가 방송한 엔티티 사건이
        // sniffTime 을 올렸고, 그 상승 에지가 재생 시작이다.
        int sniff = animatable.getSniffTime();
        Integer prevSniffBoxed = this.lastSniffTime.put(animatable.getId(), sniff);
        if (this.lastSniffTime.size() > MAX_TRACKED_ENTITIES) {
            this.lastSniffTime.clear();
        }
        boolean sniffStarted = sniff > (prevSniffBoxed == null ? 0 : prevSniffBoxed);
        // 킁킁은 C3 에서 가장 낮은 우선순위다. 이미 돌고 있는데 다른 클립이나 피격이 오면
        // 페이드로 끊는다. 서버 Goal 도 같은 조건에서 종료하고 남은 소리를 취소한다 - 두 값
        // 모두 동기화된 것이므로 모든 클라이언트가 같은 순간에 같은 판단을 한다.
        boolean sniffRunning = AnimRegistry.IDLE_SNIFF.equals(action.clip()) && action.isPlaying();
        if (sniffRunning && (!animatable.getActionClip().isEmpty() || animatable.hurtTime != 0)) {
            this.sniffInterrupt.put(animatable.getId(), now);
        }
        if (sniffStarted) {
            software.bernie.geckolib.core.animation.Animation sniffClip =
                    getAnimation(animatable, AnimRegistry.IDLE_SNIFF);
            // 재생 머리를 서버가 말한 위치에 놓는다. 프레임률이 틱보다 느려 시작 틱을 놓쳤거나
            // 몹이 재생 도중 화면에 들어와도 어긋나지 않는다 - 0 에서 다시 시작하면 서버가
            // 내는 소리와 화면이 벌어진다.
            double elapsed = Math.max(0.0D, AnimRegistry.SNIFF_LENGTH_TICKS - sniff);
            if (sniffClip != null && elapsed < sniffClip.length()) {
                this.sniffInterrupt.remove(animatable.getId());
                action.syncTo(-animatable.tickCount - 1, AnimRegistry.IDLE_SNIFF,
                        sniffClip.length(), now - elapsed);
                if (BoneTrace.isRunning() && EntityLock.isSubject(animatable.getId())) {
                    BoneTrace.noteSniffStart();
                }
            }
        }
        // ===== T6 끝 =====
        // ===== T7 기본 공격. 판정은 서버 WardenGirlAttackGoal 이 한다 =========================
        // C3 슬롯은 하나이므로 우선순위는 이 블록들의 순서가 정한다: 피격 > 공격 > 킁킁.
        int atk = animatable.getAttackTime();
        Integer prevAtkBoxed = this.lastAttackTime.put(animatable.getId(), atk);
        if (this.lastAttackTime.size() > MAX_TRACKED_ENTITIES) {
            this.lastAttackTime.clear();
        }
        if (atk > (prevAtkBoxed == null ? 0 : prevAtkBoxed)) {
            software.bernie.geckolib.core.animation.Animation attackClip =
                    getAnimation(animatable, AnimRegistry.ATTACK);
            double atkElapsed = Math.max(0.0D, AnimRegistry.ATTACK_LENGTH_TICKS - atk);
            if (attackClip != null && atkElapsed < attackClip.length()) {
                this.sniffInterrupt.remove(animatable.getId());
                action.syncTo(-animatable.tickCount - 2, AnimRegistry.ATTACK,
                        attackClip.length(), now - atkElapsed);
            }
        }
        // ===== T7 끝 =====
        // ===== T8 소닉붐. 판정은 서버 WardenGirlSonicBoomGoal 이 한다 ========================
        int sonic = animatable.getSonicTime();
        Integer prevSonicBoxed = this.lastSonicTime.put(animatable.getId(), sonic);
        if (this.lastSonicTime.size() > MAX_TRACKED_ENTITIES) {
            this.lastSonicTime.clear();
        }
        if (sonic > (prevSonicBoxed == null ? 0 : prevSonicBoxed)) {
            software.bernie.geckolib.core.animation.Animation sonicClip =
                    getAnimation(animatable, AnimRegistry.SONIC_BOOM);
            double sonicElapsed = Math.max(0.0D, AnimRegistry.SONIC_LENGTH_TICKS - sonic);
            if (sonicClip != null && sonicElapsed < sonicClip.length()) {
                this.sniffInterrupt.remove(animatable.getId());
                action.syncTo(-animatable.tickCount - 3, AnimRegistry.SONIC_BOOM,
                        sonicClip.length(), now - sonicElapsed);
            }
        }
        // ===== T8 끝 =====
        String requested = hurtStarted ? AnimRegistry.IDLE_HURT : animatable.getActionClip();
        if (hurtStarted) {
            software.bernie.geckolib.core.animation.Animation hurtClip =
                    getAnimation(animatable, AnimRegistry.IDLE_HURT);
            if (hurtClip != null) {
                action.syncTo(-animatable.tickCount, AnimRegistry.IDLE_HURT,
                        hurtClip.length(), now);
            }
        } else if (requested.isEmpty()) {
            // A running flinch is not cut short by the server's empty action slot - the two are
            // driven from different places and the flinch owns the slot until it ends on its own.
            if (!AnimRegistry.IDLE_HURT.equals(action.clip())
                    && !AnimRegistry.IDLE_SNIFF.equals(action.clip())
                    && !AnimRegistry.ATTACK.equals(action.clip())
                    && !AnimRegistry.SONIC_BOOM.equals(action.clip())) {
                action.stop();
            }
        } else {
            software.bernie.geckolib.core.animation.Animation clip =
                    getAnimation(animatable, requested);
            if (clip != null) {
                action.syncTo(animatable.getActionSeq(), requested, clip.length(), now);
            }
        }

        double age = action.age(now);
        double weight = action.weight(age);
        boolean checking = ActionCheck.isRunning();
        // Snapshot before the C3 write and diff after it. Reading the bone is the only way to
        // answer "did the value reach the bone" — a check on the sampler's output alone would pass
        // just as happily if nothing were ever written.
        LinkedHashMap<String, double[]> beforeRot = checking ? readAllBones() : null;
        LinkedHashMap<String, double[]> beforePos = checking ? readAllPositions() : null;

        ClipSampler.Pose pose = null;
        if (age >= 0.0D && weight > 0.0D) {
            software.bernie.geckolib.core.animation.Animation clip =
                    getAnimation(animatable, action.clip());
            if (clip != null) {
                pose = ClipSampler.sample(clip, age);
                // hurt_amp_scale is 4.11's only size knob and applies to the flinch alone, so
                // ActionCheck's actiontest comparison is untouched.
                double amp = weight * (AnimRegistry.IDLE_HURT.equals(action.clip())
                        ? AnimParams.HURT_AMP_SCALE.get() : 1.0D);
                if (AnimRegistry.IDLE_SNIFF.equals(action.clip())) {
                    // 중단 페이드. 한 프레임에 끊으면 튄다.
                    Double cut = this.sniffInterrupt.get(animatable.getId());
                    if (cut != null) {
                        double fade = AnimParams.SNIFF_INTERRUPT_FADE.get();
                        double u = fade <= 0.0D ? 1.0D : (now - cut) / fade;
                        amp *= Math.max(0.0D, 1.0D - u);
                    }
                    // 소리는 여기서 내지 않는다. 서버 WardenGirlSniffGoal 이 mob.playSound 로
                    // 월드 사운드를 내므로 주변 플레이어 전원이 거리만큼 감쇠해 같이 듣는다.
                }
                boolean hurtClip = AnimRegistry.IDLE_HURT.equals(action.clip());
                // 4.13: 탑승 중에는 앉기가 팔을 앞으로 보내므로 킁킁이 상쇄될 수 있다.
                if (AnimRegistry.IDLE_SNIFF.equals(action.clip()) && animatable.isPassenger()) {
                    amp *= AnimParams.SNIFF_RIDING_SCALE.get();
                }
                for (Map.Entry<String, double[]> e : pose.rotationsDeg().entrySet()) {
                    addRotX(e.getKey(), e.getValue()[0] * amp);
                    addRotY(e.getKey(), e.getValue()[1] * amp);
                    // 4.11 의 팔 zRot 만 따로 조절한다. 팔과 몸통이 x=±4 에서 맞닿아 있어
                    // 관통이 기하로 결정되므로, 사람이 화면에서 크기를 정할 노브가 필요하다.
                    double zAmp = amp;
                    if (AnimRegistry.IDLE_SNIFF.equals(action.clip())
                            && animatable.isPassenger()) {
                        zAmp *= AnimParams.SNIFF_RIDING_SCALE.get();
                    }
                    if (hurtClip && (Bones.ARM_RIGHT.equals(e.getKey())
                            || Bones.ARM_LEFT.equals(e.getKey()))) {
                        zAmp *= AnimParams.HURT_ARM_Z_SCALE.get();
                    }
                    addRotZ(e.getKey(), e.getValue()[2] * zAmp);
                }
                for (Map.Entry<String, double[]> e : pose.positionsRaw().entrySet()) {
                    addPositionRaw(e.getKey(), e.getValue(), amp);
                }
                // The one axis 4.4.2 and 4.11 share. Walk writes body.xRot as walk_body_lean;
                // the flinch writes its own. Simple addition may read wrong, so the walk's share
                // is scalable away exactly like the four C2/C3 axes are.
                double keep = AnimParams.BLEND_WALK_BODY_X_HURT.get();
                if (AnimRegistry.IDLE_HURT.equals(action.clip()) && keep < 1.0D - 1.0E-9D) {
                    addRotX(Bones.BODY, -walkOnly[4] * (1.0D - keep));
                }
            }
        }
        if (BoneTrace.isRunning() && EntityLock.isSubject(animatable.getId())) {
            boolean isHurt = AnimRegistry.IDLE_HURT.equals(action.clip());
            BoneTrace.noteHurt(hurtStarted,
                    weight * (isHurt ? AnimParams.HURT_AMP_SCALE.get() : 1.0D),
                    isHurt && pose != null ? pose.rotationsDeg() : null);
            if (AnimRegistry.IDLE_SNIFF.equals(action.clip()) && pose != null) {
                BoneTrace.noteSniffPose(age, AnimRegistry.SNIFF_LENGTH_TICKS, weight,
                        pose.rotationsDeg());
            }
        }
        if (checking) {
            ActionCheck.sample(pose, delta(beforeRot, readAllBones()),
                    delta(beforePos, readAllPositions()), age, weight, action.clip(),
                    animatable.tickCount);
        }
        // After the check, so the measured delta is C3's own contribution and not C3 plus a walk
        // rescale that has nothing to do with the clip.
        double[] eff = applyWalkBlend(walkOnly, weight);
        return new ActionInfo(blendChannels(pose, weight), eff, weight);
    }

    /** C3's contribution on the four shared axes, after the fade. Zero when nothing is playing. */
    private static double[] blendChannels(ClipSampler.Pose pose, double weight) {
        if (pose == null) {
            return new double[4];
        }
        double[] armR = pose.rotationsDeg().get(Bones.ARM_RIGHT);
        double[] armL = pose.rotationsDeg().get(Bones.ARM_LEFT);
        double[] body = pose.rotationsDeg().get(Bones.BODY);
        double[] head = pose.rotationsDeg().get(Bones.HEAD);
        return new double[]{
                armR == null ? 0.0D : armR[0] * weight,
                armL == null ? 0.0D : armL[0] * weight,
                body == null ? 0.0D : body[1] * weight,
                head == null ? 0.0D : head[1] * weight};
    }

    private static LinkedHashMap<String, double[]> delta(Map<String, double[]> before,
                                                        Map<String, double[]> after) {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        after.forEach((bone, now) -> {
            double[] then = before.get(bone);
            out.put(bone, then == null ? now
                    : new double[]{now[0] - then[0], now[1] - then[1], now[2] - then[2]});
        });
        return out;
    }

    /**
     * @return the effective walk weight per channel, in {@link #readBlendAxes} order
     */
    private double[] applyWalkBlend(double[] walkOnly, double envelope) {
        double arm = effectiveBlend(AnimParams.BLEND_WALK_ARM_X.get(), envelope);
        double body = effectiveBlend(AnimParams.BLEND_WALK_BODY_Y.get(), envelope);
        double head = effectiveBlend(AnimParams.BLEND_WALK_HEAD_Y.get(), envelope);
        // Four axes and nothing else. The legs are deliberately absent: a mob whose legs stop
        // mid-stride to swing at something is worse than one whose arms are over-rotated, and
        // 4.4.2's leg swing is the whole reason the walk reads as walking.
        addRotX(Bones.ARM_RIGHT, walkOnly[0] * (arm - 1.0D));
        addRotX(Bones.ARM_LEFT, walkOnly[1] * (arm - 1.0D));
        addRotY(Bones.BODY, walkOnly[2] * (body - 1.0D));
        addRotY(Bones.HEAD, walkOnly[3] * (head - 1.0D));
        return new double[]{arm, arm, body, head};
    }

    private static double effectiveBlend(double weight, double envelope) {
        return 1.0D + (weight - 1.0D) * envelope;
    }

    /**
     * Adds a clip's position channel in <b>GeckoLib's</b> raw units, not model pixels.
     *
     * <p>Deliberately not {@link AxisConvention#setPositionPx}: that negates x and the json path
     * does not, so routing a clip through it would make the same json number mean opposite things
     * in C2 and C3. See {@link ClipSampler}'s class doc and Part 11.
     */
    private void addPositionRaw(String bone, double[] raw, double weight) {
        getBone(bone).ifPresent(b -> {
            b.setPosX(b.getPosX() + (float) (raw[0] * weight));
            b.setPosY(b.getPosY() + (float) (raw[1] * weight));
            b.setPosZ(b.getPosZ() + (float) (raw[2] * weight));
        });
    }

    /**
     * 4.3.4 기본 자세 오프셋 — static, always added, on top of C1.
     *
     * <p><b>Added, not assigned.</b> C1 has already written this frame's breathing and sway into
     * these same bones; assigning here would delete them and the model would go still.
     */
    private void applyStaticOffsets() {
        // These are ADDED, and that is only safe because every bone below has a rotation channel in
        // warden_girl.animation.json — even the legs, whose channel is a literal zero.
        //
        // GeoBone's setters call markRotationAsChanged() internally, and AnimationProcessor skips
        // its reset-to-snapshot step for any bone already marked changed. So a bone that the
        // animation never writes gets marked by this very method on frame 1, is never reset again,
        // and accumulates one offset per frame. That is what made the legs spin.
        // xRot, not zRot. An inward zRot offset is geometrically impossible here: the arm's inner
        // face sits flush against the torso (both at x = ±4), so any inward z rotation drives the
        // arm into the body. Fore/aft rotation has nothing to collide with. See Part 11.
        addRotX(Bones.ARM_RIGHT, AnimParams.OFFSET_ARM_R_X.get());
        addRotX(Bones.ARM_LEFT, AnimParams.OFFSET_ARM_L_X.get());
        addRotY(Bones.LEG_RIGHT, AnimParams.OFFSET_LEG_R_Y.get());
        addRotY(Bones.LEG_LEFT, AnimParams.OFFSET_LEG_L_Y.get());
        addRotX(Bones.HEAD, AnimParams.OFFSET_HEAD_X.get());
        addRotX(Bones.BODY, AnimParams.OFFSET_BODY_X.get());
    }

    /**
     * 4.3.x 진단 — {@code overlay_hidden} hides every outer-layer cube.
     *
     * <p>GeckoLib can hide a bone but not one cube of a bone, and the overlay is the second cube of
     * six bones. So the cube is removed from the bone's list and kept aside, then put back. Only
     * done when the switch actually changes, so the normal path costs nothing.
     */
    private void applyOverlayVisibility() {
        boolean hide = AnimParams.OVERLAY_HIDDEN.get() >= 0.5D;
        if (hide == this.overlayHidden) {
            return;
        }
        this.overlayHidden = hide;
        for (String name : OVERLAY_BONES) {
            getBone(name).ifPresent(bone -> {
                java.util.List<software.bernie.geckolib.cache.object.GeoCube> cubes = bone.getCubes();
                if (hide) {
                    if (cubes.size() > 1) {
                        this.stashedOverlay.put(name, cubes.remove(1));
                    }
                } else {
                    software.bernie.geckolib.cache.object.GeoCube cube =
                            this.stashedOverlay.remove(name);
                    if (cube != null) {
                        cubes.add(cube);
                    }
                }
            });
        }
    }

    private static final String[] OVERLAY_BONES = {
            Bones.HEAD, Bones.BODY, Bones.ARM_RIGHT, Bones.ARM_LEFT,
            Bones.LEG_RIGHT, Bones.LEG_LEFT};
    private boolean overlayHidden = false;
    private final Map<String, software.bernie.geckolib.cache.object.GeoCube> stashedOverlay =
            new HashMap<>();

    private void addRotX(String bone, double degrees) {
        getBone(bone).ifPresent(b -> b.setRotX(b.getRotX() + AxisConvention.toRad(degrees)));
    }

    private void addRotY(String bone, double degrees) {
        getBone(bone).ifPresent(b -> b.setRotY(b.getRotY() + AxisConvention.toRad(degrees)));
    }

    private void addRotZ(String bone, double degrees) {
        getBone(bone).ifPresent(b -> b.setRotZ(b.getRotZ() + AxisConvention.toRad(degrees)));
    }

    /**
     * Adds to a bone's y position offset, in model pixels.
     *
     * <p>No conversion: positions are pixels on both sides, and y is the one axis
     * {@link AxisConvention#setPositionPx} does not negate. x would need the negation and does not
     * appear in C1, so there is deliberately no {@code addPosX} to get it wrong with.
     */
    private void addPosY(String bone, double pixels) {
        getBone(bone).ifPresent(b -> b.setPosY(b.getPosY() + (float) pixels));
    }

    // ---- T1 axis verification harness ---------------------------------------------------------

    /** @return true if the harness is active and has taken over the rig. */
    private boolean applyAxisTest(WardenGirlEntity animatable) {
        String request = animatable.getAxisTest();

        if (request == null || request.isEmpty()) {
            AxisTestReporter.forget(animatable.getId());
            return false;
        }

        String[] parts = request.split(":", 3);
        if (parts.length != 3) {
            return false;
        }
        Bones.Axis axis = Bones.Axis.parse(parts[1]);
        if (axis == null) {
            return false;
        }
        double degrees;
        try {
            degrees = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }

        String boneName = parts[0];
        resetAllBones();

        Optional<GeoBone> maybeBone = getBone(boneName);
        if (maybeBone.isEmpty()) {
            return true;
        }
        GeoBone bone = maybeBone.get();
        AxisConvention.set(bone, axis, degrees);

        AxisTestReporter.report(animatable.getId(), boneName, axis, degrees, readAllBones());
        return true;
    }

    /**
     * Every bone's current position offset, in model pixels.
     *
     * <p>This used to list only root and body — "those are the two bones 4.3 drives by position".
     * That is the same narrowing that let the legs spin for a whole task cycle: a dump restricted
     * to the bones believed to move cannot report a bone that moves unexpectedly. All nine, always,
     * on positions as well as rotations.
     */
    private LinkedHashMap<String, double[]> readAllPositions() {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        for (String name : Bones.ALL) {
            getBone(name).ifPresent(b -> out.put(name, AxisConvention.readPositionPx(b)));
        }
        return out;
    }

    /** Every bone's current rotation, in degrees, in the Part 4.0 hierarchy order. */
    private LinkedHashMap<String, double[]> readAllBones() {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        for (String name : Bones.ALL) {
            getBone(name).ifPresent(b -> out.put(name, AxisConvention.readDegrees(b)));
        }
        return out;
    }

    /**
     * Zeroes every bone's rotation.
     *
     * <p>Without this, switching from {@code head x} to {@code head y} would leave both applied and
     * the human would be judging a two-axis pose while believing it is one axis. It also wipes C1
     * for the duration, which is the point — the harness measures one axis in isolation.
     */
    private void resetAllBones() {
        for (String name : Bones.ALL) {
            getBone(name).ifPresent(AxisConvention::zero);
        }
    }
}
