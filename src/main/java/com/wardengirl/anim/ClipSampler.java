package com.wardengirl.anim;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.AnimationPoint;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.Keyframe;
import software.bernie.geckolib.core.keyframe.KeyframeStack;
import software.bernie.geckolib.core.object.Axis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a baked clip's pose at an arbitrary tick, without an {@code AnimationController}.
 *
 * <h2>Why this exists</h2>
 *
 * C3 (the action layer) has to be able to play <b>at the same time as</b> C2 (walking) — 걷기 +
 * 기본 공격 동시 재생은 필수 요구사항이다. Two GeckoLib controllers cannot do that: the later one
 * overwrites the earlier (Part 4.2, measured). The only place a second layer can survive is
 * {@code setCustomAnimations}, which runs after all controllers — and to add a clip's pose there,
 * something has to evaluate that clip. That is this class.
 *
 * <h2>It is a transcription, not a reimplementation</h2>
 *
 * {@code AnimationController.getCurrentKeyFrameLocation} and {@code getAnimationPointAtTick} are
 * private, so the two of them are transcribed here from the 4.8.4 bytecode. Everything downstream
 * — {@code AnimationPoint}, {@code EasingType.lerpWithOverride} and therefore every easing curve
 * including the repaired {@code catmullrom} — is the library's own public code, called directly.
 * Nothing about the shape of a curve is re-derived here.
 *
 * <h2>Signs: the same as C2, not different from it</h2>
 *
 * <b>This takes the animation that {@link JsonAxisConvention} has already converted</b> (the model
 * caches exactly one converted copy per clip and hands it over). That is not incidental — the
 * per-axis {@code catmullrom} {@code easingArgs} only exist on the converted copy, so reading the
 * raw bake would silently drop every spline onto the broken no-args branch.
 *
 * <p>Taking the converted copy means the sign conversion comes with it, and the transcription below
 * reproduces the runtime's {@code instanceof Constant} rule as well. So a C3 clip and a C2 clip
 * containing the same number put the same value on the bone. <b>There is no second sign system.</b>
 * See Part 4.2 — this corrects an expectation that direct evaluation would bypass the conversion.
 *
 * <h2>Positions are left exactly as GeckoLib applies them</h2>
 *
 * Rotations come back in degrees, positions in <em>GeckoLib's</em> raw position units — i.e. what
 * {@code CoreGeoBone.setPosX} would have received, <b>not</b> what {@link AxisConvention} calls
 * model pixels. Those two disagree on x: {@code AxisConvention.setPositionPx} negates x because
 * {@code RenderUtils.translateMatrixToBone} emits {@code translate(-posX/16, ...)}, and the json
 * path does not. Matching the json path keeps C2 and C3 in agreement with each other, which is what
 * matters for a clip; the disagreement with the Java-side writers is real, is unmeasured on x, and
 * is recorded in Part 11 rather than papered over here. Nothing in Part 4 uses a position x today.
 */
public final class ClipSampler {

    private ClipSampler() {
    }

    /**
     * One clip's contribution at one instant.
     *
     * @param rotationsDeg bone -> {x,y,z} degrees, only bones the clip actually animates
     * @param positionsRaw bone -> {x,y,z} in GeckoLib position units (see class doc on x)
     */
    public record Pose(Map<String, double[]> rotationsDeg, Map<String, double[]> positionsRaw) {
    }

    /**
     * @param animation the <b>converted</b> animation (see class doc)
     * @param ageTicks  playback position in ticks, may be fractional
     */
    public static Pose sample(Animation animation, double ageTicks) {
        Map<String, double[]> rotations = new LinkedHashMap<>();
        Map<String, double[]> positions = new LinkedHashMap<>();
        for (BoneAnimation bone : animation.boneAnimations()) {
            double[] rot = axes(bone.rotationKeyFrames(), ageTicks, true);
            if (rot != null) {
                rotations.put(bone.boneName(), new double[]{
                        Math.toDegrees(rot[0]), Math.toDegrees(rot[1]), Math.toDegrees(rot[2])});
            }
            double[] pos = axes(bone.positionKeyFrames(), ageTicks, false);
            if (pos != null) {
                positions.put(bone.boneName(), pos);
            }
        }
        return new Pose(rotations, positions);
    }

    /**
     * All three axes of one channel, or null when the clip does not animate it.
     *
     * <p>All-or-nothing on purpose: {@code AnimationProcessor} writes a bone's rotation only when
     * all three axis queues produced a point, and skips the whole write otherwise (4.8.4, offset
     * 312). A channel that behaved differently here would drift from what the controller path does
     * with the same json.
     */
    private static double[] axes(KeyframeStack<Keyframe<IValue>> stack, double age,
                                 boolean isRotation) {
        List<Keyframe<IValue>> x = stack.xKeyframes();
        List<Keyframe<IValue>> y = stack.yKeyframes();
        List<Keyframe<IValue>> z = stack.zKeyframes();
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return null;
        }
        return new double[]{
                value(x, age, isRotation, Axis.X),
                value(y, age, isRotation, Axis.Y),
                value(z, age, isRotation, Axis.Z)};
    }

    private static double value(List<Keyframe<IValue>> frames, double age, boolean isRotation,
                                Axis axis) {
        return EasingType.lerpWithOverride(pointAt(frames, age, isRotation, axis), null);
    }

    /**
     * Transcription of {@code AnimationController.getAnimationPointAtTick} (4.8.4, private).
     *
     * <p>The {@code instanceof Constant} test is the library's "already converted at bake time"
     * marker, not a type check for its own sake — a constant was baked to
     * {@code Constant(toRadians(±v))} and must not be converted twice, while a Molang expression is
     * still raw degrees and must be. {@link JsonAxisConvention} preserves that marker on purpose;
     * an earlier attempt that erased it produced {@code -1.065°} where {@code +61°} was written.
     */
    private static AnimationPoint pointAt(List<Keyframe<IValue>> frames, double age,
                                          boolean isRotation, Axis axis) {
        Located located = locate(frames, age);
        Keyframe<IValue> frame = located.frame();
        double start = frame.startValue().get();
        double end = frame.endValue().get();
        if (isRotation) {
            if (!(frame.startValue() instanceof Constant)) {
                start = Math.toRadians(start);
                if (axis == Axis.X || axis == Axis.Y) {
                    start *= -1.0D;
                }
            }
            if (!(frame.endValue() instanceof Constant)) {
                end = Math.toRadians(end);
                if (axis == Axis.X || axis == Axis.Y) {
                    end *= -1.0D;
                }
            }
        }
        return new AnimationPoint(frame, located.startTick(), frame.length(), start, end);
    }

    private record Located(Keyframe<IValue> frame, double startTick) {
    }

    /**
     * Transcription of {@code AnimationController.getCurrentKeyFrameLocation} (4.8.4, private).
     *
     * <p>A GeckoLib keyframe is a <em>segment</em> whose {@code length} is the gap to the next one,
     * so the running total is the segment's end time and {@code startTick} is how far into that
     * segment the playhead is. Past the last segment the library clamps to the final keyframe and
     * passes the raw age through — which for a single-keyframe (constant) track means every age
     * lands on that one frame with {@code start == end}, i.e. the constant, at any tick.
     */
    private static Located locate(List<Keyframe<IValue>> frames, double age) {
        double total = 0.0D;
        for (Keyframe<IValue> frame : frames) {
            total += frame.length();
            if (total > age) {
                return new Located(frame, age - (total - frame.length()));
            }
        }
        return new Located(frames.get(frames.size() - 1), age);
    }
}
