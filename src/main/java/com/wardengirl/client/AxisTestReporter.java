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
 * <p>This closes that gap: once the client has actually posed the bone, it prints the rotation
 * values read back off the {@code GeoBone} itself, in chat, where they can be screenshotted next
 * to the model they describe.
 *
 * <p>Printed once per change of selection, not once per frame.
 */
public final class AxisTestReporter {

    private AxisTestReporter() {
    }

    /** entity id -> last selection we announced, so we announce transitions only. */
    private static final Map<Integer, String> ANNOUNCED = new HashMap<>();

    public static void report(int entityId, String boneName, Bones.Axis axis,
                              float rotXRadians, float rotYRadians, float rotZRadians) {
        String key = boneName + ":" + axis;
        if (key.equals(ANNOUNCED.get(entityId))) {
            return;
        }
        ANNOUNCED.put(entityId, key);

        double degX = Math.toDegrees(rotXRadians);
        double degY = Math.toDegrees(rotYRadians);
        double degZ = Math.toDegrees(rotZRadians);

        // Also to the log: chat is for the human looking at the model, the log is the record that
        // survives the session and can be diffed.
        WardenGirlMod.LOGGER.info(
                "[axistest/client] entity={} bone={} axis={} measured_deg=({}, {}, {}) measured_rad=({}, {}, {})",
                entityId, boneName, axis, degX, degY, degZ, rotXRadians, rotYRadians, rotZRadians);

        send(Component.literal("[axistest/client] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(
                                String.format(Locale.ROOT,
                                        "bone=%s  축=%s  실측 GeoBone 회전 = (x %.3f°, y %.3f°, z %.3f°)",
                                        boneName, axis, degX, degY, degZ))
                        .withStyle(ChatFormatting.WHITE)));
        send(Component.literal(String.format(Locale.ROOT,
                        "                 라디안 원값 = (%.5f, %.5f, %.5f)",
                        rotXRadians, rotYRadians, rotZRadians))
                .withStyle(ChatFormatting.DARK_AQUA));
        send(Component.literal("                 기대: " + Bones.expectation(boneName, axis))
                .withStyle(ChatFormatting.YELLOW));
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
