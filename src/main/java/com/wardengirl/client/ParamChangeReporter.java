package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Client-side readback for {@code /wardengirl param}.
 *
 * <p>Same reason as {@link AxisTestReporter}: the command runs on the server, the bones exist on
 * the client, and design doc Part 6.2 does not accept a command reporting its own request as
 * evidence. This prints what the rig actually looks like on the first frame after a change.
 *
 * <p>C1 never stops, so these values are sampled from a moving rig — two reads a frame apart will
 * differ slightly even with no parameter change. That is the point: a frozen dump would mean C1
 * had stopped, which Part 10.5 forbids.
 */
public final class ParamChangeReporter {

    private ParamChangeReporter() {
    }

    public static void report(Map<String, double[]> allBones, Map<String, double[]> positions) {
        StringBuilder log = new StringBuilder();
        for (Map.Entry<String, double[]> e : allBones.entrySet()) {
            double[] r = e.getValue();
            log.append(String.format(Locale.ROOT, " %s=(%+.3f,%+.3f,%+.3f)",
                    e.getKey(), r[0], r[1], r[2]));
        }
        StringBuilder pos = new StringBuilder();
        positions.forEach((name, v) -> pos.append(String.format(Locale.ROOT, " %s=(%+.4f,%+.4f,%+.4f)",
                name, v[0], v[1], v[2])));
        WardenGirlMod.LOGGER.info("[param/client] 실측 회전(도):{}", log);
        WardenGirlMod.LOGGER.info("[param/client] 실측 위치(px):{}", pos);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double[] body = allBones.get("body");
        double[] root = allBones.get("root");
        if (body == null || root == null) {
            return;
        }
        mc.player.displayClientMessage(Component.literal("[param/client] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.format(Locale.ROOT,
                                "다음 프레임 실측 — body=(%+.3f, %+.3f, %+.3f)  (전 본은 로그 참조)",
                                body[0], body[1], body[2]))
                        .withStyle(ChatFormatting.WHITE)), false);
    }
}
