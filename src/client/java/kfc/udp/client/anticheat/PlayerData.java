package kfc.udp.client.anticheat;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 게스트 한 명의 안티치트 상태. 서버 메인 스레드에서만 만진다(퐁 처리도 메인 스레드로 넘겨 온다). */
public final class PlayerData {

    /** 검사 원형 — id, 분류, 셋백 가능, 버퍼 한도, 버퍼 감소, 알림 VL, 강퇴 VL, VL 반감기. */
    private static final Check[] TEMPLATES = {
            new Check("speed", Check.Category.MOVEMENT, true, 0.12, 0.004, 4, 45, 60_000),
            new Check("fly", Check.Category.MOVEMENT, true, 0.10, 0.004, 4, 45, 60_000),
            new Check("groundspoof", Check.Category.MOVEMENT, true, 3, 0.25, 4, 35, 60_000),
            new Check("timer", Check.Category.MOVEMENT, true, 0, 0, 3, 25, 90_000),
            new Check("elytra", Check.Category.MOVEMENT, true, 0.6, 0.01, 4, 40, 60_000),
            new Check("velocity", Check.Category.MOVEMENT, false, 2, 0.5, 3, 25, 120_000),
            new Check("reach", Check.Category.COMBAT, false, 1, 0.25, 3, 25, 120_000),
            new Check("hitbox", Check.Category.COMBAT, false, 2, 0.25, 3, 25, 120_000),
            new Check("killaura", Check.Category.COMBAT, false, 1, 0.1, 2, 15, 180_000),
            new Check("aim", Check.Category.COMBAT, false, 5, 0.5, 6, 1e9, 300_000),
            new Check("autoclicker", Check.Category.COMBAT, false, 2, 0.5, 3, 25, 180_000),
            new Check("interact", Check.Category.WORLD, false, 1, 0.1, 3, 25, 120_000),
            new Check("scaffold", Check.Category.WORLD, false, 2, 0.25, 3, 25, 120_000),
            new Check("fastbreak", Check.Category.WORLD, false, 1, 0.25, 3, 20, 120_000),
            new Check("nuker", Check.Category.WORLD, false, 0, 0, 2, 10, 180_000),
            new Check("xray", Check.Category.WORLD, false, 0, 0, 1, 1e9, 600_000),
            new Check("inventory", Check.Category.WORLD, false, 2, 0.25, 3, 25, 120_000),
            new Check("badpackets", Check.Category.PACKET, false, 0, 0, 1, 10, 300_000),
            new Check("integrity", Check.Category.INTEGRITY, false, 0, 0, 1, 5, 3_600_000),
    };

    public final UUID uuid;
    public final String name;
    public final int entityId;
    final Map<String, Check> checks = new LinkedHashMap<>();
    final Transactions tx = new Transactions();

    // ── 이동 ──
    boolean hasPos;
    double x, y, z;
    float yaw, pitch, prevYaw, prevPitch;
    boolean rotKnown;
    /** 지난 틱 실제 이동량. */
    double lastDx, lastDy, lastDz;
    boolean lastDeltaKnown;
    /** 지난 틱 끝 판정된 지면 접촉(클라 주장 + 서버 확인). */
    boolean lastGround;
    boolean lastHorizontalCollision;
    boolean lastVerticalCollision;
    MoveEnv lastEnv;
    /** 정밀 예측을 쉬는 남은 틱 — 텔레포트·리스폰·예외 상태 직후 속도를 모르는 동안. */
    int uncertainTicks = 20;
    /** 겉날개·삼지창·탈것 같은 상태가 끝난 뒤 넓은 검사만 하는 틱. */
    int graceTicks;
    /** 마지막으로 정상 판정된 위치(셋백 지점). */
    double legitX, legitY, legitZ;
    boolean legitKnown;
    int setbacksThisSecond;
    long setbackWindowMs;

    /** 지난 틱 시작 시점(=그 전 틱 끝)의 지면 접촉 — 마찰 계산용. */
    boolean groundStartOfLast;
    /** 마지막 위치 패킷 이후 클라 틱 수(틱 끝 패킷으로 셈). */
    int ticksSinceMove;
    long lastMoveNs;
    /** 착지 직전 수직 속도(슬라임 튕김 계산). */
    double lastPreLandVy;
    double lastOffset;
    int sneakTicks, useTicks;
    int airHoverTicks, elytraHoverTicks;
    /** 최근 이동 속도·점프 강화 값 기록 — 효과가 서버에서 먼저 끝나도 핑만큼은 예전 값을 인정. */
    final ArrayDeque<double[]> speedHist = new ArrayDeque<>();
    final ArrayDeque<double[]> jumpHist = new ArrayDeque<>();
    final ArrayDeque<double[]> slowFallHist = new ArrayDeque<>();
    boolean stepThisTick;
    double maxCThisTick;

    double recentMax(ArrayDeque<double[]> hist, double now, long nowMs, long windowMs) {
        hist.addLast(new double[]{nowMs, now});
        while (!hist.isEmpty() && nowMs - hist.peekFirst()[0] > windowMs) hist.pollFirst();
        while (hist.size() > 200) hist.pollFirst();
        double m = now;
        for (double[] h : hist) m = Math.max(m, h[1]);
        return m;
    }

    /** 셋백은 1초에 5번까지 — 오탐으로 끝없이 되돌리는 고리를 막는다. */
    boolean allowSetback(long nowMs) {
        if (nowMs - setbackWindowMs > 1000) { setbackWindowMs = nowMs; setbacksThisSecond = 0; }
        return ++setbacksThisSecond <= 5;
    }

    void expireVelocities() {
        while (velocities.size() > 8) velocities.pollFirst();
        velocities.removeIf(v -> serverTick - v.sentTick > 100); // 5초 넘은 건 버린다
    }

    // 넉백: 서버가 보냈고 아직 클라 이동에 안 쓰인 것
    static final class Velocity {
        final double x, y, z;
        final boolean additive;
        final long sentTick;
        boolean confirmed;
        int movesSinceConfirm;
        Velocity(double x, double y, double z, boolean additive, long sentTick) {
            this.x = x; this.y = y; this.z = z; this.additive = additive; this.sentTick = sentTick;
        }
    }
    final ArrayDeque<Velocity> velocities = new ArrayDeque<>();

    // 타이머(클라 틱 수 vs 실제 시간)
    long clientTicks;
    long timerLastNs;
    double timerBalanceMs;
    boolean tickEndSeen;
    int movePacketsSinceTickEnd;
    long movesWindowStartNs;
    int movesInWindow;

    // 겉날개 에너지
    double elytraEnergy = Double.NaN;

    // ── 전투 ──
    long serverTick;
    int lastTargetThisTick = Integer.MIN_VALUE;
    final ArrayDeque<Long> swingTimesMs = new ArrayDeque<>();
    final ArrayDeque<Double> aimErrors = new ArrayDeque<>();
    long combatUntilMs;
    /** 다음 회전 패킷이 오면 판정할, 공격/설치 당시의 지연 판정. */
    PendingRay pendingRay;
    int gcdBadSamples, gcdSamples;
    double lastYawDelta, lastPitchDelta;

    Box pendingRayTarget;
    boolean attackedThisClientTick, swungThisClientTick, snapBeforeAttack;
    int attacksSinceSwing;
    Box lastTargetBox;
    long lastAttackMs;
    long lastPitchSample;

    record PendingRay(String kind, Box[] boxes, double eyeX, double eyeY, double eyeY2, double eyeZ,
                      float prevYaw, float prevPitch, double maxDist, String detail) {}

    // ── 월드 ──
    long breakStartClientTick = -1;
    long breakStartServerTick = -1;
    long breakPosKey = Long.MIN_VALUE;
    double breakProgressPerTick;
    int breakStartsThisTick;
    final ArrayDeque<Long> placeTimesMs = new ArrayDeque<>();
    final ArrayDeque<Long> clickTimesMs = new ArrayDeque<>();
    int oresTotal, stoneMined;
    long xrayAlertMs;
    int placesThisClientTick, instantBreaksThisClientTick;
    int inputMovingTicks, shiftHeldTicks;

    // ── 패킷 ──
    final ArrayDeque<Long> packetTimesMs = new ArrayDeque<>();
    int slotChangesThisTick, sameSlotChanges;

    // ── 무결성 ──
    boolean integrityAnswered;
    long integrityAskedMs;
    String integrityNonce;

    PlayerData(UUID uuid, String name, int entityId) {
        this.uuid = uuid;
        this.name = name;
        this.entityId = entityId;
        for (Check t : TEMPLATES) checks.put(t.id, t.copy());
    }

    Check check(String id) {
        return checks.get(id);
    }

    public Map<String, Check> checks() { return checks; }

    public double rttMs() { return tx.rttMs(); }

    /** 총합 점수(화면 표시용) — 알림 문턱 대비 비율의 합. */
    public double suspicion() {
        double s = 0;
        for (Check c : checks.values()) if (c.kickVl < 1e8) s += c.vl / c.kickVl;
        return s;
    }

    void setLegit(double lx, double ly, double lz) {
        legitX = lx; legitY = ly; legitZ = lz; legitKnown = true;
    }
}
