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
                    // 2차 설계서 5.2 화염 면역. Entity.fireImmune() 이 이 타입 플래그를 그대로
                    // 돌려주고, Entity.isInvulnerableTo 가 DamageTypeTags.IS_FIRE 와 함께 검사한다.
                    // 불 블록·용암·불타는 틱·화염 속성 공격이 모두 그 태그에 들어 있으므로 이
                    // 한 줄이 전부를 덮는다 — 최종 피해 직전에 태그를 또 보지 않는다.
                    .fireImmune()
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
