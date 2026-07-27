package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AxisConvention;
import com.wardengirl.anim.Bones;
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
    }

    @Override
    public void setCustomAnimations(WardenGirlEntity animatable, long instanceId,
                                    AnimationState<WardenGirlEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        // The axis harness is a measuring tool: when it is on it owns the whole rig, so the static
        // offsets are deliberately skipped. Otherwise a "+45° on one axis" reading would silently
        // be 45° plus a 4° offset, and the number on screen would not be the number reported.
        if (applyAxisTest(animatable)) {
            for (HeadgearSpring spring : springsFor(animatable)) {
                spring.reset();
            }
            damperFor(animatable).reset();
            return;
        }
        applyStaticOffsets();
        applyLook(animatable, animationState);
        applyHeadgearSpring(animatable);
        applyOverlayVisibility();
        reportParamChange();
        if (BoneTrace.isRunning()) {
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
     * <h2>4.6 감쇠 — clamp first, then damp</h2>
     *
     * The order matters. Damping the raw value and clamping the result would park the head at the
     * limit for as long as the raw target stayed outside it, and the approach to the limit would be
     * a straight line cut off at the top. Clamping first makes the limit the target, so the head
     * eases onto it the same way it eases onto any other angle. It is also what keeps the damped
     * value inside the clamp for free: a convex combination of two in-range values is in range.
     *
     * <p>No angle wrapping. What arrives is already a <em>difference</em> (head minus body) that
     * vanilla has wrapped into (−180, 180], and the clamp above narrows it to ±75 before the filter
     * sees it. A sign flip across the back is therefore a sweep from +75 to −75 through zero, which
     * is the path the head physically has to take anyway.
     */
    private void applyLook(WardenGirlEntity animatable,
                           AnimationState<WardenGirlEntity> animationState) {
        EntityModelData look = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
        if (look == null) {
            return;
        }
        double gain = AnimParams.LOOK_GAIN.get();
        // The clamp is NOT redundant with GeckoLib's. GeckoLib's Mth.clamp(..., -85, 85) sits
        // inside the `shouldSit` branch of actuallyRender and never runs for a standing mob; the
        // delivered value is the raw yHeadRot - yBodyRot difference. Measured range across 729
        // samples: -88.280 .. +86.250. See Part 11.
        double[] target = {
                clampAbs(look.netHeadYaw() * gain, AnimParams.LOOK_YAW_MAX.get()),
                clampAbs(look.headPitch() * gain, AnimParams.LOOK_PITCH_MAX.get())};

        double distance = distanceToLocalPlayer(animatable);
        double damping = LookDamper.dampingFor(distance);
        LookDamper damper = damperFor(animatable);
        damper.advanceTo(animatable.tickCount, target, damping);
        float partialTick = Minecraft.getInstance().getPartialTick();
        double yaw = damper.output(LookDamper.YAW, partialTick, target[LookDamper.YAW]);
        double pitch = damper.output(LookDamper.PITCH, partialTick, target[LookDamper.PITCH]);

        BoneTrace.noteLook(target, new double[]{yaw, pitch}, damping, distance,
                animatable.tickCount);
        addRotY(Bones.HEAD, yaw);
        addRotX(Bones.HEAD, pitch);
    }

    /**
     * Distance in blocks to the client's own player, or −1 when there is none.
     *
     * <p>The local player, not "the entity's look target": 4.6 근거리 반응 exists so that walking up
     * to the companion changes how it moves, and the person walking up is the one holding the
     * screen. Using the AI's target would make the effect invisible to anyone but that target.
     */
    private static double distanceToLocalPlayer(WardenGirlEntity animatable) {
        var player = Minecraft.getInstance().player;
        return player == null ? -1.0D : animatable.distanceTo(player);
    }

    /**
     * One damper per entity, for the same reason as {@link #springsFor} — the model instance is
     * shared by every WardenGirl on screen, so a single filter would have them all driving one
     * neck angle.
     */
    private final Map<Integer, LookDamper> dampers = new HashMap<>();

    private LookDamper damperFor(WardenGirlEntity animatable) {
        if (this.dampers.size() > MAX_TRACKED_ENTITIES) {
            this.dampers.clear();
        }
        return this.dampers.computeIfAbsent(animatable.getId(), k -> new LookDamper());
    }

    private static double clampAbs(double value, double limit) {
        double max = Math.abs(limit);
        return Math.max(-max, Math.min(max, value));
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
