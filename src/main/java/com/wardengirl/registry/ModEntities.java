package com.wardengirl.registry;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Entity registration. Design doc Part 3.8 (phase 1 minimum entity spec).
 *
 * <p>No natural spawn is registered: phase 1 summons by command only.
 */
@Mod.EventBusSubscriber(modid = WardenGirlMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModEntities {

    private ModEntities() {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, WardenGirlMod.MOD_ID);

    /** {@code wardengirl:warden_girl} — hitbox 0.6 x 1.8, identical to the player (Part 3.8). */
    public static final RegistryObject<EntityType<WardenGirlEntity>> WARDEN_GIRL =
            ENTITY_TYPES.register("warden_girl", () -> EntityType.Builder
                    .of(WardenGirlEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(new ResourceLocation(WardenGirlMod.MOD_ID, "warden_girl").toString()));

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(WARDEN_GIRL.get(), WardenGirlEntity.createAttributes().build());
    }
}
