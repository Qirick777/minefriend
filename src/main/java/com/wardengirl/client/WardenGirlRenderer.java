package com.wardengirl.client;

import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Entity renderer. Design doc Part 3.5 file layout.
 *
 * <p><b>No render layers.</b> The sensory tendrils used to be drawn from their own texture by a
 * {@code GeoRenderLayer}; they are now painted into {@code warden_girl.png} at UV (54,16)-(64,26)
 * and come out of the single main pass like any other cube. The layer was removed rather than
 * fixed — asking {@code MultiBufferSource.BufferSource.getBuffer} for a second {@code RenderType}
 * part-way through a model ends the batch in progress while {@code renderRecursively} keeps writing
 * into the {@code VertexConsumer} it was handed at the start, so every bone visited after the
 * tendrils (the arms, then the legs) landed in the tendril's batch and was drawn with the tendril's
 * texture. One texture, one buffer, one pass: the failure cannot recur.
 */
public class WardenGirlRenderer extends GeoEntityRenderer<WardenGirlEntity> {

    public WardenGirlRenderer(EntityRendererProvider.Context context) {
        super(context, new WardenGirlModel());
        this.shadowRadius = 0.4F;
    }
}
