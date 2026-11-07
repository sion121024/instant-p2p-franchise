package kfc.udp.client.signaling;

/**
 * 방 입장·방 열기가 서버에서 거절된 이유를 잠깐 들고 있는다.
 * <p>
 * 입장 실패는 QUIC 스레드에서 알게 되지만, 화면에는 마인크래프트가 로컬 연결이 끊긴 것만 보고 "연결 끊김"만
 * 띄운다. 그래서 여기 이유를 남겨 두고, FranchiseHud가 그 화면이 뜨면 이유(밴이면 밴 화면)를 위에 띄운다.
 */
public final class ConnectFailure {

    public record Pending(String reason, boolean banned, boolean host) {}

    private static final long MAX_AGE_MS = 30_000;

    private static volatile Pending pending;
    private static volatile long atMs;

    private ConnectFailure() {}

    /** @param banned 밴 때문에 거절됐는지(기기·계정·IP) — 그러면 밴 상태를 바로 다시 묻는다. */
    public static void record(String reason, boolean banned, boolean host) {
        pending = new Pending(reason, banned, host);
        atMs = System.currentTimeMillis();
        if (banned) BanStatus.refreshAsync();
    }

    /** 30초 안에 남은 이유를 한 번만 꺼낸다. */
    public static Pending take() {
        Pending p = pending;
        if (p == null) return null;
        pending = null;
        return System.currentTimeMillis() - atMs <= MAX_AGE_MS ? p : null;
    }

    /** 꺼내지 않고 보기. */
    public static Pending peek() {
        return hasPending() ? pending : null;
    }

    public static boolean hasPending() {
        return pending != null && System.currentTimeMillis() - atMs <= MAX_AGE_MS;
    }

    public static boolean isBanError(String serverError) {
        return serverError != null && serverError.endsWith("-banned");
    }
}
