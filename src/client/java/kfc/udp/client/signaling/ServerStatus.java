package kfc.udp.client.signaling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 방 목록 화면 오른쪽 아래의 "서버 상태" — 지금 고른 서버(공식/사설)가 살아 있는지, 응답 속도, 공개 방 수.
 * <p>
 * 화면이 열려 있는 동안 {@link #poll()}을 매 틱 불러도 된다 — 실제 요청은 {@link #INTERVAL_MS}마다
 * 한 번, 백그라운드 스레드에서만 나간다. 사설 서버는 {@code /api/v1/status}(방·접속자 수)를 주고,
 * 공식 서버처럼 그 경로가 없는 서버는 "응답만 오면 온라인"으로 친다(404도 서버는 살아 있다는 뜻).
 */
public final class ServerStatus {

    public enum State { CHECKING, ONLINE, OFFLINE }

    /** 화면에 그릴 스냅샷. rooms/players는 모르면 -1. */
    public record Snapshot(State state, long pingMs, int rooms, int players) {}

    private static final long INTERVAL_MS = 10_000;
    private static final HttpClient CLIENT = Http.CLIENT; // 공용(Http 주석)

    private static volatile Snapshot current = new Snapshot(State.CHECKING, -1, -1, -1);
    private static volatile long lastStartMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private ServerStatus() {}

    public static Snapshot current() {
        return current;
    }

    /** 서버를 바꿨을 때 — 이전 서버 결과를 지우고 바로 다시 잰다. */
    public static void reset() {
        current = new Snapshot(State.CHECKING, -1, -1, -1);
        lastStartMs = 0;
    }

    /** 필요하면(주기가 됐으면) 백그라운드로 한 번 잰다. 블로킹하지 않는다. */
    public static void poll() {
        long now = System.currentTimeMillis();
        if (now - lastStartMs < INTERVAL_MS || !inFlight.compareAndSet(false, true)) return;
        lastStartMs = now;
        String url = P2PConfig.signalingHttpUrl() + "/api/v1/status";
        Thread t = new Thread(() -> {
            try {
                current = measure(url);
            } finally {
                inFlight.set(false);
            }
        }, "instant-p2p-server-status");
        t.setDaemon(true);
        t.start();
    }

    private static Snapshot measure(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            long t0 = System.nanoTime();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (res.statusCode() >= 500) return new Snapshot(State.OFFLINE, -1, -1, -1);
            int rooms = -1, players = -1;
            if (res.statusCode() == 200) {
                rooms = parseInt(VillasMsg.field(res.body(), "publicRooms"));
                players = parseInt(VillasMsg.field(res.body(), "players"));
            }
            return new Snapshot(State.ONLINE, ms, rooms, players);
        } catch (Exception e) {
            return new Snapshot(State.OFFLINE, -1, -1, -1);
        }
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
