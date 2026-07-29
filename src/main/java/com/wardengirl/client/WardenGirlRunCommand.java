package com.wardengirl.client;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * T9 달리기 자세 육안 튜닝용 <b>임시</b> 명령어. 최종 수치가 정해지면 이 파일과
 * {@code AnimParams} 의 {@code run_*} 항목, {@code WardenGirlModel.lastSwing/lastRun} 을 지운다.
 *
 * <h2>왜 클라이언트 전용인가</h2>
 *
 * 바꾸는 것이 시각값뿐이라 서버가 알 필요가 없다. 클라이언트 명령으로 등록하면
 * <b>새 패킷도, 서버 왕복도, 동기화도 없다</b> — 값은 이 클라이언트의 {@link AnimParams}
 * 인스턴스에만 들어가고 다음 렌더 프레임이 바로 읽는다. {@code show} 가 보여주는
 * {@code swingAmount} 와 달리기 가중치도 클라이언트에만 있는 값이라 여기서만 읽을 수 있다.
 *
 * <p>대가는 하나다 — <b>이 값은 이 클라이언트에만 적용된다.</b> 두 클라이언트로 보면 다른
 * 쪽은 기본값 그대로다. 최종 상수로 굳히면 양쪽이 같아진다.
 *
 * <p>저장하지 않는다. 설정 파일도 NBT 도 쓰지 않으므로 나가면 기본값으로 돌아온다.
 *
 * <h2>루트가 {@code /wg} 인 이유</h2>
 *
 * {@code /wardengirl} 은 서버 명령 루트다. 같은 루트를 클라이언트 디스패처에도 등록하면
 * 어느 쪽이 먹는지가 애매해지므로 겹치지 않는 짧은 루트를 따로 쓴다.
 */
@Mod.EventBusSubscriber(modid = WardenGirlMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class WardenGirlRunCommand {

    private WardenGirlRunCommand() {
    }

    /** 사람이 입력하는 범위. 기울임은 도, 팔은 퍼센트다. */
    private static final double LEAN_MIN = 0.0D;
    private static final double LEAN_MAX = 16.0D;
    private static final double ARM_MIN = 0.0D;
    private static final double ARM_MAX = 40.0D;

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wg")
                .then(Commands.literal("run")
                        .executes(WardenGirlRunCommand::show)
                        .then(Commands.literal("lean")
                                .then(Commands.argument("deg",
                                                DoubleArgumentType.doubleArg(LEAN_MIN, LEAN_MAX))
                                        .executes(ctx -> lean(ctx,
                                                DoubleArgumentType.getDouble(ctx, "deg")))))
                        .then(Commands.literal("arm")
                                .then(Commands.argument("percent",
                                                DoubleArgumentType.doubleArg(ARM_MIN, ARM_MAX))
                                        .executes(ctx -> arm(ctx,
                                                DoubleArgumentType.getDouble(ctx, "percent")))))
                        .then(Commands.literal("show").executes(WardenGirlRunCommand::show))
                        .then(Commands.literal("reset")
                                .executes(WardenGirlRunCommand::reset))));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void gold(CommandContext<CommandSourceStack> ctx, String head, String body) {
        ctx.getSource().sendSuccess(() -> Component.literal(head).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(body).withStyle(ChatFormatting.WHITE)), false);
    }

    private static int lean(CommandContext<CommandSourceStack> ctx, double deg) {
        double v = clamp(deg, LEAN_MIN, LEAN_MAX);
        AnimParams.RUN_LEAN.set(v);
        gold(ctx, "[wg run] ", String.format(Locale.ROOT,
                "상체 기울임 %.1f도%n         "
                        + "양수 입력 = 앞으로 숙임. 내부에서는 body 에 -%.1f 도로 들어간다.%n         "
                        + "head 에 +%.1f 도가 자동으로 붙어 최종 시선은 그대로다 — 따로 만질 값 없다.",
                v, v, v));
        return 1;
    }

    private static int arm(CommandContext<CommandSourceStack> ctx, double percent) {
        double v = clamp(percent, ARM_MIN, ARM_MAX);
        AnimParams.RUN_ARM_GAIN.set(v / 100.0D);
        gold(ctx, "[wg run] ", String.format(Locale.ROOT,
                "팔 흔들림 +%.0f%%%n         걷기 팔 진폭에 곱해진다. 달리기 가중치가 0 이면 효과도 0.",
                v));
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        double lean = AnimParams.RUN_LEAN.get();
        double armPct = AnimParams.RUN_ARM_GAIN.get() * 100.0D;
        gold(ctx, "[wg run] ", String.format(Locale.ROOT,
                "lean %.1f도  arm +%.0f%%%n"
                        + "         swingAmount %.4f  ->  달리기 가중치 %.3f%n"
                        + "         가중치 구간 run_start %.2f ~ run_full %.2f "
                        + "(/wardengirl param set 으로 조절)%n"
                        + "         값은 저장되지 않는다. 나갔다 오면 기본값 lean %.1f / arm +%.0f%% 이다.",
                lean, armPct, WardenGirlModel.lastSwing, WardenGirlModel.lastRun,
                AnimParams.RUN_START.get(), AnimParams.RUN_FULL.get(),
                AnimParams.RUN_LEAN.defaultValue, AnimParams.RUN_ARM_GAIN.defaultValue * 100.0D));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        AnimParams.RUN_LEAN.set(AnimParams.RUN_LEAN.defaultValue);
        AnimParams.RUN_ARM_GAIN.set(AnimParams.RUN_ARM_GAIN.defaultValue);
        gold(ctx, "[wg run] ", String.format(Locale.ROOT,
                "기본값 복귀 — lean %.1f도  arm +%.0f%%",
                AnimParams.RUN_LEAN.get(), AnimParams.RUN_ARM_GAIN.get() * 100.0D));
        return 1;
    }
}
