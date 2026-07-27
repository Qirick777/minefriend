package com.wardengirl.anim;

import java.util.List;

/**
 * The eight bones of the WardenGirl rig and the Part 4.0 sign convention they are supposed to obey.
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
    public static final String BODY = "body";
    public static final String HEAD = "head";
    public static final String HEADGEAR = "headgear";
    public static final String ARM_RIGHT = "arm_right";
    public static final String ARM_LEFT = "arm_left";
    public static final String LEG_RIGHT = "leg_right";
    public static final String LEG_LEFT = "leg_left";

    /** All eight, in the hierarchy order given by the design doc Part 4.0 tree. */
    public static final List<String> ALL = List.of(
            ROOT, BODY, HEAD, HEADGEAR, ARM_RIGHT, ARM_LEFT, LEG_RIGHT, LEG_LEFT);

    /**
     * Design doc P1-T1: "지정 본을 각 축 +10° 회전시켜 정지".
     *
     * <p>Lives here, in common code, rather than next to the client-side reporter — the command
     * runs on the server and must not touch client-only classes.
     */
    public static final double AXIS_TEST_ANGLE_DEGREES = 10.0D;

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
     * What Part 4.0 says a <em>positive</em> rotation on this bone/axis should look like.
     *
     * <p>This is the whole point of the T1 axis check: the command prints the expectation, the
     * human looks at the screen, and the two either agree or they do not. GeckoLib's json axis
     * directions are not guaranteed to match this table — that is exactly what is being tested.
     */
    public static String expectation(String bone, Axis axis) {
        return switch (bone) {
            case BODY -> switch (axis) {
                case X -> "앞으로 숙임";
                case Y -> "오른쪽으로 비틀기";
                case Z -> "오른쪽으로 기울임";
            };
            case HEAD -> switch (axis) {
                case X -> "아래를 봄";
                case Y -> "오른쪽을 봄";
                case Z -> "오른쪽으로 갸웃";
            };
            case ARM_RIGHT, ARM_LEFT -> switch (axis) {
                case X -> "뒤로 젖힘";
                case Y -> "(규약 없음 — Part 4.0 arm yRot 미정의)";
                case Z -> "몸 안쪽으로 붙임";
            };
            case LEG_RIGHT, LEG_LEFT -> switch (axis) {
                case X -> "뒤로 뻗음";
                case Y -> "발끝 안쪽";
                case Z -> "벌림";
            };
            case ROOT -> "(규약 없음 — root 는 위치 전용: 바운스 / 반동 이동)";
            case HEADGEAR -> "(규약 없음 — headgear 는 4.5 물리 추종 전용, 키프레임 금지)";
            default -> "(알 수 없는 본)";
        };
    }
}
