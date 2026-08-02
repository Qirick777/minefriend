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

    /**
     * Every channel Java writes with {@code +=} in {@code setCustomAnimations}.
     *
     * <p><b>Hand-maintained, and that is the point.</b> There is no way to derive this from the
     * code — it is the set of {@code addRot*}/{@code addPos*} targets across C1, C3, the static
     * offsets, the look, the turn lean and the walk blend. Writing it out here makes it an
     * independent statement that the checker can hold the clips against; deriving it from the clips
     * would make the check vacuous.
     *
     * <p><b>Add a channel to this list whenever a new Java writer appears.</b> A channel that is
     * written with {@code +=} and assigned by no clip accumulates one offset per frame forever —
     * {@code AnimationProcessor} skips its reset once the bone is marked changed, and nothing
     * un-marks it. That is what made a leg spin through 360° for a whole task cycle in T2.
     *
     * <p>{@code headgear_*} is deliberately absent: the 4.5 spring <em>assigns</em> its bones every
     * frame rather than adding, so accumulation is not possible there.
     */
    private static final Set<Key> JAVA_ADDED = new LinkedHashSet<>(java.util.List.of(
            new Key(Bones.BODY, Channel.ROTATION),      // C1 x/y/z, 4.3.4 offset x, blend y
            new Key(Bones.BODY, Channel.POSITION),      // 4.3.1 chest rise
            new Key(Bones.HEAD, Channel.ROTATION),      // C1 x/z, 4.3.4 offset x, 4.6 look, blend y
            new Key(Bones.HIP, Channel.ROTATION),       // 4.4.3 turn lean z
            new Key(Bones.ARM_RIGHT, Channel.ROTATION), // C1 z, 4.3.4 offset x, blend x
            new Key(Bones.ARM_LEFT, Channel.ROTATION),
            new Key(Bones.LEG_RIGHT, Channel.ROTATION), // 4.3.4 offset y
            new Key(Bones.LEG_LEFT, Channel.ROTATION),
            new Key(Bones.ROOT, Channel.POSITION),      // 4.3.2 bounce
            // T6 4.12: 킁킁 전 몸통 회전이 root.yRot 에 가산된다. 이 줄이 없어서 root.yRot 이
            // -16734 ~ +7747 로 폭주했고 검사기는 통과를 찍었다 - 세트가 수작업이라는 것이
            // 이 검사기의 유일한 약점이다. Java 가산 채널을 늘리면 반드시 여기도 늘려라.
            new Key(Bones.ROOT, Channel.ROTATION),
            // T30: C3 액션 클립은 pose.positionsRaw() 를 addPositionRaw 로 <b>가산</b>하므로
            // 어떤 본이든 position 채널을 쓸 수 있다. hip.position 이 이 목록에 없어서
            // gap_dive 가 hip 을 내린 뒤 모델이 영구히 지면 아래로 내려갔다 — 검사기는
            // 통과를 찍었다. 액션 레이어가 닿을 수 있는 본의 position 을 전부 등록한다.
            new Key(Bones.HIP, Channel.POSITION),
            new Key(Bones.HEAD, Channel.POSITION),
            new Key(Bones.ARM_RIGHT, Channel.POSITION),
            new Key(Bones.ARM_LEFT, Channel.POSITION),
            new Key(Bones.LEG_RIGHT, Channel.POSITION),
            new Key(Bones.LEG_LEFT, Channel.POSITION)));

    /**
     * Checks that every {@link #JAVA_ADDED} channel is assigned every frame, for one locomotion clip.
     *
     * <p>The always-on {@code base} clip and whichever clip the locomotion controller is playing are
     * the only assigners, so coverage has to hold for <b>each</b> locomotion clip separately —
     * {@code walk}, {@code idle} and {@code signtest} are not interchangeable, and a gap that only
     * exists while one of them plays is exactly the kind that survives testing.
     *
     * @return true when covered
     */
    public static boolean verifyAccumulationCoverage(Animation base, String clipName,
                                                     Animation clip) {
        if (base == null || clip == null) {
            WardenGirlMod.LOGGER.warn("[clipcheck] base + {} 누적 방지 검사 불가 — 클립을 찾지 못했다",
                    clipName);
            return false;
        }
        Set<Key> covered = channels(base);
        covered.addAll(channels(clip));
        Set<Key> missing = new LinkedHashSet<>(JAVA_ADDED);
        missing.removeAll(covered);
        if (missing.isEmpty()) {
            WardenGirlMod.LOGGER.info(
                    "[clipcheck] base + {} 누적 방지 커버리지 OK — Java 가 += 로 쓰는 채널 {}개 전부 대입된다",
                    clipName, JAVA_ADDED.size());
            return true;
        }
        WardenGirlMod.LOGGER.error(
                "[clipcheck] **base + {} 재생 중 누적이 발생한다.** 대입되지 않는 채널 {}개: {}",
                clipName, missing.size(), missing);
        WardenGirlMod.LOGGER.error(
                "[clipcheck] Java 가 += 로 쓰는데 어느 클립도 대입하지 않으면 프레임마다 누적된다 "
                        + "(T2 다리 360도). base 또는 {} 에 상수 0 채널을 추가해라", clipName);
        return false;
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
