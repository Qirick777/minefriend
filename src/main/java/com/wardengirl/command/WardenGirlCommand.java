package com.wardengirl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AnimRegistry;
import com.wardengirl.anim.Bones;
import com.wardengirl.config.ClientConfig;
import com.wardengirl.network.ModNetwork;
import com.wardengirl.entity.WardenGirlEntity;
import com.wardengirl.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
                .then(Commands.literal("vitalcheck")
                        .executes(ctx -> vitalCheck(ctx.getSource(), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                .executes(ctx -> vitalCheck(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("actioncheck")
                        .executes(ctx -> actionCheck(ctx.getSource(), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                .executes(ctx -> actionCheck(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("blendcheck")
                        .executes(ctx -> blendCheck(ctx.getSource(), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                .executes(ctx -> blendCheck(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("action")
                        .then(Commands.literal("stop")
                                .executes(ctx -> action(ctx.getSource(), "")))
                        .then(Commands.argument("clip", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        List.of(AnimRegistry.ACTION_TEST), b))
                                .executes(ctx -> action(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "clip")))))
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
                                                        DoubleArgumentType.getDouble(ctx, "value")))))))
                // ---- T10 개체별 소유권·강화 ----------------------------------------------
                .then(Commands.literal("owner")
                        .then(Commands.literal("get")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> ownerGet(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "target")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> ownerSet(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "target"),
                                                        EntityArgument.getPlayer(ctx, "player")))))))
                .then(Commands.literal("power")
                        .then(Commands.literal("get")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> powerGet(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "target")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        // 음수는 파싱 단계에서 거절한다 — 사람에게 이유가 그대로 간다.
                                        .then(Commands.argument("stacks", LongArgumentType.longArg(0L))
                                                .executes(ctx -> powerSet(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "target"),
                                                        LongArgumentType.getLong(ctx, "stacks"))))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        // 감소 시험을 위해 음수를 받는다. 결과는 0 아래로 내려가지 않는다.
                                        .then(Commands.argument("amount", LongArgumentType.longArg())
                                                .executes(ctx -> powerAdd(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "target"),
                                                        LongArgumentType.getLong(ctx, "amount")))))))
                // ---- T11 실제 Attribute 조회 -------------------------------------------------
                .then(Commands.literal("stats")
                        .then(Commands.literal("get")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> statsGet(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "target")))))));
    }

    // ---- T10 /wardengirl owner|power -------------------------------------------------------
    //
    // 명령어는 진입점일 뿐이다(2차 설계서 3.3 / 10장). 소유권 규칙도 스택 산술도 여기 없고,
    // 전부 WardenGirlEntity 의 API 를 부른다 — 나중에 길들이기·강화 상호작용이 같은 API 를
    // 쓰면 두 경로가 저절로 같은 규칙을 따른다.

    /** 대상이 워든걸이 아니면 이유를 밝히고 거절한다. */
    private static WardenGirlEntity asWardenGirl(CommandSourceStack source, Entity target) {
        if (target instanceof WardenGirlEntity girl) {
            return girl;
        }
        source.sendFailure(Component.literal("[wardengirl] 대상이 워든걸이 아니다: "
                + target.getType().getDescriptionId() + " (" + target.getName().getString() + ")"));
        return null;
    }

    /** 사람이 로그에서 개체를 구분할 수 있게 이름·엔티티 id·UUID 를 함께 낸다. */
    private static String describe(WardenGirlEntity girl) {
        return String.format(Locale.ROOT, "%s id=%d uuid=%s",
                girl.getName().getString(), girl.getId(), girl.getUUID());
    }

    private static void ok(CommandSourceStack source, String body) {
        source.sendSuccess(() -> Component.literal("[wardengirl] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(body).withStyle(ChatFormatting.WHITE)), true);
    }

    private static int ownerGet(CommandSourceStack source, Entity target) {
        WardenGirlEntity girl = asWardenGirl(source, target);
        if (girl == null) {
            return 0;
        }
        ok(source, String.format(Locale.ROOT, "%s%n         소유자 = %s",
                describe(girl), girl.getOwnerUuid().map(java.util.UUID::toString).orElse("없음 (야생)")));
        return 1;
    }

    private static int ownerSet(CommandSourceStack source, Entity target, ServerPlayer player) {
        WardenGirlEntity girl = asWardenGirl(source, target);
        if (girl == null) {
            return 0;
        }
        if (!girl.trySetInitialOwner(player.getUUID())) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[wardengirl] 각인 거절 — 이미 소유자가 있다. %s / 기존 소유자 %s%n"
                            + "         소유권 변경·해제·이전은 제공하지 않는다.",
                    describe(girl), girl.getOwnerUuid().map(java.util.UUID::toString).orElse("?"))));
            return 0;
        }
        ok(source, String.format(Locale.ROOT, "%s%n         최초 각인 -> %s (%s)",
                describe(girl), player.getGameProfile().getName(), player.getUUID()));
        return 1;
    }

    private static int powerGet(CommandSourceStack source, Entity target) {
        WardenGirlEntity girl = asWardenGirl(source, target);
        if (girl == null) {
            return 0;
        }
        ok(source, String.format(Locale.ROOT, "%s%n         강화 스택 = %d",
                describe(girl), girl.getPowerStacks()));
        return 1;
    }

    private static int powerSet(CommandSourceStack source, Entity target, long stacks) {
        WardenGirlEntity girl = asWardenGirl(source, target);
        if (girl == null) {
            return 0;
        }
        long before = girl.getPowerStacks();
        girl.setPowerStacks(stacks);
        ok(source, String.format(Locale.ROOT, "%s%n         강화 스택 %d -> %d",
                describe(girl), before, girl.getPowerStacks()));
        return 1;
    }

    private static int powerAdd(CommandSourceStack source, Entity target, long amount) {
        WardenGirlEntity girl = asWardenGirl(source, target);
        if (girl == null) {
            return 0;
        }
        long before = girl.getPowerStacks();
        girl.addPowerStacks(amount);
        long after = girl.getPowerStacks();
        long applied = after - before;
        ok(source, String.format(Locale.ROOT,
                "%s%n         강화 스택 %d %+d -> %d%s",
                describe(girl), before, amount, after,
                applied == amount ? ""
                        : String.format(Locale.ROOT, "%n         요청 %+d 중 %+d 만 적용됐다 "
                                + "(0 하한 / long 상한에서 포화).", amount, applied)));
        return 1;
    }

    // ---- T11 /wardengirl stats get ----------------------------------------------------------
    //
    // 공식은 여기에 없다. 스택은 엔티티 API 로, 체력·근접·저항은 살아 있는 AttributeInstance 로,
    // 소닉은 엔티티의 getSonicDamage() 로 읽는다 — 즉 이 출력은 계산 결과의 재현이 아니라
    // 개체가 지금 실제로 들고 있는 값이다.
    //
    // 소수 7자리로 낸다. 천장 미달(1019.9999390 < 1020)과 수렴(스택 500 → 983.8...)을 눈으로
    // 구분하려면 이 정도가 필요하다.

    private static double baseOf(WardenGirlEntity girl, Attribute attribute) {
        AttributeInstance instance = girl.getAttribute(attribute);
        return instance == null ? Double.NaN : instance.getBaseValue();
    }

    private static int statsGet(CommandSourceStack source, Entity target) {
        WardenGirlEntity girl = asWardenGirl(source, target);
        if (girl == null) {
            return 0;
        }
        ok(source, String.format(Locale.ROOT,
                "%s%n"
                        + "         강화 스택        = %d%n"
                        + "         현재/최대 체력    = %.7f / %.7f%n"
                        + "         MAX_HEALTH base  = %.7f%n"
                        + "         ATTACK_DAMAGE base = %.7f%n"
                        + "         KNOCKBACK_RESISTANCE base = %.7f%n"
                        + "         소닉 피해        = %.7f",
                describe(girl),
                girl.getPowerStacks(),
                girl.getHealth(), girl.getMaxHealth(),
                baseOf(girl, Attributes.MAX_HEALTH),
                baseOf(girl, Attributes.ATTACK_DAMAGE),
                baseOf(girl, Attributes.KNOCKBACK_RESISTANCE),
                girl.getSonicDamage()));
        return 1;
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

    // ---- /wardengirl vitalcheck <ticks> -------------------------------------------------------

    /**
     * T5 — C1 이관 검증. See {@link com.wardengirl.client.VitalCheck}.
     *
     * <p>Separate from {@code trace} on purpose. {@code trace} judges the rig against the spec
     * ranges; this compares one layer against its own formula, and mixing the two would bury a
     * nine-line residual table inside a sixty-line report.
     */
    private static int vitalCheck(CommandSourceStack source, int ticks) {
        ModNetwork.broadcastVitalCheck(ticks);
        source.sendSuccess(() -> Component.literal("[vitalcheck] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "%d틱 동안 C1 9채널을 수식과 대조한다.%n"
                                        + "         c1_source 0 = json Molang(이관 전), 1 = Java 가산(이관 후).%n"
                                        + "         두 값 모두에서 잔차가 0 이면 이관 전후가 부호도 크기도 같다는 뜻이다.%n"
                                        + "         결과는 로그의 [vitalcheck] 행에 나온다.",
                                ticks))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    // ---- /wardengirl action <clip|stop> -------------------------------------------------------

    /**
     * T5 2라운드 — C3 직접 평가 재생. See {@link com.wardengirl.client.ActionMotion}.
     *
     * <p>No controller is involved. The clip name and a trigger sequence are synched to the client,
     * which evaluates the clip itself and adds it in {@code setCustomAnimations} — the only place a
     * layer survives alongside C2 (Part 4.2).
     */
    private static int action(CommandSourceStack source, String clip) {
        int count = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (WardenGirlEntity e : level.getEntities(ModEntities.WARDEN_GIRL.get(), x -> true)) {
                e.playAction(clip);
                count++;
            }
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("[action] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                clip.isEmpty() ? "재생 중지 — 대상 %2$d마리"
                                        : "클립 '%s' 재생 — 대상 %d마리 (컨트롤러 없음, 직접 평가)",
                                clip, n))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    // ---- /wardengirl actioncheck <ticks> ------------------------------------------------------

    private static int actionCheck(CommandSourceStack source, int ticks) {
        ModNetwork.broadcastActionCheck(ticks);
        source.sendSuccess(() -> Component.literal("[actioncheck] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "%d틱 동안 C3 직접 평가 경로를 검증한다.%n"
                                        + "         이 창 안에서 /wardengirl action actiontest 를 걸어야 한다.%n"
                                        + "         걸지 않으면 잔차가 전부 0 이지만 '측정 불가' 로 나온다.%n"
                                        + "         결과는 로그의 [actioncheck] 행에 나온다.",
                                ticks))
                        .withStyle(ChatFormatting.WHITE)), true);
        return 1;
    }

    // ---- /wardengirl blendcheck <ticks> -------------------------------------------------------

    private static int blendCheck(CommandSourceStack source, int ticks) {
        ModNetwork.broadcastBlendCheck(ticks);
        source.sendSuccess(() -> Component.literal("[blendcheck] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                                "%d틱 동안 겹치는 4축에서 C2 + C3 합성을 실측한다.%n"
                                        + "         ai on 과 action actiontest 가 겹쳐야 한다. 겹친 프레임이 0 이면 측정 불가다.%n"
                                        + "         head.yRot 판정에는 look_gain 0 이 필요하다.%n"
                                        + "         결과는 로그의 [blendcheck] 행에 나온다.",
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
