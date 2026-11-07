package kfc.udp.client.signaling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 치지직(CHZZK) 계정 연동 — mc-signaling의 {@code /api/v1/chzzk/link/*}를 호출하는 클라이언트
 * 쪽 얇은 래퍼. 실제 OAuth 로그인·소유권 확인·팔로워 수 재확인은 전부 서버(mc-signaling)에서
 * 처리한다(chzzk.go 클래스 주석 참고) — 여기서는 "로그인 URL을 받아와서 브라우저로 연다"와
 * "지금 연동 상태를 물어본다" 두 가지만 한다. 결과는 ChzzkLinkScreen이 그린다.
 * <p>
 * 셋 다 {@link P2PConfig#getOrCreateChzzkViewToken()}을 같이 실어 보낸다 — link/status·link/unlink가
 * uuid만으로 아무나 "이 마크 계정이 어떤 치지직 채널과 연동됐는지" 조회·해제 가능하던 걸 막으려고
 * mc-signaling 쪽에 새로 건 인증이다(chzzk.go의 ChzzkLink.ViewToken 주석 참고).
 */
public final class ChzzkLink {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-chzzk");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient CLIENT = Http.CLIENT; // 공용(Http 주석)
    private static final java.util.concurrent.ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "instant-p2p-chzzk");
        t.setDaemon(true);
        return t;
    });

    public record Status(boolean linked, String channelName, int followerCount, boolean qualifies) {
        public static final Status UNLINKED = new Status(false, "", 0, false);
    }

    private ChzzkLink() {}

    /**
     * 서버가 준 로그인 URL 을 브라우저로 열어도 되는지(1.5~) — https 이고 네이버(치지직·네이버 로그인) 호스트일 때만.
     * 서버가 털리거나 중간에서 바꿔치기되어도 아무 사이트(피싱 페이지)나 열리지 않게 한다.
     */
    public static boolean isSafeAuthUrl(String url) {
        try {
            URI u = URI.create(url);
            String h = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && u.getUserInfo() == null && h != null
                    && (h.equalsIgnoreCase("chzzk.naver.com") || h.equalsIgnoreCase("nid.naver.com"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 로그인 URL을 받아온다 — 성공하면 그 URL, 실패하면(연동 기능이 서버에서 꺼져 있거나 네트워크
     * 오류) null을 완료시킨다. 호출부(ChzzkLinkScreen)가 null이면 실패 메시지를 보여준다. */
    public static CompletableFuture<String> requestAuthUrl(UUID me) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 연동 시작은 정품 토큰을 같이 보낸다(1.5~) — 남의 UUID 로 연동을 시작하지 못하게.
                MojangAuth.ensureToken();
                HttpRequest req = HttpRequest.newBuilder(URI.create(MojangAuth.withToken(
                                P2PConfig.signalingHttpUrl() + "/api/v1/chzzk/link/start?uuid=" + me
                                        + "&viewToken=" + P2PConfig.getOrCreateChzzkViewToken())))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    LOG.warn("[chzzk] link/start failed: HTTP {}", resp.statusCode());
                    return null;
                }
                JsonObject o = GSON.fromJson(resp.body(), JsonObject.class);
                return o != null && o.has("authUrl") ? o.get("authUrl").getAsString() : null;
            } catch (Exception e) {
                LOG.warn("[chzzk] link/start failed: {}", e.getMessage());
                return null;
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Status> fetchStatus(UUID me) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(
                                P2PConfig.signalingHttpUrl() + "/api/v1/chzzk/link/status?uuid=" + me
                                        + "&viewToken=" + P2PConfig.getOrCreateChzzkViewToken()))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return Status.UNLINKED;
                JsonObject o = GSON.fromJson(resp.body(), JsonObject.class);
                if (o == null || !o.has("linked") || !o.get("linked").getAsBoolean()) return Status.UNLINKED;
                return new Status(
                        true,
                        o.has("channelName") ? o.get("channelName").getAsString() : "",
                        o.has("followerCount") ? o.get("followerCount").getAsInt() : 0,
                        o.has("qualifies") && o.get("qualifies").getAsBoolean());
            } catch (Exception e) {
                LOG.warn("[chzzk] link/status failed: {}", e.getMessage());
                return Status.UNLINKED;
            }
        }, EXECUTOR);
    }

    /** 지금 연동을 끊는다 — 성공하면 true. 실패(네트워크 오류, 애초에 연동 안 됨 등)면 false. */
    public static CompletableFuture<Boolean> requestUnlink(UUID me) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(
                                P2PConfig.signalingHttpUrl() + "/api/v1/chzzk/link/unlink?uuid=" + me
                                        + "&viewToken=" + P2PConfig.getOrCreateChzzkViewToken()))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200;
            } catch (Exception e) {
                LOG.warn("[chzzk] link/unlink failed: {}", e.getMessage());
                return false;
            }
        }, EXECUTOR);
    }
}
