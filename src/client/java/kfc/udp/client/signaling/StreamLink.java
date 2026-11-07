package kfc.udp.client.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 가맹점 방송인 연동(F3.3~) — 치지직·트위치·씨미·SOOP 채널을 "인증 코드" 방식으로 마크 계정에 붙인다.
 * <ol>
 *   <li>{@link #start} — 플랫폼·채널 주소를 보내면 서버가 코드(예: P2P-7KQ2MX)를 준다</li>
 *   <li>방송인이 채널 소개(설명)에 그 코드를 잠깐 넣는다</li>
 *   <li>{@link #verify} — 서버가 채널 공개 정보를 읽어 코드가 있으면 연동한다(그 뒤엔 코드를 지워도 된다)</li>
 * </ol>
 * 마크 계정은 정품 토큰({@link MojangAuth#ensureToken})으로 증명한다. 실제 확인은 전부 가맹점 서버
 * (mc-relay-server의 streamlink.go)가 한다. 팔로워 기준을 넘는 연동 계정 목록({@link #refreshStreamers})이
 * 가맹점에서의 🎧 방송인 등급이 된다({@link Roles#isStreamer}).
 */
public final class StreamLink {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-stream");
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-stream");
        t.setDaemon(true);
        return t;
    });

    /** 서버 응답 요약. error가 비어 있지 않으면 실패 이유(auth-required, bad-channel, code-not-found …). */
    public record Result(boolean linked, String platform, String channel, int followers, boolean qualifies,
                         int minFollowers, String code, String error) {
        static Result err(String e) { return new Result(false, "", "", 0, false, 0, "", e); }
    }

    private static volatile Set<UUID> streamers = Set.of();

    private StreamLink() {}

    static Set<UUID> snapshot() {
        return streamers;
    }

    /** 가맹점에서 인정된 방송인인가(팔로워 기준 이상으로 연동된 계정). */
    public static boolean isStreamer(UUID id) {
        return id != null && streamers.contains(id);
    }

    public static CompletableFuture<Result> start(String platform, String channel) {
        JsonObject b = new JsonObject();
        b.addProperty("platform", platform);
        b.addProperty("channel", channel);
        return post("/api/v1/stream/start", b);
    }

    public static CompletableFuture<Result> verify() {
        return post("/api/v1/stream/verify", new JsonObject());
    }

    public static CompletableFuture<Result> unlink() {
        return post("/api/v1/stream/unlink", new JsonObject());
    }

    public static CompletableFuture<Result> status() {
        return CompletableFuture.supplyAsync(() -> {
            String tok = MojangAuth.ensureToken();
            if (tok == null) return Result.err("auth-required");
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.franchiseHttpUrl()
                                + "/api/v1/stream/status?token=" + URLEncoder.encode(tok, StandardCharsets.UTF_8)))
                        .timeout(TIMEOUT).GET().build();
                return parse(Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString()));
            } catch (Exception e) {
                LOG.warn("[stream] status 실패: {}", e.getMessage());
                return Result.err("network");
            }
        }, EXECUTOR);
    }

    /** 방송인 목록을 다시 받는다(방을 열거나 들어갈 때 Roles.refreshNow가 같이 부른다). 블로킹. */
    public static void refreshStreamers() {
        if (P2PConfig.getServerProfile() != P2PConfig.ServerProfile.FRANCHISE) {
            streamers = Set.of();
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.franchiseHttpUrl() + "/api/v1/stream/streamers"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return; // 실패하면 마지막 값 유지
            JsonObject o = GSON.fromJson(res.body(), JsonObject.class);
            Set<UUID> out = new HashSet<>();
            if (o != null && o.has("streamer") && o.get("streamer").isJsonArray()) {
                for (var e : o.getAsJsonArray("streamer")) {
                    try {
                        out.add(UUID.fromString(e.getAsString()));
                    } catch (Exception ignored) {}
                }
            }
            streamers = Set.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[stream] 방송인 목록 받기 실패(무시): {}", e.getMessage());
        }
    }

    private static CompletableFuture<Result> post(String path, JsonObject body) {
        return CompletableFuture.supplyAsync(() -> {
            String tok = MojangAuth.ensureToken();
            if (tok == null) return Result.err("auth-required");
            body.addProperty("token", tok);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.franchiseHttpUrl() + path))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                return parse(Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString()));
            } catch (Exception e) {
                LOG.warn("[stream] {} 실패: {}", path, e.getMessage());
                return Result.err("network");
            }
        }, EXECUTOR);
    }

    private static Result parse(HttpResponse<String> res) {
        JsonObject o;
        try {
            o = GSON.fromJson(res.body(), JsonObject.class);
        } catch (Exception e) {
            o = null;
        }
        if (o == null) return Result.err(res.statusCode() == 429 ? "too-fast" : "http-" + res.statusCode());
        return new Result(bool(o, "linked"), str(o, "platform"), str(o, "channel"), num(o, "followers"),
                bool(o, "qualifies"), num(o, "minFollowers"), str(o, "code"),
                o.has("error") ? str(o, "error") : (res.statusCode() / 100 == 2 ? "" : "http-" + res.statusCode()));
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
