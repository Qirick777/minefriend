package com.wardengirl.anim;

import com.wardengirl.WardenGirlMod;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.Keyframe;
import software.bernie.geckolib.core.keyframe.KeyframeStack;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Checks that a transition can actually happen between two clips.
 *
 * <h2>The rule this enforces</h2>
 *
 * <b>A GeckoLib transition only writes bones that exist in the <em>target</em> clip.</b>
 * {@code AnimationController.process}'s {@code TRANSITIONING} branch iterates
 * {@code currentAnimation.animation().boneAnimations()} — the clip being transitioned <em>to</em> —
 * and calls {@code addNextRotation} only for what it finds there (4.8.4, offset 352).
 * {@code saveSnapshotsForAnimation} iterates the same array. Anything the source clip drives but the
 * target does not simply stops being written: the queue is empty, {@code AnimationProcessor} polls
 * {@code null}, and the whole three-axis write is skipped.
 *
 * <p>Measured, with {@code idle} as an empty clip: {@code leg_right.xRot} went
 * {@code +15.6800 → +0.0000} in one frame and stayed there, and {@code arm_right.xRot} landed on
 * exactly {@code offset_arm_right_x}. The 6-tick transition length had nothing to interpolate.
 *
 * <h2>Why a checker and not a comment</h2>
 *
 * The rule creates a dependency between two files' contents: adding a channel to {@code walk}
 * silently requires adding it to {@code idle} too, and forgetting produces a one-frame snap on that
 * one axis — which looks like a rendering glitch, not like a missing keyframe. A rule that depends
 * on someone remembering it is a rule that will be broken. This runs on first use and names exactly
 * what is missing.
 *
 * <h2>Channels, not axes</h2>
 *
 * The comparison unit is (bone, channel), not (bone, axis). A json rotation entry always yields all
 * three axes, and {@code AnimationProcessor} requires all three queues to be non-empty before it
 * writes any of them — so a half-present channel is not a state either file can be in.
 */
public final class ClipStructureCheck {

    private ClipStructureCheck() {
    }

    private enum Channel {
        ROTATION, POSITION, SCALE
    }

    private record Key(String bone, Channel channel) {
        @Override
        public String toString() {
            return this.bone + "." + this.channel.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Logs every channel {@code from} drives that {@code to} does not.
     *
     * @return true when the transition is fully covered
     */
    public static boolean verifyTransitionCoverage(String fromName, Animation from,
                                                   String toName, Animation to) {
        if (from == null || to == null) {
            WardenGirlMod.LOGGER.warn(
                    "[clipcheck] {} -> {} 전이 검사 불가 — 클립을 찾지 못했다", fromName, toName);
            return false;
        }
        Set<Key> source = channels(from);
        Set<Key> target = channels(to);
        source.removeAll(target);
        if (source.isEmpty()) {
            WardenGirlMod.LOGGER.info(
                    "[clipcheck] {} -> {} 전이 채널 커버리지 OK — {}가 구동하는 채널 {}개 전부 {}에 있다",
                    fromName, toName, fromName, channels(from).size(), toName);
            return true;
        }
        // Not an exception: a missing channel degrades one axis, and crashing the client over it
        // would be a worse outcome than a visible warning plus a visible snap.
        WardenGirlMod.LOGGER.error(
                "[clipcheck] **{} -> {} 전이가 끊긴다.** {}에 없는 채널 {}개: {}",
                fromName, toName, toName, source.size(), source);
        WardenGirlMod.LOGGER.error(
                "[clipcheck] GeckoLib 전이는 대상 클립의 boneAnimations() 만 훑는다. "
                        + "{}에 상수 0 채널을 추가해라. 없으면 그 축만 한 프레임에 뚝 끊긴다", toName);
        return false;
    }

    private static Set<Key> channels(Animation animation) {
        Set<Key> out = new LinkedHashSet<>();
        for (BoneAnimation bone : animation.boneAnimations()) {
            add(out, bone.boneName(), Channel.ROTATION, bone.rotationKeyFrames());
            add(out, bone.boneName(), Channel.POSITION, bone.positionKeyFrames());
            add(out, bone.boneName(), Channel.SCALE, bone.scaleKeyFrames());
        }
        return out;
    }

    private static void add(Set<Key> out, String bone, Channel channel,
                            KeyframeStack<Keyframe<com.eliotlash.mclib.math.IValue>> stack) {
        // All three, matching AnimationProcessor's own precondition (4.8.4, offset 312).
        if (!stack.xKeyframes().isEmpty() && !stack.yKeyframes().isEmpty()
                && !stack.zKeyframes().isEmpty()) {
            out.add(new Key(bone, channel));
        }
    }
}
