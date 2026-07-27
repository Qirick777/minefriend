package com.wardengirl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.Bones;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Draws the two sensory tendrils from their own texture instead of the body sheet.
 *
 * <h2>Why a whole-model re-render and not {@code renderForBone}</h2>
 *
 * The obvious implementation — filter to the tendril bones inside {@code renderForBone} and draw
 * their cubes into a second buffer — <b>breaks every part drawn after them</b>. Measured: with the
 * tendrils on, the arms rendered in the tendril's colours; with {@code headgear_solo 3} hiding both
 * tendrils, the same pixels came back as the arm texture's own bands (light 122,124,108 → dark
 * 17,20,22 → teal 28,74,78, each the skin value times that face's brightness).
 *
 * <p>The cause is {@code MultiBufferSource.BufferSource.getBuffer}. Asking it for a second
 * {@code RenderType} part-way through a model ends the batch in progress and makes the new type
 * current — but {@code renderRecursively} is still holding, and still writing into, the
 * {@code VertexConsumer} it was handed at the start. Bones visited after the tendrils
 * ({@code arm_right}, {@code arm_left}, then the legs) therefore land in the tendril's batch and
 * are drawn with the tendril's texture. The bone order in the geo is what decided which parts broke.
 *
 * <p>So the buffer is never switched mid-model. This layer runs from {@link #render}, which
 * GeckoLib calls <em>after</em> the main pass has finished, and re-renders the model once more with
 * the tendril texture — everything except the two tendrils hidden, so only they come out.
 * {@code reRender} passes {@code isReRender = true}, which is also what stops layers recursing.
 *
 * <h2>The spring is not integrated twice</h2>
 *
 * Render layers never call {@code handleAnimations} — the spring advances once per frame in
 * {@code setCustomAnimations}, before any of this, and the second pass reuses the bone values the
 * first pass drew. Independently of that, {@link HeadgearSpring#advanceTo} takes zero steps when the
 * tick counter has not moved, so even a duplicated animation pass could not double-integrate it.
 *
 * <h2>UVs</h2>
 *
 * The tendril cubes use per-face UVs whose {@code uv_size} equals the model's declared sheet size,
 * so the normalised coordinates span 0..1 and the face samples the entire bound texture regardless
 * of its pixel dimensions. That is what allows a 10×10 file to be used without reserving space in
 * the skin. See the comment in {@code warden_girl.geo.json}.
 */
public class TendrilRenderLayer extends GeoRenderLayer<WardenGirlEntity> {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation(WardenGirlMod.MOD_ID, "textures/entity/tendril.png");

    public TendrilRenderLayer(GeoRenderer<WardenGirlEntity> renderer) {
        super(renderer);
    }

    /**
     * True while this layer's own re-render is running.
     *
     * <p>{@code reRender} goes back through {@code actuallyRender}, so every hook here fires twice
     * per frame — once for the body pass and once for the tendril pass. Without this flag the two
     * passes cannot ask for different visibility, and the first attempt hid the tendrils during the
     * pass whose whole purpose was to draw them: the arms were fixed and the tendrils vanished.
     */
    private static boolean tendrilPass = false;

    /**
     * True while the tendril re-render is running.
     *
     * <p>{@code reRender} goes back through {@code actuallyRender}, which re-runs
     * {@code handleAnimations} and therefore {@code setCustomAnimations}. Anything in there that
     * <em>adds</em> to a bone would add a second time and the pose would differ between the two
     * passes, so the model asks this before doing additive work.
     */
    public static boolean isTendrilPass() {
        return tendrilPass;
    }

    /**
     * Hides the tendrils for the body pass.
     *
     * <p>Only useful for the body pass: {@code preApplyRenderLayers}, which is what calls this, is
     * invoked from {@code GeoRenderer.render} and <b>not</b> from {@code reRender} (verified in the
     * 4.8.4 bytecode). Relying on it for the tendril pass left visibility at whatever the body pass
     * had set — tendrils hidden, everything else shown — so the re-render drew the entire body
     * again with {@code tendril.png}, which is why the arms came out in the tendril's colours and
     * no tendril appeared anywhere. {@link #render} therefore sets visibility itself.
     */
    @Override
    public void preRender(PoseStack poseStack, WardenGirlEntity animatable,
                          BakedGeoModel bakedModel, RenderType renderType,
                          MultiBufferSource bufferSource, VertexConsumer buffer,
                          float partialTick, int packedLight, int packedOverlay) {
        applyVisibility(bakedModel, false);
    }

    /** @param tendrilsOnly true to show only the tendrils, false for "everything but the tendrils" */
    private void applyVisibility(BakedGeoModel bakedModel, boolean tendrilsOnly) {
        int solo = (int) Math.round(AnimParams.HEADGEAR_SOLO.get());
        for (GeoBone bone : bakedModel.topLevelBones()) {
            setVisibility(bone, solo, tendrilsOnly);
        }
    }

    private void setVisibility(GeoBone bone, int solo, boolean tendrilsOnly) {
        int index = Bones.HEADGEAR.indexOf(bone.getName());
        boolean isTendril = index >= 0;
        boolean wanted = tendrilsOnly
                ? isTendril && solo != 3 && (solo == 0 || solo == index + 1)
                : !isTendril;
        // setHidden suppresses this bone's cubes only; children are still walked and transformed,
        // which is what lets the tendrils' ancestors stay invisible yet still position them.
        bone.setHidden(!wanted);
        for (GeoBone child : bone.getChildBones()) {
            setVisibility(child, solo, tendrilsOnly);
        }
    }

    @Override
    protected ResourceLocation getTextureResource(WardenGirlEntity animatable) {
        return TEXTURE;
    }

    @Override
    public void render(PoseStack poseStack, WardenGirlEntity animatable, BakedGeoModel bakedModel,
                       RenderType renderType, MultiBufferSource bufferSource,
                       VertexConsumer buffer, float partialTick, int packedLight,
                       int packedOverlay) {
        if (tendrilPass || Math.round(AnimParams.HEADGEAR_SOLO.get()) == 3) {
            return;
        }
        RenderType tendrilType = RenderType.entityCutoutNoCull(TEXTURE);
        tendrilPass = true;
        applyVisibility(bakedModel, true);
        try {
            getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, tendrilType,
                    bufferSource.getBuffer(tendrilType), partialTick, packedLight, packedOverlay,
                    1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            tendrilPass = false;
            // Leave the rig as the body pass wants it — nothing downstream sees a half-hidden model.
            applyVisibility(bakedModel, false);
        }
    }

}
