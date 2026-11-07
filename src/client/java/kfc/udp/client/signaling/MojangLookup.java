package kfc.udp.client.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 닉네임 → UUID 조회(1.5~) — 차단 탭 검색칸에 친 이름이 지금 접속자·최근 명단에 없을 때, 모장 API 로 찾아 그 자리에서
 * 차단할 수 있게 한다(BlockedPlayersScreen). 타자 치는 동안 매번 묻지 않게 입력이 {@link #SETTLE_MS} 동안 멈췄을 때만
 * 묻고, 결과는 짧게 기억한다.
 */
public final class MojangLookup {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final long SETTLE_MS = 600;
    private static final int MAX_CACHE = 64;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<String, CompletableFuture<Optional<P2PBanManager.BannedEntry>>> cache = new ConcurrentHashMap<>();

    private MojangLookup() {}

    /**
     * 이미 받아 둔 결과가 있으면 돌려주고, 없으면 조회를 걸어 두고 null(다음 프레임에 다시 부르면 된다).
     *
     * @param changedAtMs 검색어가 마지막으로 바뀐 시각
     */
    public static P2PBanManager.BannedEntry find(String query, long changedAtMs) {
        String q = query == null ? "" : query.trim();
        if (!NAME.matcher(q).matches() || System.currentTimeMillis() - changedAtMs < SETTLE_MS) return null;
        if (cache.size() >= MAX_CACHE) cache.clear();
        CompletableFuture<Optional<P2PBanManager.BannedEntry>> f = cache.computeIfAbsent(q.toLowerCase(Locale.ROOT), k -> lookup(q));
        return f.isDone() ? f.getNow(Optional.empty()).orElse(null) : null;
    }

    private static CompletableFuture<Optional<P2PBanManager.BannedEntry>> lookup(String name) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() != 200) return Optional.<P2PBanManager.BannedEntry>empty();
                    JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                    String id = o.get("id").getAsString();
                    if (id.length() != 32) return Optional.<P2PBanManager.BannedEntry>empty();
                    String dashed = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16)
                            + "-" + id.substring(16, 20) + "-" + id.substring(20);
                    return Optional.of(new P2PBanManager.BannedEntry(dashed, o.get("name").getAsString()));
                })
                .exceptionally(e -> Optional.empty());
    }
}
