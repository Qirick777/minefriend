package com.wardengirl;

import com.mojang.logging.LogUtils;
import com.wardengirl.registry.ModEntities;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * WardenGirl — phase 1 entry point.
 *
 * <p>Phase 1 scope is the animation skeleton only (design doc Part 1 / Part 2.5). No AI,
 * no combat resolution, no damage, no particles, no sound, no real movement.
 */
@Mod(WardenGirlMod.MOD_ID)
public class WardenGirlMod {

    public static final String MOD_ID = "wardengirl";

    public static final Logger LOGGER = LogUtils.getLogger();

    public WardenGirlMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[wardengirl] T0 boot: mod class loaded, entity registry attached.");
        logGeckoLibBinding();
    }

    /**
     * T0 machine verification (design doc Part 5 / P1-T0): prove GeckoLib is actually present at
     * runtime, not merely on the compile classpath.
     *
     * <p>Three independent facts are printed, because any one of them alone can lie:
     * <ul>
     *   <li>the version FML resolved for the {@code geckolib} mod container — proves GeckoLib
     *       loaded <em>as a mod</em>, not just as a library jar;</li>
     *   <li>{@code GeckoLib.hasInitialized} — a non-constant static field, so reading it forces
     *       real classloading and linkage of a GeckoLib class in this JVM. A compile-time
     *       constant would be inlined by javac and would prove nothing at runtime;</li>
     *   <li>the code source of that loaded class — proves <em>which</em> jar answered.</li>
     * </ul>
     */
    private void logGeckoLibBinding() {
        String reportedVersion = ModList.get()
                .getModContainerById("geckolib")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("<absent from ModList>");

        boolean initialized = software.bernie.geckolib.GeckoLib.hasInitialized;

        String codeSource;
        try {
            codeSource = software.bernie.geckolib.GeckoLib.class
                    .getProtectionDomain().getCodeSource().getLocation().toString();
        } catch (Throwable t) {
            codeSource = "<unavailable: " + t + ">";
        }

        LOGGER.info("[wardengirl] GeckoLib ModList version = {}", reportedVersion);
        LOGGER.info("[wardengirl] GeckoLib.hasInitialized  = {}", initialized);
        LOGGER.info("[wardengirl] GeckoLib code source     = {}", codeSource);
    }
}
