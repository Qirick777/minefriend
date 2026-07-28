package com.wardengirl.registry;

import com.wardengirl.WardenGirlMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * T6 4.12 킁킁 소리. <b>폐기 가능하다.</b>
 *
 * <p>{@code IdleReaction} 과 함께 지워질 수 있도록 따로 뒀다. 지울 때는 이 클래스,
 * {@code sounds.json}, {@code assets/wardengirl/sounds/sniff.ogg}, 그리고 {@code WardenGirlMod}
 * 안의 등록 한 줄이다. {@code sniff_distance} 가 0 이면 발화 자체가 없으므로 코드를 안 지워도
 * 소리는 나지 않는다.
 *
 * <p>카테고리는 {@code neutral} 이다 — {@code SoundSource.NEUTRAL} 은 바닐라가 적대적이지 않은
 * 몹의 발성에 쓰는 채널이고, 플레이어가 음량 슬라이더에서 "우호적 생물" 로 조절한다. 킁킁은
 * 공격이 아니라 반응이므로 여기가 맞다.
 */
public final class ModSounds {

    private ModSounds() {
    }

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, WardenGirlMod.MOD_ID);

    /** 0.746초, 모노, 44100Hz. 킁킁 2회가 들어 있다 — 정점은 0.180초와 0.485초. */
    public static final RegistryObject<SoundEvent> SNIFF = SOUNDS.register("entity.warden_girl.sniff",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(WardenGirlMod.MOD_ID, "entity.warden_girl.sniff")));

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
