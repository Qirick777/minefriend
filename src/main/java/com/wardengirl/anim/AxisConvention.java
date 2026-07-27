package com.wardengirl.anim;

import software.bernie.geckolib.cache.object.GeoBone;

/**
 * The rig's angle convention, and the single place degrees become radians.
 *
 * <p>Design doc Part 4.0.1 / 4.0.2.
 *
 * <h2>Convention (design doc Part 4.0.1)</h2>
 *
 * Reference pose: the mob faces world <b>+Z</b>. Its right is world <b>-X</b>, its left world
 * <b>+X</b>. The reference point is <b>the point directly above the pivot</b> (for yRot, the point
 * directly in front of the pivot):
 *
 * <pre>
 *   +xRot : reference point moves toward the mob's BACK  (-Z)
 *   +yRot : reference point (front) moves to the mob's LEFT (+X) = counter-clockwise from above
 *   +zRot : reference point moves to the mob's LEFT  (+X)
 * </pre>
 *
 * This holds for all eight bones with no exceptions, which is why there is no per-bone table here.
 *
 * <h2>There are deliberately no sign flips</h2>
 *
 * <p>An earlier plan was a (bone, axis) sign-inversion table to force GeckoLib's directions to
 * match the doc. That plan was dropped, and the doc was rewritten to match GeckoLib instead. The
 * reason is structural: <b>keyframes in {@code animation.json} are applied by GeckoLib itself and
 * never pass through this class.</b> Inverting here would leave json holding inverted values and
 * Java holding raw ones — two coexisting sign systems over the same rig.
 *
 * <p>So: whatever number appears in the doc, in json, or in Java means the same thing everywhere.
 * Do not add a negation anywhere else to "fix" a direction. Fix the number.
 *
 * <h2>Units (design doc Part 4.0.2)</h2>
 *
 * <p>Every angle in this project is in <b>degrees</b> — parameters, keyframes, constants, logs.
 * Radians exist only at the boundary with GeckoLib, because
 * {@code GeoBone.setRotX/Y/Z} takes radians ({@code RenderUtils.rotateMatrixAroundBone} feeds them
 * to {@code com.mojang.math.Axis.rotation(float)}, the radian entry point — verified from the
 * GeckoLib bytecode). Variables holding radians carry the {@code Rad} suffix; anything without it
 * is degrees.
 *
 * <p><b>The methods below are the only sanctioned conversion path.</b> Calling
 * {@code Math.toRadians} elsewhere and writing the result to a bone re-opens the same class of bug
 * this file exists to close.
 */
public final class AxisConvention {

    private AxisConvention() {
    }

    /** Degrees to radians. The only conversion in the animation path. */
    public static float toRad(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** Radians back to degrees, for reporting measured bone state. */
    public static double toDeg(float radians) {
        return Math.toDegrees(radians);
    }

    /** Sets one axis of a bone from a value in <b>degrees</b>. */
    public static void set(GeoBone bone, Bones.Axis axis, double degrees) {
        float rad = toRad(degrees);
        switch (axis) {
            case X -> bone.setRotX(rad);
            case Y -> bone.setRotY(rad);
            case Z -> bone.setRotZ(rad);
        }
    }

    /** Zeroes all three axes of a bone. */
    public static void zero(GeoBone bone) {
        bone.setRotX(0);
        bone.setRotY(0);
        bone.setRotZ(0);
    }

    /** This bone's current rotation as degrees, in x/y/z order. */
    public static double[] readDegrees(GeoBone bone) {
        return new double[]{toDeg(bone.getRotX()), toDeg(bone.getRotY()), toDeg(bone.getRotZ())};
    }

    /**
     * This bone's current position offset in pixels, x/y/z.
     *
     * <p>Positions are never converted — they are model pixels on both sides. Needed because the
     * bounce (4.3.2) and the chest rise (4.3.1) are positions, not rotations, so a rotation-only
     * readback would report a rig that looks correct while the bounce silently does nothing.
     */
    public static double[] readPositionPx(GeoBone bone) {
        return new double[]{bone.getPosX(), bone.getPosY(), bone.getPosZ()};
    }
}
