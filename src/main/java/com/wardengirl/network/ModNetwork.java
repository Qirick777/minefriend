package com.wardengirl.network;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server → client parameter sync for the tuning commands.
 *
 * <p>The animation parameters are a <em>client</em> concern — they only ever feed the renderer, and
 * Part 3.6 puts them in {@code wardengirl-client.toml}. But {@code /wardengirl param} is a normal
 * server-side command, so a change made there would otherwise never reach the machine actually
 * drawing the model. This channel closes that gap.
 *
 * <p>In single-player both sides share a JVM and this is redundant; on a dedicated server it is the
 * only thing that makes the command do anything at all.
 */
public final class ModNetwork {

    private ModNetwork() {
    }

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(WardenGirlMod.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    public static void register() {
        CHANNEL.registerMessage(0, ParamSyncPacket.class,
                ParamSyncPacket::encode, ParamSyncPacket::decode, ParamSyncPacket::handle);
    }

    /** Pushes the given parameter values to every connected client. */
    public static void broadcast(Map<String, Double> values) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), new ParamSyncPacket(values));
    }

    public record ParamSyncPacket(Map<String, Double> values) {

        public static void encode(ParamSyncPacket packet, FriendlyByteBuf buf) {
            buf.writeVarInt(packet.values.size());
            packet.values.forEach((k, v) -> {
                buf.writeUtf(k);
                buf.writeDouble(v);
            });
        }

        public static ParamSyncPacket decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            Map<String, Double> values = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                values.put(buf.readUtf(), buf.readDouble());
            }
            return new ParamSyncPacket(values);
        }

        public static void handle(ParamSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> packet.values.forEach((key, value) -> {
                AnimParams.Param p = AnimParams.get(key);
                if (p != null) {
                    p.set(value);
                }
            }));
            ctx.get().setPacketHandled(true);
        }
    }
}
