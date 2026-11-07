package kfc.udp.client.anticheat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 안티치트 엔진 — 방장의 통합 서버에서 게스트 패킷을 하나하나 검사한다. 마인크래프트 클래스를 전혀 쓰지 않는다
 * (그래서 매핑·버전과 무관하게 단위 테스트로 돌린다). 마크와의 연결은 {@code AcHooks}·믹스인이 맡는다.
 * <p>
 * 구조는 상용 안티치트와 같다: 검사별 버퍼·VL·감쇠 → 문턱을 넘으면 방장 알림 / 되돌리기(셋백)·취소 / 강퇴.
 * 랙 보정은 핑-퐁 트랜잭션({@link Transactions})과 엔티티 위치 기록({@link EntityHistory})으로 한다.
 * <p>
 * 스레드: 모든 메서드는 서버 메인 스레드에서 부른다. 알림 큐만 다른 스레드가 읽어도 되게 동시성 큐다.
 */
public final class Engine {

    public static final Engine INSTANCE = new Engine();

    /** 알림 하나 — 방장 채팅·로그·서버 방 기록으로 나간다. */
    public record Alert(UUID uuid, String name, String check, Check.Category category, double vl, int flags,
                        String detail, boolean kick, long atMs) {}

    /** 패킷 하나 처리 결과 — 훅이 그대로 따른다. */
    public static final class Out {
        /** 이 패킷(공격·설치·채굴)을 처리하지 말 것. */
        public boolean cancel;
        /** 이 위치로 되돌릴 것(이동 패킷은 처리하지 말고 텔레포트). */
        public double[] setback;
        /** 지면 판정을 이 값으로 바꿀 것(노폴 방지). null이면 그대로. */
        public Boolean groundOverride;
        /** 강퇴 사유 검사 id — null이면 강퇴 안 함. */
        public String kick;
    }

    volatile AcConfig cfg = new AcConfig();
    final EntityHistory history = new EntityHistory();
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final ConcurrentLinkedQueue<Alert> alerts = new ConcurrentLinkedQueue<>();
    private final Map<UUID, String> pendingKicks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    long serverTick;
    /** 시계 — 테스트에서 바꿔 끼운다. */
    java.util.function.LongSupplier clockNs = System::nanoTime;
    private final long epochOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000L;

    long nowNs() { return clockNs.getAsLong(); }

    long nowMs() { return epochOffsetMs + clockNs.getAsLong() / 1_000_000L; }

    public void setConfig(AcConfig c) {
        this.cfg = c.copy();
    }

    public AcConfig config() {
        return cfg.copy();
    }

    boolean mitigate() {
        return cfg.action != AcConfig.Action.ALERT;
    }

    // ── 수명 ──

    public synchronized PlayerData join(UUID uuid, String name, int entityId) {
        PlayerData p = new PlayerData(uuid, name, entityId);
        p.serverTick = serverTick;
        players.put(uuid, p);
        return p;
    }

    public synchronized void quit(UUID uuid) {
        players.remove(uuid);
        pendingKicks.remove(uuid);
    }

    public synchronized void resetAll() {
        players.clear();
        history.clear();
        alerts.clear();
        pendingKicks.clear();
    }

    public synchronized PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    public synchronized List<PlayerData> all() {
        return new ArrayList<>(players.values());
    }

    // ── 서버 틱 ──

    /** 매 서버 틱 시작에 1번. */
    public synchronized void serverTick(long tick) {
        serverTick = tick;
        for (PlayerData p : players.values()) {
            p.serverTick = tick;
            World.onServerTick(p);
        }
        if (tick % 40 == 0) history.prune(tick);
    }

    /**
     * 방장 서버가 오래 멈췄다(자동 저장·청크 생성). 그동안 쌓였던 패킷이 한꺼번에 처리되므로 타이머 기준점을 전원 다시 잡는다.
     */
    public synchronized void onServerStall() {
        for (PlayerData p : players.values()) {
            p.timerLastNs = 0;
            p.uncertainTicks = Math.max(p.uncertainTicks, 2);
        }
    }

    /** 게스트 근처 엔티티의 이번 틱 히트박스 — 리치 랙 보정용. */
    public void recordEntity(int entityId, Box box) {
        history.record(entityId, serverTick, box);
    }

    /** 이번 틱 끝에 이 게스트에게 보낼 핑 id. 0이면 보내지 않는다(검사 꺼짐). */
    public synchronized int pingFor(UUID uuid) {
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return 0;
        return p.tx.next(serverTick, nowNs());
    }

    public static boolean isOurPing(int id) {
        return Transactions.ours(id);
    }

    public synchronized void onPong(UUID uuid, int id) {
        PlayerData p = players.get(uuid);
        if (p == null) return;
        long t = p.tx.pong(id, nowNs());
        if (t < 0) return;
        for (PlayerData.Velocity v : p.velocities) if (v.sentTick <= t) v.confirmed = true;
    }

    // ── 이동 ──

    public synchronized Out onMove(UUID uuid, MoveInput m, MoveEnv env) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        long now = nowMs();
        trackInput(p, env);
        Movement.onMove(this, p, m, env, now, out);
        if (!p.tickEndSeen) {
            // 틱 끝 패킷이 없는 버전은 이동 패킷을 틱 경계로 쓴다.
            Combat.resolvePending(this, p, now, out);
            Combat.onClientTickEnd(this, p, now, out);
            World.onClientTickEnd(p);
            Packets.onClientTickEnd(p);
        } else if (!m.hasRot()) {
            Combat.resolvePending(this, p, now, out);
        }
        collectKick(p, out);
        return out;
    }

    private void trackInput(PlayerData p, MoveEnv env) {
        boolean moving = env.hasInput && (env.forward || env.backward || env.left || env.right || env.jumpKey);
        p.inputMovingTicks = moving ? p.inputMovingTicks + 1 : 0;
        p.shiftHeldTicks = env.hasInput && env.shiftKey ? p.shiftHeldTicks + 1 : 0;
    }

    /** ServerboundClientTickEndPacket(1.21.2+). */
    public synchronized Out onClientTickEnd(UUID uuid) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        long now = nowMs();
        Movement.onClientTick(this, p, nowNs(), out);
        Combat.resolvePending(this, p, now, out);
        Combat.onClientTickEnd(this, p, now, out);
        World.onClientTickEnd(p);
        Packets.onClientTickEnd(p);
        collectKick(p, out);
        return out;
    }

    /** 서버가 이 게스트에게 넉백(속도) 패킷을 보냄. additive=폭발처럼 더하기. */
    public synchronized void onVelocity(UUID uuid, double x, double y, double z, boolean additive) {
        PlayerData p = players.get(uuid);
        if (p == null) return;
        p.velocities.addLast(new PlayerData.Velocity(x, y, z, additive, serverTick));
    }

    /** 서버가 텔레포트(셋백 포함)를 보냄 — 확인 전까지 이동 기준점을 버린다. */
    public synchronized void onTeleport(UUID uuid, double x, double y, double z) {
        PlayerData p = players.get(uuid);
        if (p == null) return;
        p.hasPos = false;
        p.lastDeltaKnown = false;
        p.uncertainTicks = Math.max(p.uncertainTicks, 3);
        p.velocities.clear();
        p.setLegit(x, y, z);
    }

    // ── 전투 ──

    public synchronized Out onAttack(UUID uuid, Combat.Attack a) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        out.cancel = Combat.onAttack(this, p, a, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    public synchronized Out onSwing(UUID uuid) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        Combat.onSwing(this, p, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    // ── 월드 ──

    public synchronized Out onPlace(UUID uuid, World.Place pl) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        out.cancel = World.onPlace(this, p, pl, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    public synchronized Out onDig(UUID uuid, World.Dig d) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        out.cancel = World.onDig(this, p, d, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    public synchronized Out onContainerClick(UUID uuid) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        World.onContainerClick(this, p, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    // ── 패킷 ──

    public synchronized Out onAbilities(UUID uuid, boolean flying, boolean mayFly) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        Packets.onAbilities(this, p, flying, mayFly, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    public synchronized Out onSprint(UUID uuid, boolean start, float food, boolean swimming, boolean creativeOrFly) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        Packets.onSprint(this, p, start, food, p.shiftHeldTicks > 0, swimming, creativeOrFly, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    public synchronized Out onSelfInteract(UUID uuid) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        Packets.onSelfInteract(this, p, nowMs(), out);
        out.cancel = true;
        collectKick(p, out);
        return out;
    }

    public synchronized Out onSlotChange(UUID uuid, boolean sameSlot) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || !cfg.enabled) return out;
        Packets.onSlotChange(this, p, sameSlot, nowMs(), out);
        collectKick(p, out);
        return out;
    }

    /** 수신 패킷마다 — 플러드면 true(훅이 버림). 가벼워야 해서 잠금 없이 플레이어별 큐만 만진다. */
    public Out onPacket(UUID uuid) {
        Out out = new Out();
        PlayerData p;
        synchronized (this) { p = players.get(uuid); }
        if (p == null || !cfg.enabled) return out;
        synchronized (p) {
            out.cancel = Packets.onAnyPacket(this, p, nowMs(), out);
        }
        collectKick(p, out);
        return out;
    }

    // ── 무결성 ──

    /** 접속 직후 게스트에게 보낼 무결성 요청 nonce. */
    public synchronized String integrityChallenge(UUID uuid) {
        PlayerData p = players.get(uuid);
        if (p == null) return null;
        byte[] b = new byte[16];
        random.nextBytes(b);
        p.integrityNonce = HexFormat.of().formatHex(b);
        p.integrityAskedMs = nowMs();
        p.integrityAnswered = false;
        return p.integrityNonce;
    }

    /**
     * 게스트의 무결성 응답.
     * @param build 게스트가 보낸 이 모드 jar의 SHA-256
     * @param hostBuild 방장 자신의 jar SHA-256(같은 판이면 같아야 한다)
     */
    public synchronized Out onIntegrity(UUID uuid, String nonce, String version, String build, List<String> mods,
                                        String hostVersion, String hostBuild) {
        Out out = new Out();
        PlayerData p = players.get(uuid);
        if (p == null || p.integrityNonce == null || !cfg.categoryOn(Check.Category.INTEGRITY)) return out;
        if (!p.integrityNonce.equals(nonce) || p.integrityAnswered) return out; // 재전송·위조된 응답은 무시
        p.integrityAnswered = true;
        long now = nowMs();
        for (Integrity.Finding f : Integrity.evaluate(mods)) {
            switch (f.severity()) {
                case CHEAT, XRAY -> {
                    Check c = p.check("integrity");
                    c.failNow(now, c.kickVl); // 바로 강퇴 문턱
                    flag(p, "integrity", (f.severity() == Integrity.Severity.CHEAT ? "cheat mod: " : "x-ray mod: ") + f.modId(), out);
                }
                case ADVANTAGE -> alertOnly(p, "integrity", "mod: " + f.modId());
            }
        }
        if (version != null && version.equals(hostVersion) && build != null && hostBuild != null
                && !"dev".equals(build) && !"dev".equals(hostBuild) && !build.equals(hostBuild)) {
            alertOnly(p, "integrity", "modified instant-p2p jar");
        }
        collectKick(p, out);
        return out;
    }

    /** 무결성 응답이 15초 안에 안 온 게스트 — 구버전이거나 응답을 막은 클라. 알림만. */
    public synchronized void checkIntegrityTimeouts() {
        long now = nowMs();
        for (PlayerData p : players.values()) {
            if (p.integrityNonce != null && !p.integrityAnswered && now - p.integrityAskedMs > 15_000) {
                p.integrityAnswered = true;
                if (cfg.categoryOn(Check.Category.INTEGRITY)) alertOnly(p, "integrity", "no integrity response (old version?)");
            }
        }
    }

    // ── 판정 → 조치 ──

    /** 검사 하나가 위반을 기록했을 때 — 알림 문턱·강퇴 문턱을 본다. */
    void flag(PlayerData p, String checkId, String detail, Out out) {
        Check c = p.check(checkId);
        if (c == null || !cfg.categoryOn(c.category)) return;
        double scale = cfg.thresholdScale;
        long now = nowMs();
        boolean kick = cfg.action == AcConfig.Action.KICK && c.vl >= c.kickVl * scale;
        if (kick) {
            out.kick = checkId;
            pendingKicks.put(p.uuid, checkId);
        }
        if (kick || (c.vl >= c.alertVl * scale && now - c.lastAlertMs > 2000)) {
            c.lastAlertMs = now;
            alerts.add(new Alert(p.uuid, p.name, checkId, c.category, c.vl, c.flags, detail, kick, now));
            while (alerts.size() > 500) alerts.poll();
        }
    }

    private void alertOnly(PlayerData p, String checkId, String detail) {
        Check c = p.check(checkId);
        alerts.add(new Alert(p.uuid, p.name, checkId, c.category, c.vl, c.flags, detail, false, nowMs()));
    }

    private void collectKick(PlayerData p, Out out) {
        if (out.kick == null) {
            String k = pendingKicks.remove(p.uuid);
            if (k != null) out.kick = k;
        } else {
            pendingKicks.remove(p.uuid);
        }
    }

    /** 패킷 맥락 밖(회전 처리 등)에서 난 위반용 임시 결과. */
    Out scratchOut() {
        return new Out();
    }

    void flushScratch(PlayerData p, Out o) {
        if (o.kick != null) pendingKicks.put(p.uuid, o.kick);
    }

    public void requestKick(UUID uuid, String checkId) {
        pendingKicks.put(uuid, checkId);
    }

    /** 다음 틱에 강퇴할 사람(패킷 밖에서 결정된 강퇴). */
    public String takeKick(UUID uuid) {
        return pendingKicks.remove(uuid);
    }

    public List<Alert> drainAlerts() {
        List<Alert> l = new ArrayList<>();
        Alert a;
        while ((a = alerts.poll()) != null) l.add(a);
        return l;
    }
}
