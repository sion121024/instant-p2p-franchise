package kfc.udp.client.signaling;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "지금 이 기기·IP·계정이 중계 서버에서 막혀 있는지" — 방 목록·방 만들기 화면에서 확인해서, 막혀 있으면
 * 화면 가운데에 빨간 안내(BannedScreen)를 띄우고 아무것도 못 하게 한다.
 * <p>
 * 서버 {@code /api/v1/ban-status}(mc-relay-server/banstatus.go)에 기기 지문·계정 UUID·게시 토큰을 보내면
 * 막힘 여부·종류(hwid/ip/account)·사유·해제 시각을 준다. IP가 막힌 사람도 이 경로만은 답을 받는다.
 * 확인에 실패하면(서버가 이 기능이 없는 공식 서버 등) "안 막힘"으로 둔다 — 멀쩡한 사람을 막는 쪽이 더 나쁘다.
 */
public final class BanStatus {

    /** 화면에 보여 줄 값. untilMs 0 = 영구. */
    public record Info(boolean banned, String kind, String reason, long untilMs) {
        /** 남은 밀리초, 영구면 -1. */
        public long remainingMs() {
            return untilMs <= 0 ? -1 : Math.max(0, untilMs - System.currentTimeMillis());
        }
    }

    private static final Info CLEAR = new Info(false, "", "", 0);
    /** 밴 상태일 땐 풀리는 걸 빨리 알아야 해서 15초, 아닐 땐 60초(F3.4 경량화 — 방 목록을 켜 둔
     * 사람 전원이 15초마다 묻던 요청을 1/4로). 화면을 열 때·서버를 바꿀 때는 refreshAsync로 바로 묻는다. */
    private static final long REFRESH_BANNED_MS = 15_000;
    private static final long REFRESH_CLEAR_MS = 60_000;
    private static final HttpClient CLIENT = Http.CLIENT; // 공용(Http 주석)

    private static volatile Info current = CLEAR;
    private static volatile long lastStartMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private BanStatus() {}

    public static Info current() {
        Info i = current;
        // 시간제 밴이 끝났으면 서버에 다시 묻기 전이라도 바로 풀린 걸로 본다.
        if (i.banned() && i.untilMs() > 0 && i.remainingMs() == 0) return CLEAR;
        return i;
    }

    public static boolean isBanned() {
        return current().banned();
    }

    /** 필요하면(주기가 됐으면) 백그라운드로 다시 확인. 매 틱 불러도 된다. */
    public static void poll() {
        long every = current.banned() ? REFRESH_BANNED_MS : REFRESH_CLEAR_MS;
        if (System.currentTimeMillis() - lastStartMs < every) return;
        refreshAsync();
    }

    /** 바로 다시 확인(서버를 바꿨을 때, 화면을 열 때). */
    public static void refreshAsync() {
        if (!inFlight.compareAndSet(false, true)) return;
        lastStartMs = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                current = fetch();
            } finally {
                inFlight.set(false);
            }
        }, "instant-p2p-ban-status");
        t.setDaemon(true);
        t.start();
    }

    /** 서버를 바꿨을 때 — 이전 서버 결과는 버린다. */
    public static void reset() {
        current = CLEAR;
        lastStartMs = 0;
    }

    private static Info fetch() {
        if (!P2PConfig.franchiseConsented()) return CLEAR; // 가맹점 동의 전에는 묻지 않는다(기기·계정 정보를 보내게 되므로)
        try {
            StringBuilder url = new StringBuilder(P2PConfig.signalingHttpUrl()).append("/api/v1/ban-status?x=1")
                    .append(HardwareId.query());
            String token = MojangAuth.tokenOrNull();
            if (token != null) url.append("&token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
            String uuid = MojangAuth.sessionUuidOrNull();
            if (uuid != null) url.append("&uuid=").append(URLEncoder.encode(uuid, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return CLEAR;
            String body = res.body();
            if (!"true".equals(VillasMsg.field(body, "banned"))) return CLEAR;
            long until = 0;
            try {
                until = Long.parseLong(VillasMsg.field(body, "until"));
            } catch (Exception ignored) {
            }
            String kind = VillasMsg.field(body, "kind");
            String reason = VillasMsg.field(body, "reason");
            return new Info(true, kind == null ? "" : kind, reason == null ? "" : reason, until);
        } catch (Exception e) {
            return CLEAR;
        }
    }
}
