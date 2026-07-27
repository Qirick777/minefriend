package com.wardengirl.anim;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.Keyframe;
import software.bernie.geckolib.core.keyframe.KeyframeStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place where a json keyframe's sign is converted to the Part 4.0 bone convention.
 *
 * <h2>Why this exists</h2>
 *
 * T1 established the sign convention by calling {@code GeoBone.setRot*} from Java. The json
 * keyframe path is a different code path and does <b>not</b> agree with it. Measured, with a
 * distinct positive constant per bone per axis so that a flip and an axis swap would look
 * different (21 measurements, no exceptions):
 *
 * <pre>
 *   json 키프레임 -> 본 :   xRot x(-1)   yRot x(-1)   zRot x(+1)
 *   Java setRot*  -> 본 :   xRot x(+1)   yRot x(+1)   zRot x(+1)
 * </pre>
 *
 * Part 4's several hundred numbers are all written in the Java convention, and T1 verified that
 * one by measurement, so <b>the Java convention is the standard and json is what gets converted.</b>
 * The design doc's values go into the json file unchanged; this class flips x and y as the baked
 * animation is loaded. Reading the json file and seeing the sign "backwards" relative to the bone
 * is therefore expected — see Part 4.0.
 *
 * <h2>Why not in {@link AxisConvention}</h2>
 *
 * {@code AxisConvention} is the single point for degrees↔radians, and it is on the <em>Java</em>
 * path — the static offsets, the look addition and the headgear spring all go through it. Those
 * three already produce the right sign. Putting the flip there would invert them too. The two
 * paths need two different conversions, so they get two different classes; this one is reachable
 * only from {@link #convert}, which is called only from the model's baked-animation load.
 *
 * <h2>Rotation only</h2>
 *
 * Position and scale keyframes are left alone. The measurement covered rotation; nothing has been
 * measured about the position path, so nothing is assumed about it. {@code root.position.y} in the
 * walk cycle is the one place that matters today and it is reported separately.
 */
public final class JsonAxisConvention {

    private JsonAxisConvention() {
    }

    /**
     * Wraps a keyframe value so that it reads back negated, <b>without becoming a
     * {@link Constant}</b>.
     *
     * <p>That distinction is the whole problem. GeckoLib uses {@code instanceof Constant} as a
     * marker meaning "this value was already converted at bake time":
     *
     * <pre>
     *   BakedAnimationsAdapter.buildKeyframeStack(list, isForRotation)
     *       x,y: isConstant() ? new Constant(toRadians(-v)) : molangValue
     *       z  : isConstant() ? new Constant(toRadians( v)) : molangValue
     *
     *   AnimationController.getAnimationPointAtTick(..., isRotation, axis)
     *       if (isRotation and NOT instanceof Constant) {
     *           v = toRadians(v);  if (axis == X or Y) v *= -1;
     *       }
     * </pre>
     *
     * The first attempt wrapped every value in a plain {@code IValue}, which erased the marker, so
     * a value already in radians was put through {@code toRadians} a second time and negated
     * again. Measured: json {@code +61} on x arrived as {@code -1.065°}, and
     * {@code -(61 × π/180) = -1.0647}. The z axis was correct only because it was passed through
     * untouched and kept its {@code Constant}.
     */
    private record Negated(IValue inner) implements IValue {
        @Override
        public double get() {
            return -this.inner.get();
        }
    }

    /**
     * Negates one value, preserving whichever conversion stage it belongs to.
     *
     * <ul>
     *   <li><b>Constant</b> — already {@code toRadians(-deg)} from the bake. Negating gives
     *       {@code +toRadians(deg)}, which is what the bone should hold, and it must stay a
     *       {@code Constant} so the runtime leaves it alone.</li>
     *   <li><b>Expression</b> — still raw degrees; the runtime will apply
     *       {@code toRadians(v) × −1}. Feeding it {@code −deg} therefore yields
     *       {@code +toRadians(deg)}. It must stay non-{@code Constant} so that step still runs.</li>
     * </ul>
     */
    private static IValue negateValue(IValue v) {
        return v instanceof Constant ? new Constant(-v.get()) : new Negated(v);
    }

    /**
     * Returns {@code animation} with every rotation keyframe's x and y values negated.
     *
     * <p>Rebuilt rather than mutated: {@code Animation}, {@code BoneAnimation},
     * {@code KeyframeStack} and {@code Keyframe} are all records.
     */
    public static Animation convert(Animation animation) {
        BoneAnimation[] bones = animation.boneAnimations();
        BoneAnimation[] out = new BoneAnimation[bones.length];
        for (int i = 0; i < bones.length; i++) {
            BoneAnimation b = bones[i];
            out[i] = new BoneAnimation(b.boneName(),
                    flipXY(b.rotationKeyFrames()),
                    b.positionKeyFrames(),
                    b.scaleKeyFrames());
        }
        return new Animation(animation.name(), animation.length(), animation.loopType(), out,
                animation.keyFrames());
    }

    private static KeyframeStack<Keyframe<IValue>> flipXY(KeyframeStack<Keyframe<IValue>> stack) {
        return new KeyframeStack<>(negate(stack.xKeyframes()), negate(stack.yKeyframes()),
                stack.zKeyframes());
    }

    private static List<Keyframe<IValue>> negate(List<Keyframe<IValue>> frames) {
        List<Keyframe<IValue>> out = new ArrayList<>(frames.size());
        for (Keyframe<IValue> k : frames) {
            // easingArgs are outer spline control points in the same units as the values, so they
            // have to travel with them. Leaving them unflipped would bend the curve the wrong way
            // on exactly the axes this method is correcting.
            List<IValue> args = new ArrayList<>(k.easingArgs().size());
            for (IValue a : k.easingArgs()) {
                args.add(negateValue(a));
            }
            out.add(new Keyframe<>(k.length(), negateValue(k.startValue()),
                    negateValue(k.endValue()), k.easingType(), args));
        }
        return out;
    }
}
