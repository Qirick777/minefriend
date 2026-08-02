package com.wardengirl.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wardengirl.entity.WardenGirlEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 4차 T27 — 운영자 검증 명령. 설계서 4차 Part 3.
 *
 * <h2>이 파일이 하지 않는 것</h2>
 *
 * 이동·회피·피해 로직을 여기에 복제하지 않는다. 명령은 {@link WardenGirlEntity} 의 작은 API 와
 * 바닐라 API 를 부르는 <b>진입점</b>이다. 미래 기능(유격 점프·다이브·벽 넘기·사이드홉·백 회피·
 * 대각선 카운터)을 흉내 내는 코드도 없다 — 각 태스크에서 실제로 구현한다.
 *
 * <p>모든 상태는 개체별 runtime 이며 NBT·SynchedEntityData·패킷·전역 컬렉션 어디에도 남지
 * 않는다(설계서 3.11).
 *
 * <p>등록은 기존 {@code /wardengirl} 루트 아래 {@code test} 한 갈래뿐이다. 권한은 루트가 이미
 * 요구하는 permission level 2 를 그대로 상속한다.
 */
public final class WardenGirlTestCommand {

    private WardenGirlTestCommand() {
    }

    /** {@code test melee_hit} 이 넣는 시험 피해. 한 곳에만 있다. */
    public static final float MELEE_HIT_DAMAGE = 4.0F;

    /** {@code test projectile fire} 화살 속도. 바닐라 활 완전 충전과 같은 값이다. */
    public static final float ARROW_VELOCITY = 3.0F;

    /** 화살 명중 흔들림. 서버 검증용이라 0 에 가깝게 고정한다. */
    public static final float ARROW_INACCURACY = 0.0F;

    /** 화살 기본 피해. 바닐라 {@code Arrow} 기본값을 그대로 쓴다. */
    public static final double ARROW_BASE_DAMAGE = 2.0D;

    /** 플레이어 눈에서 워든걸 쪽으로 이만큼 앞에서 생성한다. 자기 AABB 즉시 충돌을 피한다. */
    private static final double ARROW_SPAWN_OFFSET = 1.0D;

    private static final SuggestionProvider<CommandSourceStack> DODGE_SET =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    List.of("back", "diagonal", "player"), builder);

    private static final SuggestionProvider<CommandSourceStack> DODGE_CLEAR =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    List.of("back", "diagonal", "player", "all"), builder);

    public static ArgumentBuilder<CommandSourceStack, ?> node() {
        return Commands.literal("test")
                .then(Commands.literal("teleport")
                        .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> teleport(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "wardenGirl"),
                                                EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("move")
                        .then(Commands.literal("to_player")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> moveToPlayer(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "wardenGirl"),
                                                        EntityArgument.getPlayer(ctx, "player")))))))
                .then(Commands.literal("target")
                        .then(Commands.literal("set")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .then(Commands.argument("livingEntity", EntityArgument.entity())
                                                .executes(ctx -> targetSet(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "wardenGirl"),
                                                        EntityArgument.getEntity(ctx, "livingEntity"))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .executes(ctx -> targetClear(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "wardenGirl"))))))
                .then(Commands.literal("attack_damage")
                        .then(Commands.literal("set")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D))
                                                .executes(ctx -> attackDamageSet(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "wardenGirl"),
                                                        DoubleArgumentType.getDouble(ctx, "value"))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .executes(ctx -> attackDamageClear(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "wardenGirl"))))))
                .then(Commands.literal("dodge_chance")
                        .then(Commands.literal("set")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests(DODGE_SET)
                                                .then(Commands.argument("percent", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                        .executes(ctx -> dodgeSet(ctx.getSource(),
                                                                EntityArgument.getEntity(ctx, "wardenGirl"),
                                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type"),
                                                                DoubleArgumentType.getDouble(ctx, "percent")))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests(DODGE_CLEAR)
                                                .executes(ctx -> dodgeClear(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "wardenGirl"),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type")))))))
                .then(Commands.literal("projectile")
                        .then(Commands.literal("fire")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> projectileFire(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "wardenGirl"),
                                                        EntityArgument.getPlayer(ctx, "player")))))))
                .then(Commands.literal("melee_hit")
                        .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                .then(Commands.argument("attacker", EntityArgument.entity())
                                        .executes(ctx -> meleeHit(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "wardenGirl"),
                                                EntityArgument.getEntity(ctx, "attacker"))))))
                .then(Commands.literal("movement")
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .executes(ctx -> movementCancel(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "wardenGirl"))))))
                .then(Commands.literal("state")
                        .then(Commands.literal("get")
                                .then(Commands.argument("wardenGirl", EntityArgument.entity())
                                        .executes(ctx -> stateGet(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "wardenGirl"))))));
    }

    // ---- 공통 ------------------------------------------------------------------------------

    /** 기존 {@code asWardenGirl} 과 같은 형식으로 거절한다. */
    @javax.annotation.Nullable
    private static WardenGirlEntity girl(CommandSourceStack source, Entity target) {
        if (target instanceof WardenGirlEntity g) {
            return g;
        }
        source.sendFailure(Component.literal("[test] 대상이 워든걸이 아니다: "
                + target.getType().getDescriptionId() + " (" + target.getName().getString() + ")"));
        return null;
    }

    private static String describe(WardenGirlEntity g) {
        return String.format(Locale.ROOT, "%s id=%d uuid=%s",
                g.getName().getString(), g.getId(), g.getUUID());
    }

    /** T29 — 유격 점프 실행 정보. 계획이 없거나 다른 종류면 none 이다. */
    private static String gapJumpLine(WardenGirlEntity g) {
        com.wardengirl.entity.WardenGirlSpecialMovement.Plan p = g.specialMovement().getPlan();
        if (p == null
                || p.kind() != com.wardengirl.entity.WardenGirlSpecialMovement.Kind.SPRINT_GAP_JUMP) {
            return "none";
        }
        com.wardengirl.entity.WardenGirlGapJump gj = g.gapJump();
        net.minecraft.world.phys.Vec3 v = gj.takeoffVelocity();
        return String.format(Locale.ROOT,
                "gap=%d 예상비행=%dtick 실제공중=%dtick 도약속도=(%.5f, %.5f, %.5f)",
                gj.gapLength(), gj.predictedFlightTicks(), gj.airTicks(),
                v == null ? 0.0D : v.x, v == null ? 0.0D : v.y, v == null ? 0.0D : v.z);
    }

    private static void ok(CommandSourceStack source, String body) {
        source.sendSuccess(() -> Component.literal("[test] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(body).withStyle(ChatFormatting.WHITE)), true);
    }

    // ---- teleport --------------------------------------------------------------------------

    /**
     * 설계서 3.3. 공식 1.20.1 {@code Entity.teleportTo(ServerLevel, x, y, z, Set&lt;RelativeMovement&gt;,
     * yRot, xRot)} 하나를 쓴다 — 같은 차원이면 그 자리에서 옮기고 다른 차원이면 레벨 이동까지
     * 이 API 가 처리한다(바이트코드 확인). 별도 텔레포트 패킷은 만들지 않는다.
     */
    private static int teleport(CommandSourceStack source, Entity target, ServerPlayer player) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        Vec3 before = g.position();
        String beforeDim = g.level().dimension().location().toString();
        if (g.isPassenger()) {
            g.stopRiding();
        }
        g.cancelTestMovement();                 // navigation 정지 + 시험 목적지 해제(T28 취소 연결점)
        g.setDeltaMovement(Vec3.ZERO);
        g.resetFallDistance();
        ServerLevel dest = player.serverLevel();
        boolean moved = g.teleportTo(dest, player.getX(), player.getY(), player.getZ(),
                Set.of(), g.getYRot(), g.getXRot());
        g.setDeltaMovement(Vec3.ZERO);
        g.resetFallDistance();
        if (!moved) {
            source.sendFailure(Component.literal(
                    "[test] teleportTo 가 거부했다 — 대상 차원 " + dest.dimension().location()));
            return 0;
        }
        ok(source, String.format(Locale.ROOT,
                "teleport %s%n         이전 = %s (%.3f, %.3f, %.3f)%n"
                        + "         이후 = %s (%.3f, %.3f, %.3f)%n"
                        + "         fallDistance = %.3f  탑승 = %s  소유자 = %s  스택 = %d%n"
                        + "         target 은 명령이 건드리지 않았다 (현재 %s)",
                describe(g), beforeDim, before.x, before.y, before.z,
                player.serverLevel().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.serverLevel().getEntity(g.getUUID()) instanceof WardenGirlEntity now
                        ? now.fallDistance : g.fallDistance,
                g.isPassenger(), g.getOwnerUuid().map(java.util.UUID::toString).orElse("없음"),
                g.getPowerStacks(),
                g.getTarget() == null ? "없음" : g.getTarget().getUUID().toString()));
        return 1;
    }

    // ---- move to_player --------------------------------------------------------------------

    /** 설계서 3.4 — 명령 <b>실행 순간</b>의 플레이어 발 위치를 고정 목적지로 저장한다. */
    private static int moveToPlayer(CommandSourceStack source, Entity target, ServerPlayer player) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        if (player.level() != g.level()) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[test] 목적지가 다른 차원이다 — 워든걸 %s / 플레이어 %s",
                    g.level().dimension().location(), player.level().dimension().location())));
            return 0;
        }
        Vec3 dest = player.position();
        g.setTestDestination(dest);
        ok(source, String.format(Locale.ROOT,
                "move to_player %s%n         고정 목적지 = (%.3f, %.3f, %.3f)%n"
                        + "         현재 거리 = %.3f  속도 배율 = %.2f%n"
                        + "         이후 플레이어가 움직여도 이 좌표는 바뀌지 않는다",
                describe(g), dest.x, dest.y, dest.z, g.position().distanceTo(dest),
                com.wardengirl.entity.WardenGirlTestMoveGoal.SPEED));
        return 1;
    }

    // ---- target ----------------------------------------------------------------------------

    /**
     * 설계서 3.5 — 기존 {@code isValidCombatTarget}(소유자·같은 소유자 워든걸·자기 자신·사망·
     * 다른 차원·창조·관전자·{@code canAttack}) 를 그대로 통과해야 한다. 우회 경로는 없다.
     */
    private static int targetSet(CommandSourceStack source, Entity target, Entity wanted) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        if (!(wanted instanceof LivingEntity living)) {
            source.sendFailure(Component.literal("[test] 대상이 LivingEntity 가 아니다: "
                    + wanted.getType().getDescriptionId()));
            return 0;
        }
        if (!g.isValidCombatTarget(living)) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[test] 관계 필터가 거절했다 — %s (%s). 소유자·같은 소유자 워든걸·자기 자신·"
                            + "사망·제거·다른 차원·창조·관전자·canAttack 거절 중 하나다.",
                    living.getName().getString(), living.getUUID())));
            return 0;
        }
        g.setTarget(living);
        ok(source, String.format(Locale.ROOT, "target set %s%n         대상 = %s (%s) uuid=%s",
                describe(g), living.getName().getString(),
                living.getType().getDescriptionId(), living.getUUID()));
        return 1;
    }

    private static int targetClear(CommandSourceStack source, Entity target) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        LivingEntity before = g.getTarget();
        g.setTarget(null);
        ok(source, String.format(Locale.ROOT, "target clear %s%n         이전 = %s → 현재 = 없음",
                describe(g), before == null ? "없음" : before.getUUID().toString()));
        return 1;
    }

    // ---- attack_damage ---------------------------------------------------------------------

    private static int attackDamageSet(CommandSourceStack source, Entity target, double value) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        if (!Double.isFinite(value) || value < 0.0D) {
            source.sendFailure(Component.literal(
                    "[test] value 는 0 이상 유한 double 이어야 한다: " + value));
            return 0;
        }
        double live = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        g.setTestAttackDamageOverride(value);
        ok(source, String.format(Locale.ROOT,
                "attack_damage set %s%n         override = %.4f%n"
                        + "         살아 있는 ATTACK_DAMAGE = %.4f (변경하지 않았다)%n"
                        + "         PowerStacks = %d (변경하지 않았다)%n"
                        + "         유효 근접 피해 = %.4f  · 소닉 피해 = %.4f (영향 없음)",
                describe(g), value, live, g.getPowerStacks(),
                g.effectiveMeleeDamage(), g.getSonicDamage()));
        return 1;
    }

    private static int attackDamageClear(CommandSourceStack source, Entity target) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        Double before = g.getTestAttackDamageOverride();
        g.clearTestAttackDamageOverride();
        ok(source, String.format(Locale.ROOT,
                "attack_damage clear %s%n         이전 override = %s%n"
                        + "         현재 유효 근접 피해 = %.4f (살아 있는 ATTACK_DAMAGE)%n"
                        + "         PowerStacks = %d",
                describe(g), before == null ? "없음" : String.format(Locale.ROOT, "%.4f", before),
                g.effectiveMeleeDamage(), g.getPowerStacks()));
        return 1;
    }

    // ---- dodge_chance ----------------------------------------------------------------------

    private static int dodgeSet(CommandSourceStack source, Entity target, String rawType,
                                double percent) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        WardenGirlEntity.DodgeType type = WardenGirlEntity.DodgeType.parse(rawType);
        if (type == null) {
            source.sendFailure(Component.literal(
                    "[test] 알 수 없는 분류: " + rawType + " (back|diagonal|player)"));
            return 0;
        }
        if (!Double.isFinite(percent) || percent < 0.0D || percent > 100.0D) {
            source.sendFailure(Component.literal("[test] percent 는 0~100 유한 수여야 한다: " + percent));
            return 0;
        }
        g.setTestDodgeChanceOverride(type, percent);
        ok(source, String.format(Locale.ROOT,
                "dodge_chance set %s%n         %s = %.2f%%%n"
                        + "         T27 에서는 값 저장·출력만 바뀐다. 실제 피격 결과는 T35~T37 에서 연결된다.",
                describe(g), type.name().toLowerCase(Locale.ROOT), percent));
        return 1;
    }

    private static int dodgeClear(CommandSourceStack source, Entity target, String rawType) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        if ("all".equalsIgnoreCase(rawType)) {
            g.clearAllTestDodgeChanceOverrides();
            ok(source, String.format(Locale.ROOT,
                    "dodge_chance clear %s%n         back·diagonal·player 전부 해제", describe(g)));
            return 1;
        }
        WardenGirlEntity.DodgeType type = WardenGirlEntity.DodgeType.parse(rawType);
        if (type == null) {
            source.sendFailure(Component.literal(
                    "[test] 알 수 없는 분류: " + rawType + " (back|diagonal|player|all)"));
            return 0;
        }
        Double before = g.getTestDodgeChanceOverride(type);
        g.clearTestDodgeChanceOverride(type);
        ok(source, String.format(Locale.ROOT, "dodge_chance clear %s%n         %s 이전 = %s → 해제",
                describe(g), type.name().toLowerCase(Locale.ROOT),
                before == null ? "없음" : String.format(Locale.ROOT, "%.2f%%", before)));
        return 1;
    }

    // ---- projectile fire -------------------------------------------------------------------

    /**
     * 설계서 3.8 — 소유자 없는 바닐라 화살 <b>한 발</b>.
     *
     * <p>{@code new Arrow(Level, x, y, z)} 는 owner 를 설정하지 않는 생성자다(공식 매핑 확인).
     * {@code setOwner} 를 부르지 않으므로 워든걸의 소유 관계 면역에 걸리지 않는다. 방향은
     * 플레이어 눈에서 워든걸 중심으로 향하는 실제 벡터이며, 발사점을 그 방향으로
     * {@value #ARROW_SPAWN_OFFSET}블록 밀어 플레이어 자신의 AABB 에 즉시 충돌하지 않게 한다.
     */
    private static int projectileFire(CommandSourceStack source, Entity target, ServerPlayer player) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        if (player.level() != g.level()) {
            source.sendFailure(Component.literal("[test] 워든걸과 플레이어가 다른 차원이다."));
            return 0;
        }
        if (!(g.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("[test] 서버 레벨이 아니다."));
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aim = g.position().add(0.0D, g.getBbHeight() * 0.5D, 0.0D);
        Vec3 dir = aim.subtract(eye);
        if (dir.lengthSqr() < 1.0E-6D) {
            source.sendFailure(Component.literal("[test] 방향을 계산할 수 없다 — 두 위치가 같다."));
            return 0;
        }
        Vec3 unit = dir.normalize();
        Vec3 spawn = eye.add(unit.scale(ARROW_SPAWN_OFFSET));
        Arrow arrow = new Arrow(level, spawn.x, spawn.y, spawn.z);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(ARROW_BASE_DAMAGE);
        arrow.shoot(unit.x, unit.y, unit.z, ARROW_VELOCITY, ARROW_INACCURACY);
        if (!level.addFreshEntity(arrow)) {
            source.sendFailure(Component.literal("[test] addFreshEntity 가 화살을 거부했다."));
            return 0;
        }
        ok(source, String.format(Locale.ROOT,
                "projectile fire %s%n         발사점 = (%.3f, %.3f, %.3f)  조준점 = (%.3f, %.3f, %.3f)%n"
                        + "         방향 = (%.4f, %.4f, %.4f)  속도 = %.2f  inaccuracy = %.2f%n"
                        + "         화살 uuid = %s  owner = %s  baseDamage = %.2f",
                describe(g), spawn.x, spawn.y, spawn.z, aim.x, aim.y, aim.z,
                unit.x, unit.y, unit.z, ARROW_VELOCITY, ARROW_INACCURACY,
                arrow.getUUID(), arrow.getOwner() == null ? "없음" : arrow.getOwner().getUUID().toString(),
                arrow.getBaseDamage()));
        return 1;
    }

    // ---- melee_hit -------------------------------------------------------------------------

    /**
     * 설계서 3.9 — 지정 공격자를 출처로 하는 실제 근접형 {@code DamageSource} 한 건.
     *
     * <p>공격자가 {@code Player} 면 {@code damageSources().playerAttack(player)}, 그 외
     * {@code LivingEntity} 면 {@code damageSources().mobAttack(living)} 이다(공식 매핑 확인).
     * 투사체·마법·폭발로 위장하지 않는다. 결과는 바닐라 {@code hurt} 가 정하며 명령이 결과를
     * 지어내지 않는다 — 방어구·저항·무적 틱 때문에 실제 감소량이 다르면 그대로 보고한다.
     */
    private static int meleeHit(CommandSourceStack source, Entity target, Entity attackerEntity) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        if (!(attackerEntity instanceof LivingEntity attacker)) {
            source.sendFailure(Component.literal("[test] 공격자가 LivingEntity 가 아니다: "
                    + attackerEntity.getType().getDescriptionId()));
            return 0;
        }
        DamageSource src = attacker instanceof Player p
                ? g.damageSources().playerAttack(p)
                : g.damageSources().mobAttack(attacker);
        float before = g.getHealth();
        int invulnBefore = g.invulnerableTime;
        boolean applied = g.hurt(src, MELEE_HIT_DAMAGE);
        float after = g.getHealth();
        ok(source, String.format(Locale.ROOT,
                "melee_hit %s%n         공격자 = %s (%s) uuid=%s%n"
                        + "         DamageSource = %s  요청 피해 = %.2f%n"
                        + "         hurt 반환 = %s  체력 %.4f → %.4f (감소 %.4f)%n"
                        + "         invulnerableTime 이전 = %d, 이후 = %d%n"
                        + "         마지막 공격자 = %s",
                describe(g), attacker.getName().getString(),
                attacker.getType().getDescriptionId(), attacker.getUUID(),
                src.getMsgId(), MELEE_HIT_DAMAGE, applied, before, after, before - after,
                invulnBefore, g.invulnerableTime,
                g.getLastHurtByMob() == null ? "없음" : g.getLastHurtByMob().getUUID().toString()));
        return 1;
    }

    // ---- movement cancel -------------------------------------------------------------------

    private static int movementCancel(CommandSourceStack source, Entity target) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        Vec3 before = g.getTestDestination();
        g.cancelTestMovement();
        ok(source, String.format(Locale.ROOT,
                "movement cancel %s%n         이전 목적지 = %s → 해제%n"
                        + "         navigation isDone = %s%n"
                        + "         소유자·target·PowerStacks 는 건드리지 않았다",
                describe(g),
                before == null ? "없음" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                        before.x, before.y, before.z),
                g.getNavigation().isDone()));
        return 1;
    }

    // ---- state get -------------------------------------------------------------------------

    /**
     * 설계서 3.10 — <b>지금 실제로 존재하는 상태만</b> 낸다. T28 이후에 생길 항목은 값을
     * 지어내지 않고 고정 문자열로 자리만 표시한다.
     */
    private static int stateGet(CommandSourceStack source, Entity target) {
        WardenGirlEntity g = girl(source, target);
        if (g == null) {
            return 0;
        }
        Vec3 v = g.getDeltaMovement();
        Vec3 dest = g.getTestDestination();
        Path path = g.getNavigation().getPath();
        LivingEntity t = g.getTarget();
        Double back = g.getTestDodgeChanceOverride(WardenGirlEntity.DodgeType.BACK);
        Double diag = g.getTestDodgeChanceOverride(WardenGirlEntity.DodgeType.DIAGONAL);
        Double pl = g.getTestDodgeChanceOverride(WardenGirlEntity.DodgeType.PLAYER);
        Double dmg = g.getTestAttackDamageOverride();
        com.wardengirl.entity.WardenGirlSpecialMovement sm = g.specialMovement();
        com.wardengirl.entity.WardenGirlSpecialMovement.Plan plan = sm.getPlan();
        com.wardengirl.entity.WardenGirlSpecialMovement.Failure fail = sm.getLastFailure();
        ok(source, String.format(Locale.ROOT,
                "state %s%n"
                        + "         owner uuid              = %s%n"
                        + "         target                  = %s%n"
                        + "         test destination        = %s%n"
                        + "         special movement        = %s (age %d tick)%n"
                        + "         plan                    = %s%n"
                        + "         plan origin             = %s%n"
                        + "         plan landing/end        = %s%n"
                        + "         original purpose        = %s%n"
                        + "         original goal           = %s%n"
                        + "         related entity          = %s%n"
                        + "         wall block              = %s%n"
                        + "         tracked projectile      = %s%n"
                        + "         plan start gameTime     = %s%n"
                        + "         expected end tick       = %s%n"
                        + "         recent failed connection= %s%n"
                        + "         failure reason          = %s%n"
                        + "         failure gameTime        = %s%n"
                        + "         failure remaining ticks = %d%n"
                        + "         player aim evade        = none%n"
                        + "         onGround                = %s%n"
                        + "         horizontalCollision     = %s%n"
                        + "         fallDistance            = %.4f%n"
                        + "         deltaMovement           = (%.5f, %.5f, %.5f)%n"
                        + "         navigation path         = %s%n"
                        + "         navigation isDone       = %s%n"
                        + "         attack damage override  = %s%n"
                        + "         effective melee damage  = %.4f%n"
                        + "         back dodge override     = %s%n"
                        + "         diagonal dodge override = %s%n"
                        + "         player dodge override   = %s",
                describe(g),
                g.getOwnerUuid().map(java.util.UUID::toString).orElse("없음 (야생)"),
                t == null ? "없음" : String.format(Locale.ROOT, "%s uuid=%s",
                        t.getType().getDescriptionId(), t.getUUID()),
                dest == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                        dest.x, dest.y, dest.z),
                sm.getState(), sm.getStateAge(),
                plan == null ? "none" : plan.kind().toString(),
                plan == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                        plan.origin().x, plan.origin().y, plan.origin().z),
                plan == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                        plan.landing().x, plan.landing().y, plan.landing().z),
                plan == null ? "none" : plan.purpose().toString(),
                plan == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                        plan.goal().x, plan.goal().y, plan.goal().z),
                plan == null || plan.relatedEntity() == null ? "none" : plan.relatedEntity().toString(),
                plan == null || plan.wall() == null ? "none" : plan.wall().toString(),
                plan == null || plan.projectile() == null ? "none" : plan.projectile().toString(),
                plan == null ? "none" : Long.toString(plan.startGameTime()),
                plan == null || plan.expectedEndTick() == null ? "none"
                        : plan.expectedEndTick().toString(),
                fail == null ? "none" : String.format(Locale.ROOT, "%s origin=%s landing=%s",
                        fail.key().kind(), fail.key().originSupport(), fail.key().landingSupport()),
                fail == null ? "none" : fail.reason().toString(),
                fail == null ? "none" : Long.toString(fail.gameTime()),
                sm.failureRemainingTicks(),
                g.onGround(), g.horizontalCollision, g.fallDistance, v.x, v.y, v.z,
                path == null ? "없음" : String.format(Locale.ROOT, "nodes=%d next=%d canReach=%s",
                        path.getNodeCount(), path.getNextNodeIndex(), path.canReach()),
                g.getNavigation().isDone(),
                dmg == null ? "none" : String.format(Locale.ROOT, "%.4f", dmg),
                g.effectiveMeleeDamage(),
                back == null ? "none" : String.format(Locale.ROOT, "%.2f%%", back),
                diag == null ? "none" : String.format(Locale.ROOT, "%.2f%%", diag),
                pl == null ? "none" : String.format(Locale.ROOT, "%.2f%%", pl)));
        return 1;
    }
}
