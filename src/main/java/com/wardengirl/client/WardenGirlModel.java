package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.Bones;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

import java.util.Optional;

/**
 * GeoModel for the WardenGirl. Design doc Part 3.5 file layout.
 *
 * <p>T1 scope: resource wiring plus the axis-verification harness. There is no animation content
 * here — the C1/C2/C3 layers are T2 and later.
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
     * Applies the T1 axis-verification pose.
     *
     * <p>Runs after the animation processor, so whatever is written here is the last word on the
     * bone — which is what makes it a usable measuring stick.
     *
     * <p><strong>Units:</strong> {@link GeoBone} rotations are radians, not degrees. Verified from
     * the GeckoLib bytecode: {@code RenderUtils.rotateMatrixAroundBone} feeds the values to
     * {@code com.mojang.math.Axis.rotation(float)}, which is the radian entry point
     * ({@code rotationDegrees} is the degree one). Writing 10 here would be ~573°.
     */
    @Override
    public void setCustomAnimations(WardenGirlEntity animatable, long instanceId,
                                    AnimationState<WardenGirlEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        String request = animatable.getAxisTest();

        // Clearing the harness must actively zero the bone. Simply not writing it would leave the
        // last pose frozen in place, because nothing else animates this rig yet.
        if (request == null || request.isEmpty()) {
            AxisTestReporter.forget(animatable.getId());
            resetAllBones();
            return;
        }

        String[] parts = request.split(":", 3);
        if (parts.length != 3) {
            return;
        }

        String boneName = parts[0];
        Bones.Axis axis = Bones.Axis.parse(parts[1]);
        if (axis == null) {
            return;
        }
        double degrees;
        try {
            degrees = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        resetAllBones();

        Optional<GeoBone> maybeBone = getBone(boneName);
        if (maybeBone.isEmpty()) {
            return;
        }
        GeoBone bone = maybeBone.get();

        float radians = (float) Math.toRadians(degrees);
        switch (axis) {
            case X -> bone.setRotX(radians);
            case Y -> bone.setRotY(radians);
            case Z -> bone.setRotZ(radians);
        }

        // Read the values back off the bones rather than echoing what we intended to write.
        // Part 6.2 principle 2: the report has to be an observation, not a restatement.
        //
        // All eight are dumped, not just the target. Rotating body or root visibly tilts the whole
        // model because everything hangs off them (Part 4.0 tree), and that is indistinguishable
        // by eye from "some other bone also rotated". The only way to tell those apart is to show
        // that the other seven are still zero.
        AxisTestReporter.report(animatable.getId(), boneName, axis, degrees, readAllBones());
    }

    /** Every bone's current rotation, in degrees, in the Part 4.0 hierarchy order. */
    private java.util.LinkedHashMap<String, double[]> readAllBones() {
        java.util.LinkedHashMap<String, double[]> out = new java.util.LinkedHashMap<>();
        for (String name : Bones.ALL) {
            getBone(name).ifPresent(b -> out.put(name, new double[]{
                    Math.toDegrees(b.getRotX()),
                    Math.toDegrees(b.getRotY()),
                    Math.toDegrees(b.getRotZ())}));
        }
        return out;
    }

    /**
     * Zeroes every bone's rotation.
     *
     * <p>Needed because the axis harness is the only thing posing this rig in T1; without an
     * explicit reset, switching from {@code head x} to {@code head y} would leave both applied and
     * the human would be judging a two-axis pose while believing it is one axis.
     */
    private void resetAllBones() {
        for (String name : Bones.ALL) {
            getBone(name).ifPresent(bone -> {
                bone.setRotX(0);
                bone.setRotY(0);
                bone.setRotZ(0);
            });
        }
    }
}
