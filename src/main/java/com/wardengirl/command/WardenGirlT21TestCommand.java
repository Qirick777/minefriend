package com.wardengirl.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.wardengirl.entity.WardenGirlEntity;
import com.wardengirl.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * T21 <b>사람 확인 전용</b> 상황 생성 명령. 기능 코드가 아니다.
 *
 * <pre>
 *   /wardengirl test t21_retreat_sonic
 * </pre>
 *
 * <p>명령은 <b>상황만</b> 만든다. 후퇴 boolean 도, 소닉 age 도, 워든걸 target 도 직접 넣지
 * 않는다. 좀비 target 만 소환된 워든걸로 지정하고 나머지는 실제 AI 가 굴러가게 둔다.
 *
 * <h2>T22 에서 통째로 지운다</h2>
 *
 * 이 파일과 {@code WardenGirlCommand} 의 {@code .then(WardenGirlT21TestCommand.node())} 한
 * 줄이 전부다. 일반 AI 어디에서도 {@link #TEST_TAG} 를 보지 않는다 — 태그는 이 명령이 자기가
 * 만든 개체를 다시 지울 때만 쓴다.
 */
public final class WardenGirlT21TestCommand {

    /** 이 명령이 만든 개체에만 붙는다. 일반 AI 는 이 태그를 읽지 않는다. */
    public static final String TEST_TAG = "wardengirl_t21_test";

    /** 재실행 시 지우는 반경. 실행 플레이어 기준이다. */
    private static final double CLEANUP_RADIUS = 64.0D;

    /** 플레이어에서 워든걸까지, 워든걸에서 좀비까지의 거리. */
    private static final double GIRL_OFFSET = 2.0D;
    private static final double ZOMBIE_GAP = 1.25D;

    /** 워든걸이 도망칠 최소 공간. 좀비 반대 방향으로 이만큼 연속으로 걸을 수 있어야 한다. */
    private static final int RETREAT_CLEARANCE = 6;

    private static final float GIRL_HEALTH = 8.5F;
    private static final double ZOMBIE_SPEED = 0.05D;
    private static final double ZOMBIE_ATTACK = 2.0D;
    private static final double ZOMBIE_HEALTH = 100.0D;
    private static final double ZOMBIE_FOLLOW_RANGE = 32.0D;

    private WardenGirlT21TestCommand() {
    }

    /** {@code WardenGirlCommand} 가 이 한 줄만 붙인다. */
    public static ArgumentBuilder<CommandSourceStack, ?> node() {
        return Commands.literal("test")
                .then(Commands.literal("t21_retreat_sonic")
                        .executes(ctx -> run(ctx.getSource())));
    }

    private static int run(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[T21] 실행 주체가 실제 플레이어여야 한다 — 콘솔·커맨드블록에서는 아무것도 소환하지 않는다."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            source.sendFailure(Component.literal(
                    "[T21] 난이도가 평화로움이라 좀비가 유지되지 않는다. 명령은 난이도를 바꾸지 않는다 — "
                            + "직접 easy 이상으로 바꾼 뒤 다시 실행해라."));
            return 0;
        }

        int removed = cleanup(level, player);

        Vec3 origin = player.position();
        float yaw = player.getYRot();
        Vec3 forward = new Vec3(-Mth.sin(yaw * ((float) Math.PI / 180.0F)), 0.0D,
                Mth.cos(yaw * ((float) Math.PI / 180.0F))).normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);

        List<String> failures = new ArrayList<>();
        Placement chosen = null;
        String[] names = {"정면", "오른쪽", "왼쪽", "뒤쪽"};
        Vec3[] dirs = {forward, right, right.scale(-1.0D), forward.scale(-1.0D)};
        for (int i = 0; i < dirs.length; i++) {
            String why = check(level, player, origin, dirs[i]);
            if (why == null) {
                chosen = new Placement(names[i], dirs[i],
                        origin.add(dirs[i].scale(GIRL_OFFSET)),
                        origin.add(dirs[i].scale(GIRL_OFFSET + ZOMBIE_GAP)));
                break;
            }
            failures.add(names[i] + ": " + why);
        }
        if (chosen == null) {
            source.sendFailure(Component.literal("[T21] 네 방향 모두 소환할 수 없다. 아무것도 소환하지 않았다.\n         "
                    + String.join("\n         ", failures)));
            return 0;
        }

        WardenGirlEntity girl = ModEntities.WARDEN_GIRL.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (girl == null || zombie == null) {
            source.sendFailure(Component.literal("[T21] 엔티티 생성 실패 — create 가 null 을 반환했다."));
            return 0;
        }

        float facing = yawTowards(chosen.girl, chosen.zombie);
        girl.moveTo(chosen.girl.x, chosen.girl.y, chosen.girl.z, facing, 0.0F);
        girl.setPersistenceRequired();
        girl.addTag(TEST_TAG);
        if (!level.addFreshEntity(girl)) {
            source.sendFailure(Component.literal("[T21] 워든걸 addFreshEntity 가 거부했다."));
            return 0;
        }
        girl.trySetInitialOwner(player.getUUID());
        girl.setPowerStacks(0L);                        // 기존 API. 능력치를 정상 재계산한다.
        girl.setHealth(GIRL_HEALTH);

        zombie.moveTo(chosen.zombie.x, chosen.zombie.y, chosen.zombie.z, facing + 180.0F, 0.0F);
        zombie.setPersistenceRequired();
        zombie.addTag(TEST_TAG);
        setBase(zombie, Attributes.MOVEMENT_SPEED, ZOMBIE_SPEED);
        setBase(zombie, Attributes.ATTACK_DAMAGE, ZOMBIE_ATTACK);
        setBase(zombie, Attributes.MAX_HEALTH, ZOMBIE_HEALTH);
        setBase(zombie, Attributes.FOLLOW_RANGE,
                Math.max(ZOMBIE_FOLLOW_RANGE, zombie.getAttributeValue(Attributes.FOLLOW_RANGE)));
        zombie.setHealth((float) ZOMBIE_HEALTH);
        // 낮에 타 죽으면 상황이 무너진다. 무적·NoAI 는 쓰지 않는다.
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
        if (!level.addFreshEntity(zombie)) {
            girl.discard();
            source.sendFailure(Component.literal("[T21] 좀비 addFreshEntity 가 거부했다."));
            return 0;
        }
        // 상황 조성용으로만 허용된 직접 지정이다. 워든걸 target 은 건드리지 않는다.
        zombie.setTarget(girl);

        report(source, player, level, girl, zombie, chosen, removed);
        return 1;
    }

    /** 반경 안의 <b>이 명령이 만든</b> 개체만 지운다. */
    private static int cleanup(ServerLevel level, ServerPlayer player) {
        AABB box = player.getBoundingBox().inflate(CLEANUP_RADIUS);
        List<Entity> old = level.getEntities((Entity) null, box, e -> e.getTags().contains(TEST_TAG));
        for (Entity e : old) {
            e.discard();
        }
        return old.size();
    }

    /** 이 배치가 쓸 수 있는가. 쓸 수 있으면 {@code null}, 아니면 실패 사유. */
    private static String check(ServerLevel level, ServerPlayer player, Vec3 origin, Vec3 dir) {
        Vec3 girl = origin.add(dir.scale(GIRL_OFFSET));
        Vec3 zombie = origin.add(dir.scale(GIRL_OFFSET + ZOMBIE_GAP));
        EntityType<?> girlType = ModEntities.WARDEN_GIRL.get();

        String why = spot(level, girl, girlType.getWidth(), girlType.getHeight());
        if (why != null) {
            return "워든걸 자리 " + why;
        }
        why = spot(level, zombie, EntityType.ZOMBIE.getWidth(), EntityType.ZOMBIE.getHeight());
        if (why != null) {
            return "좀비 자리 " + why;
        }
        if (blocked(level, player, player.getEyePosition(), girl.add(0.0D, girlType.getHeight() * 0.85D, 0.0D))) {
            return "플레이어-워든걸 사이가 막혔다";
        }
        if (blocked(level, player, girl.add(0.0D, girlType.getHeight() * 0.85D, 0.0D),
                zombie.add(0.0D, EntityType.ZOMBIE.getHeight() * 0.85D, 0.0D))) {
            return "워든걸-좀비 사이가 막혔다";
        }
        // 좀비 반대 방향으로 연속으로 걸을 수 있어야 후퇴가 성립한다.
        for (int i = 1; i <= RETREAT_CLEARANCE; i++) {
            Vec3 step = girl.subtract(dir.scale(i));
            if (walkType(level, step) != BlockPathTypes.WALKABLE) {
                return "후퇴 공간이 " + (i - 1) + "블록뿐이다(필요 " + RETREAT_CLEARANCE + ")";
            }
        }
        return null;
    }

    /**
     * 한 자리가 안전한 육상 자리인가. 바닐라 {@code WalkNodeEvaluator.getBlockPathTypeStatic}
     * 하나가 고체 바닥·용암·불·마그마·선인장·낭떠러지를 전부 판정한다. 캠프파이어만 바닐라가
     * 위험으로 표시하지 않으므로 따로 본다.
     */
    private static String spot(ServerLevel level, Vec3 pos, float width, float height) {
        BlockPathTypes type = walkType(level, pos);
        if (type != BlockPathTypes.WALKABLE) {
            return "지형이 부적합하다(PathType=" + type + ")";
        }
        BlockPos block = BlockPos.containing(pos);
        if (CampfireBlock.isLitCampfire(level.getBlockState(block))
                || CampfireBlock.isLitCampfire(level.getBlockState(block.below()))) {
            return "불붙은 캠프파이어 위다";
        }
        AABB box = new AABB(pos.x - width / 2.0D, pos.y, pos.z - width / 2.0D,
                pos.x + width / 2.0D, pos.y + height, pos.z + width / 2.0D);
        if (!level.noCollision(null, box)) {
            return "충돌 공간이 없다";
        }
        return null;
    }

    private static BlockPathTypes walkType(ServerLevel level, Vec3 pos) {
        return WalkNodeEvaluator.getBlockPathTypeStatic(level, BlockPos.containing(pos).mutable());
    }

    private static boolean blocked(ServerLevel level, Entity ignore, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, ignore)).getType() != HitResult.Type.MISS;
    }

    private static float yawTowards(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return (float) (Mth.atan2(d.z, d.x) * (180.0D / Math.PI)) - 90.0F;
    }

    private static void setBase(Entity entity, Attribute attribute, double value) {
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            AttributeInstance instance = living.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(value);
            }
        }
    }

    private static void report(CommandSourceStack source, ServerPlayer player, ServerLevel level,
                               WardenGirlEntity girl, Zombie zombie, Placement placement, int removed) {
        double horizontal = Math.sqrt(
                Math.pow(zombie.getX() - girl.getX(), 2) + Math.pow(zombie.getZ() - girl.getZ(), 2));
        String text = String.format(Locale.ROOT,
                "[T21 즉시 확인 상황 생성]%n"
                        + "  기존 테스트 개체 제거      : %d%n"
                        + "  player UUID               : %s%n"
                        + "  world dimension           : %s%n"
                        + "  difficulty                : %s%n"
                        + "  소환 배치 방향            : %s%n"
                        + "  warden girl UUID          : %s%n"
                        + "  warden girl position      : (%.3f, %.3f, %.3f)%n"
                        + "  owner UUID                : %s%n"
                        + "  power stacks              : %d%n"
                        + "  health / maxHealth        : %.2f / %.2f%n"
                        + "  retreat threshold health  : %.2f%n"
                        + "  retreating                : %s%n"
                        + "  초기 target UUID          : %s%n"
                        + "  zombie UUID               : %s%n"
                        + "  zombie position           : (%.3f, %.3f, %.3f)%n"
                        + "  zombie target UUID        : %s%n"
                        + "  movement speed            : %.4f%n"
                        + "  attack damage             : %.2f%n"
                        + "  zombie health / maxHealth : %.2f / %.2f%n"
                        + "  두 개체 초기 수평 거리    : %.4f%n"
                        + "  초기 3D 거리              : %.4f%n"
                        + "  예상 흐름: 첫 좀비 타격 -> 워든걸 체력 %.1f 이하 -> 후퇴 -> 거리 확보"
                        + " -> ETA 44틱 초과 -> 후퇴 소닉 방출",
                removed,
                player.getUUID(),
                level.dimension().location(),
                level.getDifficulty().getKey(),
                placement.name,
                girl.getUUID(),
                girl.getX(), girl.getY(), girl.getZ(),
                girl.getOwnerUuid().map(java.util.UUID::toString).orElse("없음"),
                girl.getPowerStacks(),
                girl.getHealth(), girl.getMaxHealth(),
                girl.getMaxHealth() * 0.25F,
                girl.isRetreating(),
                girl.getTarget() == null ? "null" : girl.getTarget().getUUID().toString(),
                zombie.getUUID(),
                zombie.getX(), zombie.getY(), zombie.getZ(),
                zombie.getTarget() == null ? "null" : zombie.getTarget().getUUID().toString(),
                zombie.getAttributeValue(Attributes.MOVEMENT_SPEED),
                zombie.getAttributeValue(Attributes.ATTACK_DAMAGE),
                zombie.getHealth(), zombie.getMaxHealth(),
                horizontal,
                girl.distanceTo(zombie),
                girl.getMaxHealth() * 0.25F);
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), true);
    }

    private record Placement(String name, Vec3 dir, Vec3 girl, Vec3 zombie) {
    }
}
