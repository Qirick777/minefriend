package com.wardengirl.entity;

import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.AnimRegistry;
import com.wardengirl.registry.ModSounds;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;

/**
 * 4.12 킁킁 — <b>서버 판정</b>. 4.14 에서 클라이언트 {@code IdleReaction} 을 대체했다.
 *
 * <h2>왜 서버인가</h2>
 *
 * 이전 구조는 각 클라이언트가 <b>자기 플레이어</b>를 기준으로 거리·정면각·확률을 따로 굴렸다.
 * 그래서 같은 몹이 사람마다 다른 순간에 킁킁을 했고, 소리도 {@code playLocalSound} 로 그 클라이언트
 * 에서만 났다. 판정을 서버로 올리면 결정이 하나이므로 모든 관찰자가 같은 몹을 본다.
 *
 * <h2>바닐라 구조를 그대로 쓴다</h2>
 *
 * 새 패킷도, 새 동기화 필드도, 순번도 없다.
 * <ul>
 *   <li>판정 — {@link Goal}. {@code Mob.serverAiStep} 은 {@code (서버틱 + 엔티티id) % 2} 로 갈려
 *       {@link #canUse()} 를 <b>두 틱에 한 번</b> 부른다(바이트코드 확인). 그래서 경과 틱은
 *       {@code mob.tickCount} 차분으로 센다 — 호출 간격이 1이든 2든 같은 결과가 나온다.</li>
 *   <li>대상 — {@code Level.getNearestPlayer}. 전체 플레이어 목록을 직접 돌지 않는다.</li>
 *   <li>시작 통지 — {@code Level.broadcastEntityEvent}, 표준 엔티티 사건. 4.11 피격이 쓰는 것과
 *       같은 경로다.</li>
 *   <li>소리 — {@code Entity.playSound} → {@code Level.playSound(null, ...)}. 서버에서는 주변
 *       모든 플레이어에게 방송되고 각자 거리만큼 감쇠해서 듣는다.</li>
 * </ul>
 *
 * <h2>규칙은 이전 설계 그대로다</h2>
 *
 * 거리 / 정면각 / dwell / 확률 주기 / 확률 / 쿨다운 / 두 소리 시점 / 볼륨 / 피치 / 클립 길이 /
 * 피격 중단 — 값과 의미를 바꾸지 않았다. 소유자·길들이기 체계는 이 프로젝트에 없고, 새로 만들지도
 * 않았다. 대상은 이전과 같이 <b>가장 가까운 플레이어</b>다.
 */
public class WardenGirlSniffGoal extends Goal {

    /**
     * 나가는 문턱 배율. 들어오는 문턱과 다르다 — 같으면 플레이어가 경계에서 한 발짝씩 움직일 때
     * dwell 이 매 틱 리셋됐다 다시 차오르기를 반복해 발동이 튄다. 이 값은 사양에 없고
     * {@code IdleReaction} 에서 그대로 옮겨 왔다.
     */
    public static final double EXIT_FACTOR = 1.25D;

    /** 소리를 터뜨릴 재생 나이(틱). 0.746초 클립 안에 킁킁 2회가 있다 — 정점 0.180초와 0.485초. */
    private static final double[] SOUND_AT = {4.0D, 22.0D};

    private final WardenGirlEntity mob;
    /**
     * 대상 선별 규약. {@code LookAtPlayerGoal} 이 쓰는 것과 같은 {@code forNonCombat()} 이다 —
     * 관전자는 빼고 크리에이티브는 넣는다. {@code getNearestPlayer(Entity, double)} 쪽은
     * 크리에이티브 플레이어를 <b>제외</b>하므로 쓰지 않는다. 이전 클라이언트 판정은
     * {@code Minecraft.getInstance().player} 를 게임모드와 무관하게 봤고, 이쪽이 그것과 같다.
     */
    private final TargetingConditions targeting = TargetingConditions.forNonCombat();

    // ---- 판정 상태 -------------------------------------------------------------------------
    /** dwell 을 쌓고 있는 대상. 바뀌면 누적을 버린다. */
    private Player dwellTarget;
    private int dwell;
    private int cooldown;
    private double rollTimer;
    private boolean inside;
    private boolean frontInside;
    private int lastTick = Integer.MIN_VALUE;

    // ---- 재생 상태 -------------------------------------------------------------------------
    /** 재생 나이(틱). 음수면 재생 중이 아니다. */
    private int ticks = -1;
    private int soundsFired;

    public WardenGirlSniffGoal(WardenGirlEntity mob) {
        this.mob = mob;
    }

    /**
     * 플래그 없음. 킁킁은 몸통 클립이고 시선을 막지 않는다 — {@code LookAtPlayerGoal} /
     * {@code RandomLookAroundGoal} 과 동시에 돌아야 한다.
     */

    /** 두 소리의 시점을 틱 단위로 잡으려면 재생 중 매 틱 {@link #tick()} 이 필요하다. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** 공격·피격·부호 실측 중에는 발동하지도, 계속하지도 않는다. C3 에서 가장 낮은 우선순위다. */
    private boolean blocked() {
        return this.mob.hurtTime != 0
                || !this.mob.getActionClip().isEmpty()
                || this.mob.isSignTest();
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    /** 대상이 바뀌거나 사라지면 누적을 전부 버린다. 다른 사람의 dwell 을 물려받지 않는다. */
    private void resetDwell(Player next) {
        this.dwellTarget = next;
        this.dwell = 0;
        this.inside = false;
        this.frontInside = false;
        this.rollTimer = 0.0D;
    }

    @Override
    public boolean canUse() {
        int tick = this.mob.tickCount;
        int elapsed = this.lastTick == Integer.MIN_VALUE ? 1 : tick - this.lastTick;
        this.lastTick = tick;
        if (elapsed < 0) {
            elapsed = 1;
        }

        double enter = AnimParams.SNIFF_DISTANCE.get();
        if (enter <= 0.0D) {
            // 꺼져 있다. 상태를 비워 둔다.
            resetDwell(null);
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldown -= elapsed;
        }

        Player player = this.mob.level().getNearestPlayer(
                this.targeting.range(enter * EXIT_FACTOR), this.mob);
        if (player != this.dwellTarget) {
            resetDwell(player);
        }
        if (player == null) {
            return false;
        }

        // 각도도 거리와 같은 히스테리시스를 쓴다. 경계에서 몸을 흔들 때 dwell 이 리셋됐다
        // 차오르기를 반복하면 발동이 튄다.
        double front = AnimParams.SNIFF_FRONT_ANGLE.get();
        double toPlayer = Math.toDegrees(Math.atan2(player.getZ() - this.mob.getZ(),
                player.getX() - this.mob.getX())) - 90.0D;
        double frontAngle = Math.abs(wrap180(toPlayer - this.mob.yBodyRot));
        boolean frontOk;
        if (front >= 180.0D) {
            frontOk = true;                       // 제한 없음
        } else if (this.frontInside) {
            frontOk = frontAngle <= front * EXIT_FACTOR;
        } else {
            frontOk = frontAngle <= front;
        }
        this.frontInside = frontOk;

        double distance = this.mob.distanceTo(player);
        boolean near = this.inside ? distance <= enter * EXIT_FACTOR : distance <= enter;
        this.inside = near;

        if (!near || !frontOk || blocked()) {
            this.dwell = 0;
            return false;
        }
        this.dwell += elapsed;
        if (this.dwell < AnimParams.SNIFF_DWELL.get()) {
            return false;
        }
        if (this.cooldown > 0) {
            // 쿨다운 중에는 굴리지도 않는다. 굴려서 버리면 쿨다운이 풀리는 순간 성공이 밀려
            // 있다가 즉시 터져 규칙적으로 보인다 — 확률을 넣은 이유가 사라진다.
            this.rollTimer = 0.0D;
            return false;
        }
        this.rollTimer += elapsed;
        double interval = AnimParams.SNIFF_ROLL_INTERVAL.get();
        if (interval <= 0.0D || this.rollTimer < interval) {
            return false;
        }
        this.rollTimer -= interval;
        // 난수는 몹 자신의 것이다. 새 java.util.Random 을 만들지 않는다.
        return this.mob.getRandom().nextDouble() < AnimParams.SNIFF_ROLL_CHANCE.get();
    }

    @Override
    public boolean canContinueToUse() {
        return this.ticks >= 0 && this.ticks < AnimRegistry.SNIFF_LENGTH_TICKS && !blocked();
    }

    /**
     * 쿨다운은 <b>발동 시점</b>에 건다 — {@code IdleReaction} 과 같은 의미다. dwell 은 되돌리지
     * 않는다. 조건은 계속 충족돼 있으므로 다시 기다릴 이유가 없고, 재발동 간격은 쿨다운이 정한다.
     */
    @Override
    public void start() {
        this.ticks = 0;
        this.soundsFired = 0;
        this.cooldown = (int) Math.round(AnimParams.SNIFF_COOLDOWN.get());
        this.mob.level().broadcastEntityEvent(this.mob, WardenGirlEntity.EVENT_SNIFF);
    }

    @Override
    public void tick() {
        if (blocked()) {
            // 중단. 아직 안 난 소리를 취소하려면 카운터를 끝까지 민다 — 몸은 멈췄는데 소리만
            // 나는 그림을 만들지 않는다. 다음 canContinueToUse 에서 종료된다.
            this.soundsFired = SOUND_AT.length;
            this.ticks = (int) Math.ceil(AnimRegistry.SNIFF_LENGTH_TICKS);
            return;
        }
        this.ticks++;
        while (this.soundsFired < SOUND_AT.length && this.ticks >= SOUND_AT[this.soundsFired]) {
            double volume = AnimParams.SNIFF_SOUND_VOLUME.get()
                    * (this.soundsFired == 0 ? 1.0D : AnimParams.SNIFF_SOUND_VOLUME_2ND.get());
            // Entity.playSound -> Level.playSound(null, x, y, z, ...). 서버에서 null 은 "제외할
            // 플레이어 없음" 이므로 주변 전원에게 방송된다. 클라이언트가 스스로 내는 소리는 없다.
            this.mob.playSound(ModSounds.SNIFF.get(), (float) volume,
                    (float) AnimParams.SNIFF_SOUND_PITCH.get());
            this.soundsFired++;
        }
    }

    @Override
    public void stop() {
        this.ticks = -1;
        this.soundsFired = SOUND_AT.length;
    }
}
