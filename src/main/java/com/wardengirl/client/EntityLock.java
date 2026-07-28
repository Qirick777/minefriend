package com.wardengirl.client;

import com.wardengirl.WardenGirlMod;

/**
 * Restricts every measurement in this package to a single entity, and counts the ones it turned
 * away.
 *
 * <h2>Why this exists</h2>
 *
 * All four verifiers ({@link BoneTrace}, {@link VitalCheck}, {@link BlendCheck}, {@link ActionCheck})
 * hold their state in static fields, because a check is a one-off run against one mob. Nothing
 * enforced that. {@code setCustomAnimations} runs once per <em>rendered entity</em>, so the moment a
 * second warden girl is on screen, two independent animation states are written into the same
 * accumulators — interleaved, at whatever order the renderer happens to walk them.
 *
 * <p><b>Measured instance.</b> The test world is not deleted between harness runs, so warden girls
 * summoned by earlier runs were still standing in the pen. A 1200-tick window reported
 * <b>1569 idle↔walk transitions</b>. The walk state is a per-tick decision, so one entity can
 * produce at most one transition per tick and 1569 &gt; 1200 is arithmetically impossible — which is
 * how the contamination was caught. The grace list confirmed it directly: consecutive rows read
 * {@code t=924} then {@code t=370}, two entities' tick counters interleaved.
 *
 * <p>The failure mode is the dangerous kind: it produces a full page of plausible numbers. Nothing
 * about the ratios or the verdicts looked wrong. So the lock <b>reports</b> rather than merely
 * filtering — a window that saw more than one entity says so in its validity block, next to the
 * numbers, where Part 6.3.0 requires it.
 */
public final class EntityLock {

    private EntityLock() {
    }

    private static int lockedId = Integer.MIN_VALUE;
    private static int rejectedEntities = 0;
    private static int rejectedFrames = 0;

    /** Called by each verifier's {@code start}: the next entity to render becomes the subject. */
    public static void reset() {
        lockedId = Integer.MIN_VALUE;
        rejectedEntities = 0;
        rejectedFrames = 0;
    }

    /** True only for the one entity this measurement is about. */
    public static boolean accepts(int entityId) {
        if (lockedId == Integer.MIN_VALUE) {
            lockedId = entityId;
            WardenGirlMod.LOGGER.info("[measure] 측정 대상 엔티티 고정: id={}", entityId);
            return true;
        }
        if (lockedId == entityId) {
            return true;
        }
        if (rejectedFrames == 0) {
            rejectedEntities = 1;
        }
        rejectedFrames++;
        return false;
    }

    /** Non-mutating: is this the locked entity? For call sites downstream of {@link #accepts}. */
    public static boolean isSubject(int entityId) {
        return lockedId == entityId;
    }

    public static int rejectedFrames() {
        return rejectedFrames;
    }

    /**
     * The validity line. Printed by every verifier's report, unconditionally — "only one mob was in
     * the pen" is exactly the kind of assumption that has to be stated as a measurement rather than
     * believed.
     */
    public static String validityLine() {
        return rejectedFrames == 0
                ? String.format("측정 대상 엔티티 id=%d 하나만 관측 — 오염 없음", lockedId)
                : String.format("**다른 엔티티 %d개 이상에서 프레임 %d개를 걸렀다** (대상 id=%d). "
                        + "월드에 워든걸이 둘 이상 있다 — 창은 유효하나 원인을 제거해라",
                        rejectedEntities, rejectedFrames, lockedId);
    }
}
