package com.wardengirl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * T28 — 특수 이동의 <b>공통 안전 판정</b>. 4차 설계서 2.5.
 *
 * <h2>이 클래스가 하지 않는 것</h2>
 *
 * 궤적을 만들지 않는다. 목적지를 고르지 않는다. 상태도 계획도 들지 않는다. 호출자가 준
 * 위치 하나 또는 위치 표본 목록을 <b>검사만</b> 하는 정적 helper 다. 범용 파쿠르 엔진이
 * 아니며 워든걸 한 개체에 필요한 검사만 담는다.
 *
 * <h2>위험 지형 판정은 T20 과 같은 규칙이다</h2>
 *
 * {@link WardenGirlRetreatGoal#safeSpot} 이 쓰던 두 가지 —
 * {@code WalkNodeEvaluator.getBlockPathTypeStatic(...) == WALKABLE} 과
 * {@code CampfireBlock.isLitCampfire} — 를 {@link #safeFloorBlock} 하나로 옮겼고 후퇴 Goal 도
 * 이제 이 메서드를 부른다. 블록 목록을 두 곳에 적지 않기 위해서다. 판정 의미는 바꾸지 않았다.
 */
public final class WardenGirlMovementSafety {

    private WardenGirlMovementSafety() {
    }

    /**
     * 궤적 표본 사이의 최대 간격(블록). 워든걸 폭(0.6)보다 작아야 표본 사이로 블록을 건너뛰지
     * 않는다. 호출자가 이보다 성기게 표본을 주면 {@link #sweepClear} 가 거절한다.
     */
    public static final double MAX_SAMPLE_STEP = 0.45D;

    /** 진행이 "실제로 개선됐다"고 인정하는 최소 거리(블록). 부동소수점 잡음을 넘기 위한 값이다. */
    public static final double MIN_PROGRESS_GAIN = 0.25D;

    /** 착지 판정에서 지지면을 찾을 때 발밑으로 내려다보는 깊이. */
    private static final double FLOOR_PROBE_DEPTH = 0.51D;

    /**
     * 착지 후보 한 곳이 안전한가. 4차 설계서 2.5 의 3·4·5·6·8 항목이다.
     *
     * <p>목적지 AABB 는 <b>현재 bounding box 를 평행 이동</b>해 만든다 — 모델 크기나 별도
     * 추정값을 쓰지 않는다.
     *
     * @param feet 착지 지점의 <b>발</b> 좌표
     */
    public static boolean safeLanding(WardenGirlEntity mob, Vec3 feet) {
        Level level = mob.level();
        AABB box = destinationBox(mob, feet);
        if (!chunksLoaded(level, box)) {
            return false;                           // 2.5.1 — 검사가 청크를 새로 로드하지 않는다
        }
        if (!level.noCollision(mob, box)) {
            return false;                           // 2.5.3·2.5.4 — 몸과 머리 공간
        }
        if (!hasSupport(mob, feet, box)) {
            return false;                           // 2.5.5 — 실제 지지 collision shape
        }
        if (!safeFloorBlock(level, BlockPos.containing(feet))) {
            return false;                           // 2.5.8 — 위험 지형
        }
        return !occupied(mob, box);                 // 2.5.6 — 다른 LivingEntity 점유
    }

    /** 현재 bounding box 를 그 자리로 평행 이동한 목적지 AABB. */
    public static AABB destinationBox(WardenGirlEntity mob, Vec3 feet) {
        return mob.getBoundingBox().move(feet.subtract(mob.position()));
    }

    /**
     * 착지 발밑에 실제로 설 수 있는 collision shape 가 있는가.
     *
     * <p>{@code BlockState.isAir()} 같은 블록 종류 검사가 아니라 <b>충돌 모양</b>을 본다 —
     * 바닥 AABB 를 아주 얇게 만들어 {@code noCollision} 이 거짓이 되는지 확인한다. 그래서
     * 반 블록·계단·닫힌 뚜껑처럼 발 아래에 실제로 닿는 면만 지지면으로 인정하고, 공기·물만
     * 있는 칸·통과 가능한 풀은 인정하지 않는다.
     */
    public static boolean hasSupport(WardenGirlEntity mob, Vec3 feet, AABB box) {
        AABB under = new AABB(box.minX, feet.y - FLOOR_PROBE_DEPTH, box.minZ,
                box.maxX, feet.y - 0.01D, box.maxZ);
        return !mob.level().noCollision(mob, under);
    }

    /**
     * 이 칸을 밟아도 되는가. <b>T20 후퇴 안전 지점이 쓰던 판정 그대로</b>다.
     *
     * <p>{@code getBlockPathTypeStatic} 한 번이 용암·불·soul fire·magma 계열
     * ({@code DAMAGE_FIRE}), 선인장·달콤한 열매({@code DAMAGE_OTHER}), 가루눈
     * ({@code POWDER_SNOW}·{@code DANGER_POWDER_SNOW}), 인접 불·용암·물·절벽까지 한꺼번에
     * 분류한다(바이트코드 확인). 켜진 모닥불만 바닐라가 표시하지 않으므로 따로 본다.
     */
    public static boolean safeFloorBlock(Level level, BlockPos feetPos) {
        if (WalkNodeEvaluator.getBlockPathTypeStatic(level, feetPos.mutable())
                != BlockPathTypes.WALKABLE) {
            return false;
        }
        BlockState here = level.getBlockState(feetPos);
        BlockState below = level.getBlockState(feetPos.below());
        return !CampfireBlock.isLitCampfire(here) && !CampfireBlock.isLitCampfire(below);
    }

    /** 목적지 AABB 와 겹치는 다른 {@code LivingEntity} 가 있는가. 중심 거리가 아니라 AABB 교차다. */
    public static boolean occupied(WardenGirlEntity mob, AABB box) {
        List<LivingEntity> found = mob.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != mob && e.isAlive() && !e.isRemoved());
        for (LivingEntity e : found) {
            if (e.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AABB 가 닿는 모든 청크가 <b>이미</b> 로드돼 있는가.
     *
     * <p>{@code LevelReader.hasChunksAt(BlockPos, BlockPos)} 는 비로딩 조회다 — 없는 청크를
     * 만들거나 불러오지 않는다. {@code getChunk()} 계열은 쓰지 않는다.
     */
    public static boolean chunksLoaded(Level level, AABB box) {
        return level.hasChunksAt(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }

    /**
     * 호출자가 계산한 <b>위치 표본</b>을 따라 몸이 지나갈 수 있는가. 4차 설계서 2.5.7.
     *
     * <p>궤적 식은 각 이동 태스크가 만든다 — 여기서는 받은 표본만 본다. 표본이 너무 성기면
     * 사이의 블록을 건너뛸 수 있으므로 {@value #MAX_SAMPLE_STEP} 를 넘는 간격은 거절한다.
     * 첫 표본과 마지막 표본도 검사에 포함한다.
     *
     * @param samples 발 좌표 표본. 순서대로 이어진 한 궤적이어야 한다.
     */
    public static boolean sweepClear(WardenGirlEntity mob, List<Vec3> samples) {
        if (samples == null || samples.isEmpty()) {
            return false;
        }
        Level level = mob.level();
        Vec3 prev = null;
        for (Vec3 feet : samples) {
            if (prev != null && prev.distanceTo(feet) > MAX_SAMPLE_STEP) {
                return false;                       // 표본이 성겨 블록을 건너뛸 수 있다
            }
            prev = feet;
            AABB box = destinationBox(mob, feet);
            if (!chunksLoaded(level, box) || !level.noCollision(mob, box)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 착지점이 원래 목적 좌표에 대해 출발점보다 <b>실제로</b> 가까운가. 4차 설계서 2.5.11.
     *
     * <p>{@value #MIN_PROGRESS_GAIN} 블록 이상 좁혀져야 한다. 위협 점수를 다시 계산하지
     * 않는다 — FLEE 도 기존 후퇴 Goal 이 정한 목적지에 가까워지는지만 본다.
     */
    public static boolean improvesProgress(Vec3 origin, Vec3 landing, Vec3 goal) {
        double before = origin.distanceTo(goal);
        double after = landing.distanceTo(goal);
        return before - after >= MIN_PROGRESS_GAIN;
    }
}
