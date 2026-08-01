package com.wardengirl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * T23 — 나무문 길찾기·개폐. 설계서 4.5.
 *
 * <h2>Goal 이 아니라 보조 controller 다</h2>
 *
 * 목적지도 target 도 정하지 않고 navigation 경로를 바꾸지도 않는다. 기존 Goal 이 만든
 * <b>현재 경로</b>를 읽어 그 경로에 필요한 문만 열고, 통과한 뒤 안전하면 닫는다. 그래서
 * {@code Goal.Flag} 를 하나도 잡지 않으며 추종·전투·후퇴·소닉 어느 것도 끊지 않는다.
 *
 * <h2>바닐라 {@code OpenDoorGoal} 을 쓰지 않은 이유 (바이트코드 확인)</h2>
 *
 * <ul>
 *   <li>{@code DoorInteractGoal.canUse()} 는 {@code mob.horizontalCollision} 을 요구한다 —
 *       문에 <b>부딪혀야</b> 시작한다. 설계서는 경로에 문이 있으면 1.5블록 안에서 열라고 한다.
 *   <li>{@code OpenDoorGoal} 의 닫기는 {@code start()} 부터 세는 {@code forgetTime} 이다.
 *       설계서 4.5.5 는 <b>완전 통과 시점</b>부터 20틱을 요구한다.
 *   <li>안전 조건(소유자·같은 소유자 워든걸·{@code LivingEntity} 겹침·경로 재통과)과
 *       10틱 재시도·100틱 포기, 연속 두 문 기록이 바닐라에 없다.
 * </ul>
 *
 * 판정 <b>방식</b>은 바닐라를 그대로 따른다 — 앞으로 두 노드({@code getNextNodeIndex() + 2}),
 * 노드 위 한 칸을 문 위치로 보는 규칙, 통과 판정의 내적 부호가 모두 {@code DoorInteractGoal}
 * 의 것이다. 문 개폐 자체도 {@link DoorBlock#setOpen} 하나만 쓴다 — 소리·game event·
 * block state 동기화가 전부 바닐라 경로로 나간다.
 */
public final class WardenGirlDoorHelper {

    /** 설계서 4.5.4 — 문을 여는 최대 거리. */
    public static final double OPEN_DISTANCE = 1.5D;
    /** 설계서 4.5.5 — 완전 통과 뒤 닫기까지. */
    public static final int CLOSE_DELAY_TICKS = 20;
    /** 설계서 4.5.5 — 안전하지 않을 때 재검사 간격. */
    public static final int CLOSE_RETRY_TICKS = 10;
    /** 설계서 4.5.5 — 재시도 총 한계. 이 시간을 넘기면 열린 채로 둔다. */
    public static final int CLOSE_GIVE_UP_TICKS = 100;
    /** 닫기 안전 검사에서 소유자·같은 소유자 워든걸을 보는 반경. */
    public static final double CLEAR_RADIUS = 1.5D;
    /** 기록 상한. 연속된 문을 몇 개 지나도 앞 문을 잊지 않을 만큼만 둔다. */
    public static final int MAX_RECORDS = 8;

    /**
     * 경로가 문에서 벗어난 것을 <b>포기</b>로 확정하기까지의 안정 시간(틱).
     *
     * <p>{@link WardenGirlApproach#REPATH_MIN} + {@link WardenGirlApproach#REPATH_SPREAD} − 1 =
     * 10 이다. 즉 현재 전투 추격이 쓰는 재경로 주기의 <b>최댓값</b>이며, 새 수치를 만들지 않았다.
     * 경로가 한 틱 흔들렸다는 이유로 바로 포기하지 않게 하는 것이 목적이다.
     */
    public static final int ABANDON_STABLE_TICKS =
            WardenGirlApproach.REPATH_MIN + WardenGirlApproach.REPATH_SPREAD - 1;

    /** 워든걸이 <b>스스로 연</b> 문 하나. 서버 runtime 전용이며 NBT 도 패킷도 없다. */
    private static final class DoorRecord {
        final BlockPos pos;                 // 정규화된 하단
        final int openTick;
        final double dirX;                  // 열 당시 문 중심 − 워든걸 위치 (수평)
        final double dirZ;
        boolean passed;
        int passedTick;
        int nextCloseTick;
        int giveUpTick;
        int abandonSeenTick = Integer.MIN_VALUE;

        DoorRecord(BlockPos pos, int tick, double dirX, double dirZ) {
            this.pos = pos;
            this.openTick = tick;
            this.dirX = dirX;
            this.dirZ = dirZ;
        }
    }

    private final WardenGirlEntity mob;
    private final List<DoorRecord> records = new ArrayList<>(MAX_RECORDS);

    WardenGirlDoorHelper(WardenGirlEntity mob) {
        this.mob = mob;
    }

    /** 계측·보고용. 지금 추적 중인 "자신이 연 문" 수. */
    public int recordCount() {
        return this.records.size();
    }

    /**
     * navigation 의 문 설정을 길들여짐에 맞춘다. <b>값이 바뀔 때만</b> 쓴다 — 매 틱 같은 값을
     * 다시 넣지 않고 navigation 객체를 새로 만들지도 않는다.
     *
     * <p>{@code GroundPathNavigation} 이 아니면 손대지 않는다. 억지로 캐스팅하거나 교체하지
     * 않는다는 설계서 5장 요구다.
     */
    void syncNavigation() {
        PathNavigation nav = this.mob.getNavigation();
        if (!(nav instanceof GroundPathNavigation ground)) {
            return;
        }
        boolean want = this.mob.hasOwner();
        // 닫힌 나무문이 WALKABLE_DOOR 가 되려면 canOpenDoors 와 canPassDoors 가 <b>둘 다</b>
        // 참이어야 한다(WalkNodeEvaluator.evaluateBlockPathType 바이트코드). 철문
        // (DOOR_IRON_CLOSED) 은 어느 조합에서도 변환되지 않아 계속 막힌 노드다.
        if (ground.canOpenDoors() != want) {
            ground.setCanOpenDoors(want);
        }
        if (ground.canPassDoors() != want) {
            ground.setCanPassDoors(want);
        }
    }

    /** 매 서버틱 한 번. {@code customServerAiStep()} 에서 부른다 — 경로가 이미 최신이다. */
    void tick() {
        int now = this.mob.tickCount;
        if (this.mob.hasOwner()) {
            openNeededDoor(now);
        }
        // 기록 처리는 야생이 되더라도 계속한다 — 이미 열어 둔 문은 닫아 줘야 한다.
        updateRecords(now);
    }

    // ---- 열기 -------------------------------------------------------------------------------

    /**
     * 설계서 4.5.4 — <b>현재 경로의 앞으로 두 노드</b>만 본다. 주변 반경 탐색은 없다.
     *
     * <p>노드 범위 {@code [nextNodeIndex, nextNodeIndex + 2)} 와 "노드 위 한 칸이 문" 규칙은
     * 바닐라 {@code DoorInteractGoal.canUse()} 와 같다. 이미 지나간 노드는 범위에 들어오지
     * 않으므로 다시 열 일이 없다.
     */
    private void openNeededDoor(int now) {
        PathNavigation nav = this.mob.getNavigation();
        if (!(nav instanceof GroundPathNavigation ground) || !ground.canOpenDoors()) {
            return;
        }
        Path path = ground.getPath();
        if (path == null || path.isDone()) {
            return;                                 // 경로가 없으면 문을 열지 않는다
        }
        int from = path.getNextNodeIndex();
        int to = Math.min(from + 2, path.getNodeCount());
        Level level = this.mob.level();
        for (int i = from; i < to; i++) {
            Node node = path.getNode(i);
            // 노드 자체와 위·아래 한 칸. 문 상단이 잡히면 하단으로 정규화한다.
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos at = new BlockPos(node.x, node.y + dy, node.z);
                BlockPos door = lowerDoorPos(level, at);
                if (door == null) {
                    continue;
                }
                BlockState state = level.getBlockState(door);
                if (!(state.getBlock() instanceof DoorBlock block) || block.isOpen(state)) {
                    continue;                       // 이미 열려 있으면 그대로 통과한다
                }
                if (!DoorBlock.isWoodenDoor(state)) {
                    continue;                       // 철문은 여기서 끝이다
                }
                if (distanceToDoorway(door) > OPEN_DISTANCE) {
                    continue;
                }
                if (find(door) != null) {
                    continue;                       // 같은 문을 두 번 기록하지 않는다
                }
                double dx = door.getX() + 0.5D - this.mob.getX();
                double dz = door.getZ() + 0.5D - this.mob.getZ();
                block.setOpen(this.mob, level, state, door, true);
                trim();
                this.records.add(new DoorRecord(door, now, dx, dz));
                return;                             // 한 틱에 한 문이면 충분하다
            }
        }
    }

    // ---- 통과·포기·닫기 ---------------------------------------------------------------------

    private void updateRecords(int now) {
        this.records.removeIf(r -> !stillOurDoor(r));
        for (DoorRecord r : this.records) {
            if (!r.passed) {
                if (checkPassed(r, now)) {
                    continue;
                }
                checkAbandoned(r, now);
                continue;
            }
            if (now - r.nextCloseTick < 0) {
                continue;
            }
            if (now - r.giveUpTick >= 0) {
                r.nextCloseTick = Integer.MAX_VALUE; // 열린 채로 둔다. 아래에서 기록이 빠진다
                continue;
            }
            if (safeToClose(r)) {
                Level level = this.mob.level();
                BlockState state = level.getBlockState(r.pos);
                if (state.getBlock() instanceof DoorBlock block && block.isOpen(state)) {
                    block.setOpen(this.mob, level, state, r.pos, false);
                }
                r.nextCloseTick = Integer.MAX_VALUE;
            } else {
                r.nextCloseTick = now + CLOSE_RETRY_TICKS;
            }
        }
        this.records.removeIf(r -> r.nextCloseTick == Integer.MAX_VALUE);
    }

    /**
     * 설계서 4.5.5 통과 판정. 바닐라 {@code DoorInteractGoal.tick()} 의 내적 부호에
     * <b>AABB 비중첩</b>을 더한 것이다 — 부호만 보면 몸이 문칸에 걸친 상태에서도 통과로 잡힌다.
     */
    private boolean checkPassed(DoorRecord r, int now) {
        double dx = r.pos.getX() + 0.5D - this.mob.getX();
        double dz = r.pos.getZ() + 0.5D - this.mob.getZ();
        if (r.dirX * dx + r.dirZ * dz >= 0.0D) {
            return false;                           // 아직 문 중심선을 넘지 않았다
        }
        if (this.mob.getBoundingBox().intersects(doorway(r.pos))) {
            return false;                           // 몸이 아직 문칸에 있다
        }
        markPassed(r, now);
        return true;
    }

    private void markPassed(DoorRecord r, int now) {
        r.passed = true;
        r.passedTick = now;
        r.nextCloseTick = now + CLOSE_DELAY_TICKS;
        r.giveUpTick = now + CLOSE_DELAY_TICKS + CLOSE_GIVE_UP_TICKS;
    }

    /**
     * 설계서 4.5 — 열어 놓고 통과하지 않은 문을 무한히 들고 있지 않는다. 세 조건이
     * {@link #ABANDON_STABLE_TICKS} 동안 <b>계속</b> 참이어야 포기로 확정한다.
     */
    private void checkAbandoned(DoorRecord r, int now) {
        boolean gone = !pathGoesThrough(r.pos)
                && !this.mob.getBoundingBox().intersects(doorway(r.pos))
                && distanceToDoorway(r.pos) > OPEN_DISTANCE;
        if (!gone) {
            r.abandonSeenTick = Integer.MIN_VALUE;
            return;
        }
        if (r.abandonSeenTick == Integer.MIN_VALUE) {
            r.abandonSeenTick = now;
            return;
        }
        if (now - r.abandonSeenTick >= ABANDON_STABLE_TICKS) {
            markPassed(r, now);                     // 통과한 문과 같은 안전 닫기 절차로 넘긴다
        }
    }

    /** 설계서 4.5.5 의 안전 조건 전부. 하나라도 걸리면 이번에는 닫지 않는다. */
    private boolean safeToClose(DoorRecord r) {
        AABB column = doorway(r.pos);
        Level level = this.mob.level();
        Player owner = this.mob.serverOwner();
        if (owner != null && owner.level() == level && distanceToDoorway(r.pos, owner) <= CLEAR_RADIUS) {
            return false;
        }
        AABB near = column.inflate(CLEAR_RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, near, x -> x != this.mob)) {
            if (e.getBoundingBox().intersects(column)) {
                return false;                       // 문칸에 몸이 걸쳐 있다
            }
            if (e instanceof WardenGirlEntity other
                    && this.mob.getOwnerUuid().isPresent()
                    && this.mob.getOwnerUuid().equals(other.getOwnerUuid())
                    && distanceToDoorway(r.pos, other) <= CLEAR_RADIUS) {
                return false;                       // 같은 소유자의 워든걸이 지나는 중이다
            }
        }
        return !pathGoesThrough(r.pos);
    }

    /** 기록이 아직 유효한가. 블록이 사라졌거나·나무문이 아니거나·이미 닫혔으면 버린다. */
    private boolean stillOurDoor(DoorRecord r) {
        BlockState state = this.mob.level().getBlockState(r.pos);
        return state.getBlock() instanceof DoorBlock block
                && DoorBlock.isWoodenDoor(state)
                && block.isOpen(state);
    }

    // ---- 공통 ------------------------------------------------------------------------------

    /** 현재 경로의 <b>앞으로 갈</b> 노드가 이 문을 다시 지나는가. 지나간 노드는 보지 않는다. */
    private boolean pathGoesThrough(BlockPos door) {
        PathNavigation nav = this.mob.getNavigation();
        Path path = nav.getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            if (node.x == door.getX() && node.z == door.getZ()
                    && Math.abs(node.y - door.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** 문이 차지하는 1×2 칸. 문이 열려 collision 이 비어도 이 공간은 고정이다. */
    private static AABB doorway(BlockPos lower) {
        return new AABB(lower.getX(), lower.getY(), lower.getZ(),
                lower.getX() + 1, lower.getY() + 2, lower.getZ() + 1);
    }

    private double distanceToDoorway(BlockPos lower) {
        return distanceToDoorway(lower, this.mob);
    }

    /**
     * 설계서 4.5.4 의 거리. <b>엔티티 AABB 와 1×2 문칸 AABB 사이의 수평 최단거리</b>다.
     *
     * <p>문 하단 중심과 발 위치의 3차원 거리를 쓰면 문 상단·하단 때문에 같은 자리가
     * 열리기도 안 열리기도 한다. 바닐라 {@code DoorInteractGoal} 은
     * {@code distanceToSqr(doorPos.getX(), mob.getY(), doorPos.getZ()) <= 2.25} 로 블록
     * <b>모서리</b>까지의 거리를 쓰는데, 그 값은 축에 따라 최대 1블록 어긋난다. 여기서는
     * 양쪽 상자 사이의 실제 간격을 쓴다.
     */
    private static double distanceToDoorway(BlockPos lower, net.minecraft.world.entity.Entity e) {
        AABB column = doorway(lower);
        AABB box = e.getBoundingBox();
        double dx = Math.max(0.0D, Math.max(column.minX - box.maxX, box.minX - column.maxX));
        double dz = Math.max(0.0D, Math.max(column.minZ - box.maxZ, box.minZ - column.maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 문 상단이면 하단으로 내린다. 문이 아니면 {@code null}. */
    @Nullable
    private static BlockPos lowerDoorPos(Level level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return null;
        }
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? at.below() : at;
    }

    @Nullable
    private DoorRecord find(BlockPos pos) {
        for (DoorRecord r : this.records) {
            if (r.pos.equals(pos)) {
                return r;
            }
        }
        return null;
    }

    /** 상한을 넘으면 가장 오래된 기록부터 버린다. 무효한 기록은 이미 제거된 뒤다. */
    private void trim() {
        while (this.records.size() >= MAX_RECORDS) {
            this.records.remove(0);
        }
    }
}
