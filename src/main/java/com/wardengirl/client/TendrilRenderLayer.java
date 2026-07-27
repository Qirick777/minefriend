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
    private boolean tendrilPass = false;

    /**
     * Sets visibility for whichever pass is about to run.
     *
     * <p>This is the only place bone visibility is decided. The model must not do it — the model's
     * {@code setCustomAnimations} also runs during the re-render (via {@code handleAnimations}),
     * so anything it hides is hidden for the tendril pass too.
     */
    @Override
    public void preRender(PoseStack poseStack, WardenGirlEntity animatable,
                          BakedGeoModel bakedModel, RenderType renderType,
                          MultiBufferSource bufferSource, VertexConsumer buffer,
                          float partialTick, int packedLight, int packedOverlay) {
        int solo = (int) Math.round(AnimParams.HEADGEAR_SOLO.get());
        for (GeoBone bone : bakedModel.topLevelBones()) {
            setVisibility(bone, solo);
        }
    }

    private void setVisibility(GeoBone bone, int solo) {
        int index = Bones.HEADGEAR.indexOf(bone.getName());
        boolean isTendril = index >= 0;
        boolean wantedNow = this.tendrilPass
                // tendril pass: only the tendrils, and only the side headgear_solo asks for
                ? isTendril && solo != 3 && (solo == 0 || solo == index + 1)
                // body pass: everything except the tendrils
                : !isTendril;
        bone.setHidden(!wantedNow);
        for (GeoBone child : bone.getChildBones()) {
            setVisibility(child, solo);
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
        if (this.tendrilPass || Math.round(AnimParams.HEADGEAR_SOLO.get()) == 3) {
            return;
        }
        RenderType tendrilType = RenderType.entityCutoutNoCull(TEXTURE);
        this.tendrilPass = true;
        try {
            getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, tendrilType,
                    bufferSource.getBuffer(tendrilType), partialTick, packedLight, packedOverlay,
                    1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            this.tendrilPass = false;
        }
        // Leave the rig as the body pass wants it, so nothing downstream sees a half-hidden model.
        for (GeoBone bone : bakedModel.topLevelBones()) {
            setVisibility(bone, (int) Math.round(AnimParams.HEADGEAR_SOLO.get()));
        }
    }

}
