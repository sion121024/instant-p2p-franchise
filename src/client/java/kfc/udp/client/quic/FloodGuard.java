package kfc.udp.client.quic;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 방장 UDP 포트 홍수 방어(1.5~) — <b>모르는 주소</b>에서 오는 패킷만 센다.
 * <p>
 * 방장의 UDP 주소는 접속자 전원(공개 방이면 누구나)에게 후보로 나가므로, 그 포트로 아무나 패킷을 퍼부을 수
 * 있다. 아는 주소(시그널링으로 받은 후보·STUN/TURN 서버·확인된 상대 — {@code IceSocket.markKnown})는 제한 없이
 * 통과시키고, 모르는 주소는
 * <ul>
 *   <li>한 주소당 초당 {@link #PER_IP_PPS}개까지만 받는다.</li>
 *   <li>모르는 주소 전체가 초당 {@link #UNKNOWN_PPS}개를 넘으면 {@link #DEFENSE_MS} 동안 모르는 주소를 전부
 *       버린다(방어 모드). 이미 붙은 접속자는 아는 주소라 영향이 없고, 새 접속자만 잠시 못 들어온다.</li>
 * </ul>
 * 방어 모드를 켜고/끌 때 {@code onChange} 로 알린다 — 방장 채팅에 안내가 뜬다(KfcudpClient.onFloodDefense).
 */
final class FloodGuard {

    static final int UNKNOWN_PPS = 300;
    static final int PER_IP_PPS = 60;
    static final long DEFENSE_MS = 300_000;
    /** 1초 창 안에서 기억하는 모르는 주소 수 상한 — 넘으면 새 주소는 그냥 버린다(메모리 보호). */
    private static final int MAX_IPS = 4096;

    private final Consumer<Boolean> onChange;
    private long windowStart;
    private long unknownInWindow;
    private long defenseUntil;
    private boolean announced;
    private final Map<String, int[]> perIp = new HashMap<>();

    FloodGuard(Consumer<Boolean> onChange) {
        this.onChange = onChange;
    }

    /** @return 받아도 되면 true */
    synchronized boolean admit(String ip, boolean known, long now) {
        if (announced && now >= defenseUntil) {
            announced = false;
            onChange.accept(false);
        }
        if (now - windowStart >= 1_000) {
            windowStart = now;
            unknownInWindow = 0;
            perIp.clear();
        }
        if (known) return true;
        if (++unknownInWindow > UNKNOWN_PPS) {
            defenseUntil = now + DEFENSE_MS;
            if (!announced) {
                announced = true;
                onChange.accept(true);
            }
        }
        if (now < defenseUntil) return false;
        int[] count = perIp.get(ip);
        if (count == null) {
            if (perIp.size() >= MAX_IPS) return false;
            count = new int[1];
            perIp.put(ip, count);
        }
        return ++count[0] <= PER_IP_PPS;
    }

    synchronized boolean defending(long now) {
        return now < defenseUntil;
    }
}
