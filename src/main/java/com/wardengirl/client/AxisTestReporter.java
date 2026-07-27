package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.Bones;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side readback for the T1 axis check.
 *
 * <p>The command that starts an axis test runs on the server; the bone rotation it is asking about
 * only exists on the client. So the command alone can report what it <em>requested</em> and nothing
 * more — and design doc Part 6.2 principle 2 is explicit that a command which reports its own
 * request has verified nothing.
 *
 * <p>This closes that gap: once the client has actually posed the rig, it prints the rotations read
 * back off the {@code GeoBone}s themselves — all eight of them, so that "only the target bone
 * moved" is a shown fact rather than an assumption.
 *
 * <p>Printed once per change of selection, not once per frame.
 */
public final class AxisTestReporter {

    private AxisTestReporter() {
    }

    /** entity id -> last selection we announced, so we announce transitions only. */
    private static final Map<Integer, String> ANNOUNCED = new HashMap<>();

    public static void report(int entityId, String boneName, Bones.Axis axis, double degrees,
                              Map<String, double[]> allBones) {
        String key = boneName + ":" + axis + ":" + degrees;
        if (key.equals(ANNOUNCED.get(entityId))) {
            return;
        }
        ANNOUNCED.put(entityId, key);

        double[] target = allBones.get(boneName);
        if (target == null) {
            return;
        }

        send(Component.literal("[axistest/client] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.format(Locale.ROOT,
                                "bone=%s  축=%s  요청=%+.1f°  실측=(x %+.3f°, y %+.3f°, z %+.3f°)",
                                boneName, axis, degrees, target[0], target[1], target[2]))
                        .withStyle(ChatFormatting.WHITE)));
        send(Component.literal("                 기대: " + Bones.expectation(boneName, axis))
                .withStyle(ChatFormatting.YELLOW));

        // The full dump goes to the log rather than chat — eight lines of chat per test would bury
        // the model behind the chat overlay, which is the thing being looked at.
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, double[]> e : allBones.entrySet()) {
            double[] r = e.getValue();
            sb.append(String.format(Locale.ROOT, " %s=(%+.3f,%+.3f,%+.3f)",
                    e.getKey(), r[0], r[1], r[2]));
        }
        WardenGirlMod.LOGGER.info("[axistest/client] entity={} target={}:{} requested={}deg allBones:{}",
                entityId, boneName, axis, degrees, sb);
    }

    /** Called when the harness is switched off, so the next activation announces again. */
    public static void forget(int entityId) {
        ANNOUNCED.remove(entityId);
    }

    private static void send(Component component) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(component, false);
        }
    }
}
