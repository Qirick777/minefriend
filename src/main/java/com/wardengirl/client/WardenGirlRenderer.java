package com.wardengirl.client;

import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Entity renderer. Design doc Part 3.5 file layout.
 *
 * <p>One render layer, added in T3: the sensory tendrils use a separate texture file so it can be
 * redrawn independently of the skin. See {@link TendrilRenderLayer}.
 */
public class WardenGirlRenderer extends GeoEntityRenderer<WardenGirlEntity> {

    public WardenGirlRenderer(EntityRendererProvider.Context context) {
        super(context, new WardenGirlModel());
        this.shadowRadius = 0.4F;
        // The tendrils are drawn from their own texture; the model hides them during the body pass.
        addRenderLayer(new TendrilRenderLayer(this));
    }
}
