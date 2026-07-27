package com.wardengirl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wardengirl.anim.Bones;
import com.wardengirl.entity.WardenGirlEntity;
import com.wardengirl.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Verification commands. Design doc Part 6.3.
 *
 * <p>T1 implements two of the eleven: {@code summon} and {@code axistest}. The rest arrive with the
 * systems they inspect.
 *
 * <p>Both obey design doc Part 6.2. In particular {@code axistest} does not print "성공" — it
 * prints the state it changed, before and after, and the client separately prints the rotation it
 * actually measured off the bone.
 */
public final class WardenGirlCommand {

    private WardenGirlCommand() {
    }

    private static final double SEARCH_RADIUS = 64.0D;

    private static final SuggestionProvider<CommandSourceStack> BONE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(Bones.ALL, builder);

    private static final SuggestionProvider<CommandSourceStack> AXIS_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    List.of("x", "y", "z", "clear"), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wardengirl")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("summon")
                        .executes(ctx -> summon(ctx.getSource(), ctx.getSource().getPosition()))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> summon(ctx.getSource(),
                                        Vec3Argument.getVec3(ctx, "pos")))))
                .then(Commands.literal("axistest")
                        .then(Commands.argument("bone", StringArgumentType.word())
                                .suggests(BONE_SUGGESTIONS)
                                .then(Commands.argument("axis", StringArgumentType.word())
                                        .suggests(AXIS_SUGGESTIONS)
                                        .executes(ctx -> axisTest(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "bone"),
                                                StringArgumentType.getString(ctx, "axis")))))));
    }

    // ---- /wardengirl summon [pos] ------------------------------------------------------------

    private static int summon(CommandSourceStack source, Vec3 pos) {
        ServerLevel level = source.getLevel();

        WardenGirlEntity entity = ModEntities.WARDEN_GIRL.get().create(level);
        if (entity == null) {
            source.sendFailure(Component.literal("[summon] 엔티티 생성 실패 — EntityType.create 가 null 을 반환했다."));
            return 0;
        }

        entity.moveTo(pos.x, pos.y, pos.z, source.getRotation().y, 0.0F);
        if (!level.addFreshEntity(entity)) {
            source.sendFailure(Component.literal("[summon] level.addFreshEntity 가 거부했다."));
            return 0;
        }

        // Report the entity's own post-spawn position, not the requested one — they can differ.
        Vec3 actual = entity.position();
        source.sendSuccess(() -> Component.literal("[summon] ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(String.format(Locale.ROOT,
                                "UUID=%s  id=%d%n         요청 위치 = (%.2f, %.2f, %.2f)%n         실제 위치 = (%.2f, %.2f, %.2f)  차원=%s",
                                entity.getUUID(), entity.getId(),
                                pos.x, pos.y, pos.z,
                                actual.x, actual.y, actual.z,
                                level.dimension().location()))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    // ---- /wardengirl axistest <bone> <x|y|z|clear> -------------------------------------------

    private static int axisTest(CommandSourceStack source, String boneName, String axisRaw) {
        if (!Bones.ALL.contains(boneName)) {
            source.sendFailure(Component.literal(
                    "[axistest] 알 수 없는 본: " + boneName + "  (사용 가능: " + String.join(", ", Bones.ALL) + ")"));
            return 0;
        }

        WardenGirlEntity target = nearest(source);
        if (target == null) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[axistest] 반경 %.0f 블록 안에 워든걸이 없다. 먼저 /wardengirl summon 을 실행해라.",
                    SEARCH_RADIUS)));
            return 0;
        }

        String before = target.getAxisTest();
        boolean clearing = axisRaw.equalsIgnoreCase("clear")
                || axisRaw.equalsIgnoreCase("off")
                || axisRaw.equalsIgnoreCase("none");

        if (clearing) {
            target.setAxisTest("");
            String after = target.getAxisTest();
            source.sendSuccess(() -> Component.literal("[axistest] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format(Locale.ROOT,
                                    "해제.  대상 UUID=%s%n           상태 변경: \"%s\" -> \"%s\"%n           요청 회전 (xRot, yRot, zRot) = (0.0, 0.0, 0.0)°  — 전 본 0 으로 복귀",
                                    target.getUUID(), before, after))
                            .withStyle(ChatFormatting.WHITE)), true);
            return 1;
        }

        Bones.Axis axis = Bones.Axis.parse(axisRaw);
        if (axis == null) {
            source.sendFailure(Component.literal(
                    "[axistest] 축은 x / y / z / clear 중 하나여야 한다. 받은 값: " + axisRaw));
            return 0;
        }

        target.setAxisTest(boneName + ":" + axis.name().toLowerCase(Locale.ROOT));
        String after = target.getAxisTest();

        double angle = Bones.AXIS_TEST_ANGLE_DEGREES;
        double rx = axis == Bones.Axis.X ? angle : 0.0D;
        double ry = axis == Bones.Axis.Y ? angle : 0.0D;
        double rz = axis == Bones.Axis.Z ? angle : 0.0D;
        double distance = Math.sqrt(target.distanceToSqr(source.getPosition()));

        source.sendSuccess(() -> Component.literal("[axistest] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "대상 UUID=%s  (거리 %.1fm)%n           본 = %s,  축 = %s,  각도 = +%.1f°%n"
                                        + "           요청 회전 (xRot, yRot, zRot) = (%.1f, %.1f, %.1f)°%n"
                                        + "           상태 변경: \"%s\" -> \"%s\"%n"
                                        + "           Part 4.0 기대: %s",
                                target.getUUID(), distance,
                                boneName, axis, angle,
                                rx, ry, rz,
                                before, after,
                                Bones.expectation(boneName, axis)))
                        .withStyle(ChatFormatting.WHITE)), true);
        source.sendSuccess(() -> Component.literal(
                        "           ※ 한 번에 한 축만 적용된다. 나머지 두 축과 다른 본은 전부 0 으로 초기화된다.")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal(
                        "           ※ 실측 회전값은 클라이언트가 [axistest/client] 로 따로 출력한다.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static WardenGirlEntity nearest(CommandSourceStack source) {
        Vec3 origin = source.getPosition();
        AABB box = new AABB(origin, origin).inflate(SEARCH_RADIUS);
        return source.getLevel()
                .getEntitiesOfClass(WardenGirlEntity.class, box)
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(origin)))
                .orElse(null);
    }
}
