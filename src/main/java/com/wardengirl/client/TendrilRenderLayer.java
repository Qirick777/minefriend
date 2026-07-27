package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.Bones;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Draws the two sensory tendrils from their own texture instead of the body sheet.
 *
 * <h2>Why a layer</h2>
 *
 * The tendril art is a standalone file, kept separate so it can be redrawn without touching the
 * skin. GeckoLib binds one texture per model, so a second one needs a render layer.
 * {@code GeoRenderLayer.renderForBone} is the hook: {@code renderRecursively} calls it <em>per
 * bone</em>, immediately after that bone's own cubes and before its children, with the PoseStack
 * already carrying the bone's full transform — including this frame's spring rotation.
 *
 * <h2>Why the bone is hidden and then briefly unhidden</h2>
 *
 * Left visible, each tendril would be drawn twice: once by the body pass with the skin's UVs, once
 * here. {@code renderCubesOfBone} returns immediately for a hidden bone, so the model hides both
 * tendrils every frame and the body pass skips them — but {@code applyRenderLayersForBone} sits
 * <em>outside</em> that check, so this method still runs for them. It unhides just long enough to
 * reuse GeckoLib's own cube loop (which handles each cube's pivot and rotation) and hides again.
 *
 * <h2>The spring is not integrated twice</h2>
 *
 * Render layers never call {@code handleAnimations} — the spring advances once per frame in
 * {@code setCustomAnimations}, before any of this. And independently of that call graph,
 * {@link HeadgearSpring#advanceTo} takes zero steps when the tick counter has not moved, so even a
 * duplicated animation pass could not double-integrate it.
 *
 * <h2>UVs</h2>
 *
 * The tendril cubes use per-face UVs whose {@code uv_size} equals the model's declared sheet size,
 * so the normalised coordinates span 0..1 and the face samples the entire bound texture regardless
 * of its pixel dimensions. That is what allows a 10×10 file to be used without reserving space in
 * the 128×128 skin. See the comment in {@code warden_girl.geo.json}.
 */
public class TendrilRenderLayer extends GeoRenderLayer<WardenGirlEntity> {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation(WardenGirlMod.MOD_ID, "textures/entity/tendril.png");

    public TendrilRenderLayer(GeoRenderer<WardenGirlEntity> renderer) {
        super(renderer);
    }

    @Override
    protected ResourceLocation getTextureResource(WardenGirlEntity animatable) {
        return TEXTURE;
    }

    @Override
    public void renderForBone(com.mojang.blaze3d.vertex.PoseStack poseStack,
                              WardenGirlEntity animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource,
                              com.mojang.blaze3d.vertex.VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (!Bones.HEADGEAR.contains(bone.getName())) {
            return;
        }
        // entityCutoutNoCull: alpha cutout for the transparent margin, and no back-face culling so
        // a zero-depth plane is visible from both sides. Same type the body uses, different texture.
        var tendrilBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        boolean wasHidden = bone.isHidden();
        bone.setHidden(false);
        getRenderer().renderCubesOfBone(poseStack, bone, tendrilBuffer,
                packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
        bone.setHidden(wasHidden);
    }
}
