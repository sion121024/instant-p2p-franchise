package kfc.udp.client.anticheat;

/**
 * 검사 하나의 위반 점수(VL) 관리 — 상용 안티치트(Grim·Vulcan 류)와 같은 "버퍼 + VL + 감쇠" 구조.
 * <p>
 * <b>버퍼</b>: 한 번 튄 값은 바로 위반으로 치지 않고 버퍼에 쌓는다. 버퍼가 {@link #bufferLimit}를 넘을
 * 때만 VL을 올린다. 정상 틱이 오면 버퍼가 조금씩 빠진다 — 랙·패킷 뭉침 한두 번으로는 안 걸린다.
 * <br><b>VL</b>: 실제 "적발 횟수". 시간이 지나면 반감기로 줄어든다.
 * <br><b>조치</b>: VL이 알림/셋백/강퇴 문턱을 넘으면 {@link Engine}이 그 조치를 고른다. 문턱은 민감도에 따라 배율이 붙는다.
 * <p>
 * 마인크래프트 클래스를 쓰지 않는다 — 단위 테스트에서 그대로 돌린다.
 */
public final class Check {

    public enum Category { MOVEMENT, COMBAT, WORLD, PACKET, INTEGRITY }

    public final String id;
    public final Category category;
    /** 이 검사가 셋백(되돌리기)을 할 수 있는 이동 검사인지. */
    public final boolean canSetback;
    final double bufferLimit;
    final double bufferDecay;
    final double alertVl;
    final double kickVl;
    final long halfLifeMs;

    double buffer;
    double vl;
    long lastDecayMs;
    long lastAlertMs;
    int flags;

    Check(String id, Category category, boolean canSetback, double bufferLimit, double bufferDecay,
          double alertVl, double kickVl, long halfLifeMs) {
        this.id = id;
        this.category = category;
        this.canSetback = canSetback;
        this.bufferLimit = bufferLimit;
        this.bufferDecay = bufferDecay;
        this.alertVl = alertVl;
        this.kickVl = kickVl;
        this.halfLifeMs = halfLifeMs;
    }

    Check copy() {
        return new Check(id, category, canSetback, bufferLimit, bufferDecay, alertVl, kickVl, halfLifeMs);
    }

    void decay(long now) {
        if (lastDecayMs == 0) { lastDecayMs = now; return; }
        long dt = now - lastDecayMs;
        if (dt <= 0) return;
        vl *= Math.pow(0.5, dt / (double) halfLifeMs);
        if (vl < 0.01) vl = 0;
        lastDecayMs = now;
    }

    /** 정상 틱 — 버퍼를 조금 뺀다. */
    void reward() {
        buffer = Math.max(0, buffer - bufferDecay);
    }

    /**
     * 의심 값 하나를 넣는다. 버퍼를 넘었으면 VL을 {@code weight}만큼 올리고 true.
     * @param amount 버퍼에 더할 양(대개 1, 이동 검사는 벗어난 거리에 비례)
     */
    boolean fail(long now, double amount, double weight) {
        decay(now);
        buffer += amount;
        if (buffer <= bufferLimit) return false;
        buffer = Math.min(buffer, bufferLimit * 2 + 1);
        vl += weight;
        flags++;
        return true;
    }

    /** 버퍼 없이 바로 VL — "사람이 절대 못 하는" 확실한 값(각도 90도 초과 등). */
    boolean failNow(long now, double weight) {
        decay(now);
        vl += weight;
        flags++;
        return true;
    }

    public double vl() { return vl; }

    public int flags() { return flags; }
}
