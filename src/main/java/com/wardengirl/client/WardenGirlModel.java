package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AxisConvention;
import com.wardengirl.anim.Bones;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.molang.MolangParser;
import software.bernie.geckolib.model.GeoModel;

import java.util.LinkedHashMap;
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

    @Override
    public ResourceLocation getAnimationResource(WardenGirlEntity animatable) {
        return ANIMATION;
    }

    /**
     * Publishes the tuning parameters and the animation clock to Molang.
     *
     * <p>{@code query.wg_time} is <b>ticks, and it does not rewind.</b> GeckoLib's own
     * {@code query.anim_time} is seconds and resets at each loop boundary; C1 layers three periods
     * that do not share a common multiple (30 / 25 / 53 ticks), so a resetting clock would snap all
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
            return;
        }
        applyStaticOffsets();
        reportParamChange();
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
        ParamChangeReporter.report(readAllBones(), readMovedPositions());
    }

    /**
     * 4.3.4 기본 자세 오프셋 — static, always added, on top of C1.
     *
     * <p><b>Added, not assigned.</b> C1 has already written this frame's breathing and sway into
     * these same bones; assigning here would delete them and the model would go still.
     */
    private void applyStaticOffsets() {
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

    /** The bones that 4.3 drives by position rather than rotation: root (bounce) and body (chest). */
    private LinkedHashMap<String, double[]> readMovedPositions() {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        for (String name : new String[]{Bones.ROOT, Bones.BODY}) {
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
