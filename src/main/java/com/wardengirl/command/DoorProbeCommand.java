package com.wardengirl.command;

// T23-DOORPROBE — 사용자 월드 원인 계측용 임시 읽기 전용 명령. 원인 확정 후 제거한다.
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Locale;

/** 상태를 바꾸지 않는다. 문을 열지도, 경로를 만들지도, 개체를 움직이지도 않는다. */
public final class DoorProbeCommand {

    private DoorProbeCommand() { }

    public static ArgumentBuilder<CommandSourceStack, ?> node() {
        return Commands.literal("door")
                .then(Commands.literal("probe")
                        .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                .executes(c -> probe(c.getSource(),
                                        EntityArgument.getEntity(c, "wardenGirl")))));
    }

    private static BlockPos lowerDoor(Level level, BlockPos at) {
        BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof DoorBlock)) {
            return null;
        }
        return s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? at.below() : at;
    }

    private static int probe(CommandSourceStack src, Entity e) {
        if (!(e instanceof WardenGirlEntity g)) {
            src.sendFailure(Component.literal("[door probe] 대상이 워든걸이 아니다"));
            return 0;
        }
        Level level = g.level();
        StringBuilder b = new StringBuilder("[door probe] ").append(g.getName().getString());
        Player owner = g.serverOwner();
        b.append(String.format(Locale.ROOT, "%n  hasOwner=%s serverOwner=%s ownerDist=%s "
                        + "sameLevel=%s localAnchor=%s",
                g.hasOwner(), owner != null,
                owner == null ? "-" : String.format(Locale.ROOT, "%.2f", g.distanceTo(owner)),
                owner != null && owner.level() == level, g.localAnchor()));
        var sm = g.specialMovement();
        b.append(String.format(Locale.ROOT, "%n  specialMovement=%s kind=%s active=%s "
                        + "gapApproach=%s diveFacingHeld=%s diveYaw=%s",
                sm.getState(), sm.getPlan() == null ? "none" : sm.getPlan().kind(), sm.isActive(),
                g.gapJump().hasApproachTarget(), g.gapJump().diveFacingHeld(),
                String.format(Locale.ROOT, "%.1f", g.gapJump().diveYaw())));
        b.append(String.format(Locale.ROOT, "%n  yRot=%.1f yBodyRot=%.1f yHeadRot=%.1f "
                        + "onGround=%s horizontalCollision=%s",
                g.getYRot(), g.yBodyRot, g.yHeadRot, g.onGround(), g.horizontalCollision));
        PathNavigation nav = g.getNavigation();
        boolean ground = nav instanceof GroundPathNavigation;
        b.append(String.format(Locale.ROOT, "%n  navigation=%s ground=%s canOpenDoors=%s "
                        + "canPassDoors=%s",
                nav.getClass().getSimpleName(), ground,
                ground ? String.valueOf(((GroundPathNavigation) nav).canOpenDoors()) : "-",
                ground ? String.valueOf(((GroundPathNavigation) nav).canPassDoors()) : "-"));
        Path path = nav.getPath();
        b.append(String.format(Locale.ROOT, "%n  path null=%s done=%s canReach=%s next=%s/%s",
                path == null, path == null ? "-" : String.valueOf(path.isDone()),
                path == null ? "-" : String.valueOf(path.canReach()),
                path == null ? "-" : String.valueOf(path.getNextNodeIndex()),
                path == null ? "-" : String.valueOf(path.getNodeCount())));
        if (path != null && !path.isDone()) {
            int from = path.getNextNodeIndex();
            int to = Math.min(from + 2, path.getNodeCount());
            for (int i = from; i < to; i++) {
                Node n = path.getNode(i);
                b.append(String.format(Locale.ROOT, "%n  node[%d]=(%d,%d,%d) block=%s",
                        i, n.x, n.y, n.z,
                        level.getBlockState(new BlockPos(n.x, n.y, n.z)).getBlock()
                                .getDescriptionId()));
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos at = new BlockPos(n.x, n.y + dy, n.z);
                    BlockPos door = lowerDoor(level, at);
                    if (door == null) {
                        continue;
                    }
                    BlockState st = level.getBlockState(door);
                    double dist = Math.sqrt(g.distanceToSqr(door.getX() + 0.5D, g.getY(),
                            door.getZ() + 0.5D));
                    b.append(String.format(Locale.ROOT,
                            "%n    door=%s wooden=%s open=%s dist=%.3f <=OPEN_DISTANCE(%.2f)=%s",
                            door.toShortString(), DoorBlock.isWoodenDoor(st),
                            st.getBlock() instanceof DoorBlock db && db.isOpen(st), dist,
                            com.wardengirl.entity.WardenGirlDoorHelper.OPEN_DISTANCE,
                            dist <= com.wardengirl.entity.WardenGirlDoorHelper.OPEN_DISTANCE));
                }
            }
        }
        BlockPos me = g.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-6, -2, -6), me.offset(6, 2, 6))) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(st)) {
                double d = p.distToCenterSqr(g.getX(), g.getY(), g.getZ());
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        b.append(String.format(Locale.ROOT, "%n  nearestWoodenDoor=%s dist=%s",
                best == null ? "none" : best.toShortString(),
                best == null ? "-" : String.format(Locale.ROOT, "%.3f", Math.sqrt(bestD))));
        String stop;
        if (!g.hasOwner()) {
            stop = "hasOwner=false (야생이면 열지 않는다)";
        } else if (!ground || !((GroundPathNavigation) nav).canOpenDoors()) {
            stop = "navigation canOpenDoors=false";
        } else if (path == null) {
            stop = "path=null (이동 목적이 없거나 경로 미생성)";
        } else if (path.isDone()) {
            stop = "path.isDone=true";
        } else {
            stop = "앞 두 노드 검사까지 진행 — 위 door 줄을 보라";
        }
        b.append("\n  DoorHelper 중단 조건 = ").append(stop);
        final String out = b.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }
}
