package com.wardengirl.anim;

import java.util.List;

/**
 * The nine bones of the WardenGirl rig and the Part 4.0 sign convention they obey.
 *
 * <p>Shared by the axis-verification command (server side) and the model (client side), so it
 * cannot live in either — a client-only class referenced from a command would blow up on a
 * dedicated server.
 *
 * <p>Design doc Part 4.0: <em>"본 8개. 이 이상 추가하지 않는다."</em>
 */
public final class Bones {

    private Bones() {
    }

    public static final String ROOT = "root";
    /** Added after T1: whole-body lean / weight shift. No cube — invisible. */
    public static final String HIP = "hip";
    public static final String BODY = "body";
    public static final String HEAD = "head";
    /**
     * The warden's sensory tendrils. Split left/right after T3 so the two springs can run
     * independently — with one bone both halves move identically, which reads as machinery.
     */
    public static final String HEADGEAR_RIGHT = "headgear_right";
    public static final String HEADGEAR_LEFT = "headgear_left";
    public static final String ARM_RIGHT = "arm_right";
    public static final String ARM_LEFT = "arm_left";
    public static final String LEG_RIGHT = "leg_right";
    public static final String LEG_LEFT = "leg_left";

    /** All ten, in the hierarchy order given by the design doc Part 4.0 tree. */
    public static final List<String> ALL = List.of(
            ROOT, HIP, BODY, HEAD, HEADGEAR_RIGHT, HEADGEAR_LEFT,
            ARM_RIGHT, ARM_LEFT, LEG_RIGHT, LEG_LEFT);

    /** The two spring-driven tendrils, right then left. */
    public static final List<String> HEADGEAR = List.of(HEADGEAR_RIGHT, HEADGEAR_LEFT);

    /**
     * Default axis-test angle.
     *
     * <p>The design doc says +10°, but 10° proved too small to judge from a screenshot — over half
     * the poses were indistinguishable from neutral by eye. Raised to 45° by explicit instruction;
     * the angle is now an argument, so 10° is still reachable when wanted.
     *
     * <p>Lives here, in common code, rather than next to the client-side reporter — the command
     * runs on the server and must not touch client-only classes.
     */
    public static final double AXIS_TEST_ANGLE_DEGREES = 45.0D;

    public enum Axis {
        X, Y, Z;

        public static Axis parse(String raw) {
            return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "x", "xrot" -> X;
                case "y", "yrot" -> Y;
                case "z", "zrot" -> Z;
                default -> null;
            };
        }
    }

    /**
     * The physical meaning of a rotation, per design doc Part 4.0.1's derived table.
     *
     * <p>Sign-aware: pass the actual signed angle, because every entry reverses with the sign and
     * a human reading "위를 봄" while looking at a downward tilt is exactly the confusion this is
     * meant to prevent.
     *
     * <p>These are <em>derived</em> descriptions, not the convention itself. The convention is
     * stated once, in {@link AxisConvention}: the point above the pivot moves toward the mob's
     * back for +xRot, and toward the mob's left for +yRot / +zRot.
     */
    public static String expectation(String bone, Axis axis, double degrees) {
        boolean pos = degrees >= 0;
        return switch (bone) {
            case HEAD -> switch (axis) {
                case X -> pos ? "위를 봄" : "아래를 봄";
                case Y -> pos ? "왼쪽을 봄" : "오른쪽을 봄";
                case Z -> pos ? "왼쪽 갸웃" : "오른쪽 갸웃";
            };
            // body pivots at the hip (0,12,0), so this is the upper body only — the legs hang
            // off hip and stay put.
            case BODY -> switch (axis) {
                case X -> pos ? "상체를 뒤로 젖힘" : "상체를 앞으로 숙임";
                case Y -> pos ? "상체를 왼쪽으로 비틀기" : "상체를 오른쪽으로 비틀기";
                case Z -> pos ? "상체를 왼쪽으로 기울임" : "상체를 오른쪽으로 기울임";
            };
            // hip carries everything including the legs — the old body behaviour.
            case HIP -> switch (axis) {
                case X -> pos ? "전신을 뒤로 젖힘 (다리 포함)" : "전신을 앞으로 숙임 (다리 포함)";
                case Y -> pos ? "전신을 왼쪽으로 비틀기" : "전신을 오른쪽으로 비틀기";
                case Z -> pos ? "전신을 왼쪽으로 기울임 (다리 포함)" : "전신을 오른쪽으로 기울임 (다리 포함)";
            };
            case ARM_RIGHT, LEG_RIGHT -> switch (axis) {
                case X -> pos ? "앞으로 휘두름" : "뒤로 젖힘";
                case Y -> pos ? "위에서 볼 때 반시계" : "위에서 볼 때 시계";
                case Z -> pos ? "바깥으로 벌림" : "안쪽으로 붙임";
            };
            case ARM_LEFT, LEG_LEFT -> switch (axis) {
                case X -> pos ? "앞으로 휘두름" : "뒤로 젖힘";
                case Y -> pos ? "위에서 볼 때 반시계" : "위에서 볼 때 시계";
                case Z -> pos ? "안쪽으로 붙임" : "바깥으로 벌림";
            };
            // root carries position only (bounce, recoil travel) and has no rotation keyframes.
            case ROOT -> "(root 는 위치 전용 — 회전 키프레임 없음. 규약은 적용되나 미사용)";
            // headgear obeys the convention but is driven by the 4.5 spring, never by keyframes.
            case HEADGEAR_RIGHT, HEADGEAR_LEFT ->
                    "(감각 촉수는 키프레임 없음 — 4.5 스프링 출력에만 규약 적용)";
            default -> "(알 수 없는 본)";
        };
    }
}
