package com.wardengirl.client;

import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Entity renderer. Design doc Part 3.5 file layout.
 *
 * <p>T1 scope: wiring only. No layers, no glow, no dynamic texturing.
 */
public class WardenGirlRenderer extends GeoEntityRenderer<WardenGirlEntity> {

    public WardenGirlRenderer(EntityRendererProvider.Context context) {
        super(context, new WardenGirlModel());
        this.shadowRadius = 0.4F;
    }
}
