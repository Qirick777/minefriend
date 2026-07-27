package com.wardengirl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.Bones;
import com.wardengirl.config.ClientConfig;
import com.wardengirl.network.ModNetwork;
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
import java.util.Map;
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

    private static final SuggestionProvider<CommandSourceStack> PARAM_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(AnimParams.all().keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> PRESET_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(AnimParams.Preset.values())
                            .map(pr -> pr.name().toLowerCase(Locale.ROOT)).toList(), builder);

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
                                                StringArgumentType.getString(ctx, "axis"),
                                                Bones.AXIS_TEST_ANGLE_DEGREES))
                                        .then(Commands.argument("degrees",
                                                        DoubleArgumentType.doubleArg(-180.0D, 180.0D))
                                                .executes(ctx -> axisTest(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "bone"),
                                                        StringArgumentType.getString(ctx, "axis"),
                                                        DoubleArgumentType.getDouble(ctx, "degrees")))))))
                .then(Commands.literal("ai")
                        .then(Commands.literal("on")
                                .executes(ctx -> setAi(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setAi(ctx.getSource(), false))))
                .then(Commands.literal("signtest")
                        .then(Commands.literal("on")
                                .executes(ctx -> setSignTest(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setSignTest(ctx.getSource(), false))))
                .then(Commands.literal("trace")
                        .executes(ctx -> trace(ctx.getSource(), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                .executes(ctx -> trace(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("param")
                        .then(Commands.literal("list")
                                .executes(ctx -> paramList(ctx.getSource())))
                        .then(Commands.literal("reload")
                                .executes(ctx -> paramReload(ctx.getSource())))
                        .then(Commands.literal("preset")
                                .then(Commands.argument("level", StringArgumentType.word())
                                        .suggests(PRESET_SUGGESTIONS)
                                        .executes(ctx -> paramPreset(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "level")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(PARAM_SUGGESTIONS)
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> paramSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        DoubleArgumentType.getDouble(ctx, "value"))))))));
    }

    // ---- /wardengirl ai <on|off> --------------------------------------------------------------

    /**
     * P1-T5 임시 이동 AI 토글. Default off.
     *
     * <p>Applies to every loaded WardenGirl rather than a targeted one: the point is to look at the
     * walk cycle, and having to aim at the mob first is friction in the one loop this command
     * exists to serve. The look goals are untouched — only the stroll goal moves.
     */
    private static int setAi(CommandSourceStack source, boolean on) {
        int count = 0;
        for (net.minecraft.server.level.ServerLevel level : source.getServer().getAllLevels()) {
            for (WardenGirlEntity e : level.getEntities(
                    com.wardengirl.registry.ModEntities.WARDEN_GIRL.get(), x -> true)) {
                e.setMovementAi(on);
                count++;
            }
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("[ai] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "이동 AI %s — 대상 %d마리 (WaterAvoidingRandomStrollGoal)%n"
                                        + "         시선 Goal 2종은 그대로다. T5 걷기 확인용이며 기본값은 off 다.",
                                on ? "ON" : "OFF", n))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    /** T5 임시 — json 키프레임 부호 실측. See {@link com.wardengirl.anim.AnimRegistry#SIGN_TEST}. */
    private static int setSignTest(CommandSourceStack source, boolean on) {
        int count = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (WardenGirlEntity e : level.getEntities(ModEntities.WARDEN_GIRL.get(), x -> true)) {
                e.setSignTest(on);
                count++;
            }
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("[signtest] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                        "json 부호 실측 애니메이션 %s — 대상 %d마리", on ? "ON" : "OFF", n))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    // ---- /wardengirl trace <ticks> ----------------------------------------------------------

    private static int trace(CommandSourceStack source, int ticks) {
        ModNetwork.broadcastTrace(ticks);
        source.sendSuccess(() -> Component.literal("[trace] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "%d틱 동안 본 9개 × 3축 전부 기록한다.%n"
                                        + "         각 축을 사양 범위와 대조해 OK / FAIL 범위이탈 / FAIL 단조누적 으로 판정한다.%n"
                                        + "         결과는 로그의 [trace] 행에 나온다 (클라이언트만 본을 볼 수 있다).",
                                ticks))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    // ---- /wardengirl param ------------------------------------------------------------------

    private static int paramList(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                        "[param] " + AnimParams.all().size() + "개.  key = 현재값 (기본값) [단위, 태스크]")
                .withStyle(ChatFormatting.GOLD), false);
        for (AnimParams.Param p : AnimParams.all().values()) {
            boolean changed = p.get() != p.defaultValue;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "  %-22s = %-9s (%s) [%s, %s]  %s",
                            p.key, trim(p.get()), trim(p.defaultValue), p.unit, p.task, p.description))
                    .withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int paramSet(CommandSourceStack source, String key, double value) {
        AnimParams.Param p = AnimParams.get(key);
        if (p == null) {
            source.sendFailure(Component.literal("[param] 알 수 없는 key: " + key
                    + "  (/wardengirl param list 로 확인)"));
            return 0;
        }
        double before = p.get();
        p.set(value);
        ModNetwork.broadcast(Map.of(key, value));

        source.sendSuccess(() -> Component.literal("[param] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "%s : %s -> %s  [%s]%n         %s%n         영향 본: %s",
                                key, trim(before), trim(value), p.unit, p.description,
                                p.affects.isEmpty() ? "(없음)" : String.join(", ", p.affects)))
                        .withStyle(ChatFormatting.WHITE)), true);
        source.sendSuccess(() -> Component.literal(
                        "         ※ 다음 프레임의 실측 본 회전값은 클라이언트가 [param/client] 로 출력한다.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int paramPreset(CommandSourceStack source, String levelRaw) {
        AnimParams.Preset preset = AnimParams.Preset.parse(levelRaw);
        if (preset == null) {
            source.sendFailure(Component.literal(
                    "[param] preset 은 min / low / default / high / max 중 하나여야 한다. 받은 값: " + levelRaw));
            return 0;
        }
        Map<String, double[]> changed = AnimParams.applyPreset(preset);
        Map<String, Double> sync = new java.util.LinkedHashMap<>();
        changed.forEach((k, v) -> sync.put(k, v[1]));
        if (!sync.isEmpty()) {
            ModNetwork.broadcast(sync);
        }

        source.sendSuccess(() -> Component.literal("[param] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "preset %s (기본값 x%s) 적용.  %d개 변경 / 전체 %d개",
                                preset.name().toLowerCase(Locale.ROOT), trim(preset.factor),
                                changed.size(), AnimParams.all().size()))
                        .withStyle(ChatFormatting.WHITE)), true);
        changed.forEach((k, v) -> source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %-22s %s -> %s", k, trim(v[0]), trim(v[1]))).withStyle(ChatFormatting.GRAY), false));
        if (changed.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  (이미 그 값이다 — 변경 없음)")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int paramReload(CommandSourceStack source) {
        Map<String, double[]> changed = ClientConfig.load();
        Map<String, Double> sync = new java.util.LinkedHashMap<>();
        changed.forEach((k, v) -> sync.put(k, v[1]));
        if (!sync.isEmpty()) {
            ModNetwork.broadcast(sync);
        }
        source.sendSuccess(() -> Component.literal("[param] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("TOML 재로드. 변경된 key " + changed.size() + "개")
                        .withStyle(ChatFormatting.WHITE)), true);
        if (changed.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                            "  (파일 값과 현재 값이 같다. 전용 서버에서는 클라이언트 TOML 을 읽을 수 없다는 점에 주의)")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        changed.forEach((k, v) -> source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %-22s %s -> %s", k, trim(v[0]), trim(v[1]))).withStyle(ChatFormatting.GRAY), false));
        return 1;
    }

    /** Drops trailing zeros so the tables stay readable. */
    private static String trim(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 10000.0D) / 10000.0D);
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

    private static int axisTest(CommandSourceStack source, String boneName, String axisRaw,
                                double degrees) {
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

        target.setAxisTest(boneName + ":" + axis.name().toLowerCase(Locale.ROOT) + ":" + degrees);
        String after = target.getAxisTest();

        double angle = degrees;
        double rx = axis == Bones.Axis.X ? angle : 0.0D;
        double ry = axis == Bones.Axis.Y ? angle : 0.0D;
        double rz = axis == Bones.Axis.Z ? angle : 0.0D;
        double distance = Math.sqrt(target.distanceToSqr(source.getPosition()));

        source.sendSuccess(() -> Component.literal("[axistest] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "대상 UUID=%s  (거리 %.1fm)%n           본 = %s,  축 = %s,  각도 = +%.1f°%n"
                                        + "           요청 회전 (xRot, yRot, zRot) = (%.1f, %.1f, %.1f)°%n"
                                        + "           상태 변경: \"%s\" -> \"%s\"%n"
                                        + "           Part 4.0 유도: %s",
                                target.getUUID(), distance,
                                boneName, axis, angle,
                                rx, ry, rz,
                                before, after,
                                Bones.expectation(boneName, axis, degrees)))
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
