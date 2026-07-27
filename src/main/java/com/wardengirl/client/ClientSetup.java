package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only registration. Design doc Part 3.5 file layout. */
@Mod.EventBusSubscriber(modid = WardenGirlMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.WARDEN_GIRL.get(), WardenGirlRenderer::new);
        WardenGirlMod.LOGGER.info("[wardengirl] renderer registered for {}",
                ModEntities.WARDEN_GIRL.getId());
    }
}
