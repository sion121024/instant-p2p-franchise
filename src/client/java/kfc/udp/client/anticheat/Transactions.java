package kfc.udp.client.anticheat;

import java.util.ArrayDeque;

/**
 * 핑-퐁 트랜잭션 — 상용 안티치트(Grim)가 랙 보정에 쓰는 방식.
 * <p>
 * 방장 서버는 게스트에게 매 서버 틱 끝마다 {@code ClientboundPingPacket(id)}을 보낸다. 클라는 받은 패킷을
 * 순서대로 처리하고 핑을 처리하는 순간 퐁을 돌려준다. 그래서 퐁(id)이 왔다는 건 "그 틱까지 서버가 보낸 것
 * (엔티티 위치·넉백·텔레포트)을 클라가 전부 처리했다"는 확실한 증거다. 이걸로
 * <ul>
 *   <li>리치 검사 — 클라가 실제로 보고 있었을 수 있는 대상 위치의 범위(어느 틱의 위치인지)</li>
 *   <li>넉백 검사 — 넉백을 확실히 받은 시점</li>
 *   <li>핑(RTT) — 서버 틱 단위가 아니라 실제 왕복 시간</li>
 * </ul>
 * 를 정확히 안다. id는 바닐라가 쓰지 않는 음수 구간을 쓴다.
 */
public final class Transactions {

    /** 우리가 쓰는 핑 id 범위의 시작 — 바닐라·다른 모드와 안 겹치게 아주 작은 음수부터. */
    public static final int BASE_ID = Integer.MIN_VALUE + 0x4b464300; // "KFC" 표식

    private record Sent(int id, long serverTick, long sentNs) {}

    private final ArrayDeque<Sent> inFlight = new ArrayDeque<>();
    private int nextId = BASE_ID;
    /** 클라가 처리를 확인한 가장 최근 서버 틱. */
    long confirmedTick = -1;
    /** 가장 최근에 보낸 핑의 서버 틱. */
    long lastSentTick = -1;
    double rttMs = 100;
    long lastPongNs;
    int lostOrIgnored;

    /** 이번 틱 끝에 보낼 핑 id를 받는다. */
    public int next(long serverTick, long nowNs) {
        int id = nextId++;
        if (nextId >= BASE_ID + 0x00100000) nextId = BASE_ID; // 100만 개마다 되감기
        inFlight.addLast(new Sent(id, serverTick, nowNs));
        lastSentTick = serverTick;
        // 클라가 퐁을 안 돌려주면(구버전 모드·핵 클라가 무시) 무한히 쌓이지 않게 자른다.
        while (inFlight.size() > 600) {
            inFlight.pollFirst();
            lostOrIgnored++;
        }
        return id;
    }

    public static boolean ours(int id) {
        return id >= BASE_ID && id < BASE_ID + 0x00100000;
    }

    /** 퐁 수신. @return 확인된 서버 틱(모르는 id면 -1). */
    public long pong(int id, long nowNs) {
        // 순서대로 오므로 앞에서부터 버린다 — 중간 것이 빠졌으면(퐁 누락) 그것도 확인된 것으로 친다.
        while (!inFlight.isEmpty()) {
            Sent s = inFlight.pollFirst();
            if (s.id == id) {
                confirmedTick = Math.max(confirmedTick, s.serverTick);
                double sample = (nowNs - s.sentNs) / 1e6;
                rttMs = rttMs * 0.8 + sample * 0.2;
                lastPongNs = nowNs;
                return s.serverTick;
            }
        }
        return -1;
    }

    /** 아직 퐁이 안 온 가장 오래된 핑이 얼마나 오래됐는지(ms) — 클라가 패킷 처리를 멈춘 시간. */
    public double oldestPendingMs(long nowNs) {
        Sent s = inFlight.peekFirst();
        return s == null ? 0 : (nowNs - s.sentNs) / 1e6;
    }

    public double rttMs() { return rttMs; }

    public long confirmedTick() { return confirmedTick; }

    public long lastSentTick() { return lastSentTick; }

    /** 퐁이 한 번이라도 왔는지 — 안 왔으면 트랜잭션 기반 검사는 넓은 범위로 대신한다. */
    public boolean working() { return confirmedTick >= 0; }
}
