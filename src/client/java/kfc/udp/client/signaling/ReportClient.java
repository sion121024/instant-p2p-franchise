package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kfc.udp.client.KfcudpClient;
import kfc.udp.client.quic.QuicBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 신고 전송(1.5 신고 체계, F3.5 에서 F3.4 의 신고를 대체) — 신고 화면(ReportScreen)·{@code /report} 명령·채팅 안전 필터의
 * 자동 신고가 전부 여기로 온다.
 * <p>
 * <b>신고는 항상 지금 접속해 있는 시그널링 서버로 간다</b>({@link P2PConfig#signalingHttpUrl()}, {@code POST /api/v1/report}).
 * 본점이면 원작자 서버로, 가맹점이면 가맹점 서버로 — 방장에게 가는 게 아니라서 신고 대상(또는 한패인 방장)이 신고를
 * 막거나 무력화할 방법이 없다. 신고자는 정품 토큰({@link MojangAuth#withToken})으로 확인된다.
 * <p>
 * 신고에 같이 실리는 것:
 * <ul>
 *   <li><b>채팅</b> — 신고 시각 앞 5분 + <b>뒤 1분</b>(그래서 지금 방에 있는 사람 신고는 1분 모았다가 보낸다 — 방을 나가면
 *       그 즉시). 대상의 서명된 채팅 증거(ChatProof) 최대 8개.</li>
 *   <li><b>주변 표지판·책</b>(SignSnapshot / BookSnapshot).</li>
 * </ul>
 * 이미 나간 사람도 최근 7일 안에 같은 방에 있었으면(SessionRoster) 그 방·시각 기준으로 "지난 신고"(past)를 보낸다.
 * 같은 사람·같은 방은 10분에 한 번만. 신고하면 그 사람은 자동으로 차단된다(ReportScreen).
 */
public final class ReportClient {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-report");
    static final long BEFORE_MS = 300_000;
    static final long AFTER_MS = 60_000;
    private static final int MAX_LINES = 100;
    private static final int MAX_PROOFS = 8;
    private static final HttpClient HTTP = Http.CLIENT; // 공용(Http 주석)
    private static final ScheduledExecutorService POOL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-report");
        t.setDaemon(true);
        return t;
    });
    /** 1분 모으는 중인 신고. */
    private static final List<Pending> pending = new ArrayList<>();
    /** "대상|방" → 보낸 시각 — 같은 신고는 10분에 한 번만. */
    private static final Map<String, Long> submitted = new ConcurrentHashMap<>();
    private static final long DUP_MS = 600_000;
    /** 지난 신고(이미 나간 사람)는 마지막으로 본 시각 앞 15분 + 뒤 2분 채팅을 싣는다. */
    private static final long PAST_BEFORE_MS = 900_000;
    private static final long PAST_AFTER_MS = 120_000;
    /** 서명 안 된 채팅을 봤다고 알린 방 — 방마다 한 번. */
    private static final Set<String> observed = ConcurrentHashMap.newKeySet();
    /** 안전 필터 자동 신고 — 같은 사람은 1분에 한 번. */
    private static final Map<String, Long> shieldSent = new ConcurrentHashMap<>();
    private static final Path RESULTS_SEEN = Path.of("config", "instant-p2p", "report_results_seen.txt");
    private static final AtomicBoolean resultsChecked = new AtomicBoolean();

    /** 신고 분류 — 원본 1.5.1 과 같은 5가지(F4.0, 예전 가맹점 12사유는 정리). 화면 순서도 이 순서다. */
    public static final String[] CATEGORIES = {"harass", "behavior", "money", "illegal", "other"};

    /** 알려진 분류 코드인지 — {@code /report 이름 spam 내용} 처럼 첫 단어로 분류를 고를 수 있게. */
    public static boolean isCategory(String code) {
        for (String c : CATEGORIES) {
            if (c.equals(code)) return true;
        }
        return false;
    }

    private ReportClient() {}

    public static void tellNoRoom() {
        tell("instant-p2p.report.msg.no_room");
    }

    /** 방 목록에서 방(제목·내용)을 신고한다 — 대상은 방장. 채팅은 없고 지금 보이는 표지판·책만. */
    public static void submitRoom(String hostUuid, String code, String seenTitle, String text) {
        long now = System.currentTimeMillis();
        submitted.values().removeIf(t -> now - t > DUP_MS);
        if (submitted.putIfAbsent(hostUuid + "|" + code, now) != null) {
            tell("instant-p2p.report.msg.duplicate");
            return;
        }
        JsonObject o = baseReport(hostUuid, code, "title", text, now, false);
        o.addProperty("seen_title", seenTitle == null ? "" : seenTitle);
        o.add("chat", new JsonArray());
        JsonArray roomSigns = SignSnapshot.capture();
        if (!roomSigns.isEmpty()) o.add("signs", roomSigns);
        JsonArray roomBooks = BookSnapshot.capture();
        if (!roomBooks.isEmpty()) o.add("books", roomBooks);
        tell("instant-p2p.report.msg.sending_past");
        POOL.execute(() -> {
            try {
                if (MojangAuth.ensureToken() == null) {
                    tell("instant-p2p.report.msg.auth");
                    return;
                }
                postReport(o);
            } catch (Exception e) {
                LOG.warn("[report] 전송 실패: {}", e.toString());
                tell("instant-p2p.report.msg.failed", e.getClass().getSimpleName());
            }
        });
    }

    /** 신고 본문의 공통 필드. */
    private static JsonObject baseReport(String target, String code, String category, String text, long at, boolean past) {
        JsonObject o = new JsonObject();
        o.addProperty("target", target);
        o.addProperty("code", code);
        o.addProperty("category", category);
        o.addProperty("text", text == null ? "" : text.trim());
        o.addProperty("at", at);
        o.addProperty("past", past);
        return o;
    }

    /** 접속자로 들어간 방에서 서명 안 된 채팅을 봤다 — 서버에 한 번 알린다(보안 채팅을 끈 공개 방 거르기). */
    public static void observeUnsigned(String room) {
        if (room == null || KfcudpClient.activeRoomCode() != null || QuicBridge.joinedRoomCode() == null) return;
        if (observed.size() > 64) observed.clear();
        if (!observed.add(room)) return;
        POOL.execute(() -> {
            try {
                if (MojangAuth.ensureToken() == null) return;
                JsonObject body = new JsonObject();
                body.addProperty("code", room);
                body.addProperty("kind", "unsigned");
                HttpRequest req = HttpRequest.newBuilder(URI.create(MojangAuth.withToken(P2PConfig.signalingHttpUrl() + "/api/v1/observe")))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                LOG.debug("[report] observe 전송 실패: {}", e.toString());
            }
        });
    }

    /**
     * 방장 — 채팅 안전 필터(ChatShield)에 걸린 메시지를 서명 증거와 함께 자동 신고한다(category=illegal, text=auto-shield).
     * 증거는 클라이언트 스레드에서 떠야 한다(접속자 목록의 공개키를 읽는다).
     */
    public static void submitShield(UUID target, Object message, Object profile) {
        String code = KfcudpClient.activeRoomCode();
        if (message == null || code == null) return;
        long now = System.currentTimeMillis();
        shieldSent.values().removeIf(t -> now - t > 60_000);
        if (shieldSent.putIfAbsent(target.toString(), now) != null) return;
        runOnClient(() -> {
            ChatProof.Proof pr = ChatProof.capture(message, profile);
            if (pr == null || pr.signed() == null) return; // 서명이 없으면 증거가 안 된다
            POOL.execute(() -> {
                try {
                    if (MojangAuth.ensureToken() == null) return;
                    JsonObject o = baseReport(target.toString(), code, "illegal", "auto-shield", now, false);
                    o.add("chat", new JsonArray());
                    JsonArray proofs = new JsonArray();
                    proofs.add(proofJson(now, pr));
                    o.add("proofs", proofs);
                    HttpResponse<String> res = HTTP.send(reportRequest(o), HttpResponse.BodyHandlers.ofString());
                    LOG.debug("[shield] 증거 제출 HTTP {}", res.statusCode());
                } catch (Exception e) {
                    LOG.debug("[shield] 증거 제출 실패: {}", e.toString());
                }
            });
        });
    }

    /** 내가 낸 신고의 결과(조치됨/기각됨)를 접속할 때 한 번 묻는다 — 새 결과가 있으면 then(조치, 기각). */
    public static void checkResultsOnce(BiConsumer<Integer, Integer> then) {
        if (!resultsChecked.compareAndSet(false, true)) return;
        POOL.execute(() -> {
            try {
                if (MojangAuth.ensureToken() == null) {
                    resultsChecked.set(false); // 다음 접속 때 다시
                    return;
                }
                long since = 0;
                try {
                    since = Long.parseLong(Files.readString(RESULTS_SEEN).trim());
                } catch (Exception ignored) {
                    // 처음이면 파일이 없다
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(
                                MojangAuth.withToken(P2PConfig.signalingHttpUrl() + "/api/v1/report/results?since=" + since)))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) return;
                JsonArray arr = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("results");
                int acted = 0, dismissed = 0;
                long newest = since;
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    if ("acted".equals(o.get("result").getAsString())) acted++;
                    else dismissed++;
                    newest = Math.max(newest, o.get("at").getAsLong());
                }
                if (arr.isEmpty()) return;
                JsonFiles.writeText(RESULTS_SEEN, Long.toString(newest));
                then.accept(acted, dismissed);
            } catch (Exception e) {
                LOG.debug("[report] 신고 결과 확인 실패: {}", e.toString());
            }
        });
    }

    /** 지금 있는 P2P 방 코드 — 내가 연 방이면 그 코드, 접속자면 들어간 방 코드, 둘 다 아니면 null. */
    public static String roomCode() {
        String c = KfcudpClient.activeRoomCode();
        return c != null ? c : QuicBridge.joinedRoomCode();
    }

    /** 지금 같은 방에 있거나, 최근 7일 안에 같은 방에 있었던 사람인지. */
    public static boolean canReport(String targetUuid) {
        if (roomCode() != null && SessionRoster.isOnline(targetUuid)) return true;
        SessionRoster.Recent r = SessionRoster.get(targetUuid);
        return r != null && r.room() != null;
    }

    /**
     * 플레이어 신고. 지금 같은 방에 있으면 1분 동안 뒤 채팅을 모았다가 보내고, 이미 나갔으면 마지막으로 본 방·시각
     * 기준으로 바로 보낸다(past).
     *
     * @param category {@link #CATEGORIES} 중 하나
     */
    public static void submit(String targetUuid, String category, String text) {
        boolean live = roomCode() != null && SessionRoster.isOnline(targetUuid);
        SessionRoster.Recent rec = SessionRoster.get(targetUuid);
        String code = live ? roomCode() : rec != null ? rec.room() : null;
        if (code == null) {
            tell("instant-p2p.report.msg.no_room");
            return;
        }
        long now = System.currentTimeMillis();
        submitted.values().removeIf(t -> now - t > DUP_MS);
        if (submitted.putIfAbsent(targetUuid + "|" + code, now) != null) {
            tell("instant-p2p.report.msg.duplicate");
            return;
        }
        String body = text == null ? "" : text.trim();
        String cat = isCategory(category) ? category : "other";
        if (live) {
            Pending p = new Pending(targetUuid, code, cat, body, now, ChatContext.window(now - BEFORE_MS, now, code));
            p.signs = SignSnapshot.capture();
            p.books = BookSnapshot.capture();
            synchronized (pending) {
                pending.add(p);
            }
            p.timer = POOL.schedule(() -> {
                p.freeze();
                p.post();
            }, AFTER_MS, TimeUnit.MILLISECONDS);
            tell("instant-p2p.report.msg.queued");
        } else {
            long at = rec.lastSeen();
            Pending p = new Pending(targetUuid, code, cat, body, at, ChatContext.window(at - PAST_BEFORE_MS, at + PAST_AFTER_MS, code));
            p.past = true;
            p.signs = SignSnapshot.capture();
            p.books = BookSnapshot.capture();
            p.after = List.of();
            synchronized (pending) {
                pending.add(p);
            }
            POOL.execute(p::post);
            tell("instant-p2p.report.msg.sending_past");
        }
    }

    /** 방을 옮겼다(SessionRoster) — 모으던 신고를 지금까지 모은 채팅으로 바로 보낸다. */
    public static void flushAll() {
        List<Pending> list;
        synchronized (pending) {
            list = new ArrayList<>(pending);
        }
        for (Pending p : list) {
            p.freeze();
            if (p.timer != null) p.timer.cancel(false);
            POOL.execute(p::post);
        }
    }

    private static JsonObject proofJson(long t, ChatProof.Proof pr) {
        JsonObject o = new JsonObject();
        o.addProperty("t", t);
        o.addProperty("signed", pr.signed());
        o.addProperty("sig", pr.sig());
        o.addProperty("key", pr.key());
        o.addProperty("ksig", pr.ksig());
        o.addProperty("kexp", pr.kexp());
        return o;
    }

    private static void send(Pending p) throws Exception {
        if (MojangAuth.ensureToken() == null) {
            tell("instant-p2p.report.msg.auth");
            return;
        }
        List<ChatContext.Line> all = new ArrayList<>(p.before);
        all.addAll(p.after != null ? p.after : List.of());
        all.sort(Comparator.comparingLong(ChatContext.Line::t));
        Set<String> seen = new HashSet<>();
        all.removeIf(line -> !seen.add(line.t() + "|" + line.text())); // 앞·뒤 구간이 겹친 줄
        if (all.size() > MAX_LINES) all = all.subList(all.size() - MAX_LINES, all.size());

        JsonObject o = baseReport(p.target, p.code, p.category, p.text, p.at, p.past);
        JsonArray chat = new JsonArray();
        for (ChatContext.Line l : all) {
            JsonObject c = new JsonObject();
            c.addProperty("t", l.t());
            c.addProperty("from", l.from());
            c.addProperty("line", l.text());
            chat.add(c);
        }
        o.add("chat", chat);
        // 대상이 보낸 서명된 채팅 증거 — 최근 것부터 8개
        JsonArray proofs = new JsonArray();
        String target = p.target.replace("-", "");
        for (int i = all.size() - 1; i >= 0 && proofs.size() < MAX_PROOFS; i--) {
            ChatContext.Line l = all.get(i);
            if (l.proof() != null && l.uuid() != null && l.uuid().replace("-", "").equalsIgnoreCase(target)) {
                proofs.add(proofJson(l.t(), l.proof()));
            }
        }
        if (!proofs.isEmpty()) o.add("proofs", proofs);
        if (p.signs != null && !p.signs.isEmpty()) o.add("signs", p.signs);
        if (p.books != null && !p.books.isEmpty()) o.add("books", p.books);
        postReport(o);
    }

    private static HttpRequest reportRequest(JsonObject o) {
        return HttpRequest.newBuilder(URI.create(MojangAuth.withToken(P2PConfig.signalingHttpUrl() + "/api/v1/report")))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(o.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static void postReport(JsonObject o) throws Exception {
        HttpResponse<String> res = HTTP.send(reportRequest(o), HttpResponse.BodyHandlers.ofString());
        String body = res.body() == null ? "" : res.body();
        switch (res.statusCode()) {
            case 200 -> tell(body.contains("\"duplicate\":true") ? "instant-p2p.report.msg.duplicate" : "instant-p2p.report.msg.sent");
            case 401 -> tell("instant-p2p.report.msg.auth");
            case 403 -> tell("instant-p2p.report.msg.banned");
            case 422 -> tell("instant-p2p.report.msg.no_shared_room");
            case 429 -> tell("instant-p2p.report.msg.rate");
            default -> tell("instant-p2p.report.msg.failed", "HTTP " + res.statusCode());
        }
    }

    /** 1분 모으는 신고 하나. */
    private static final class Pending {
        final String target;
        final String code;
        final String category;
        final String text;
        final long at;
        final List<ChatContext.Line> before;
        ScheduledFuture<?> timer;
        List<ChatContext.Line> after;
        boolean done;
        boolean past;
        JsonArray signs;
        JsonArray books;

        Pending(String target, String code, String category, String text, long at, List<ChatContext.Line> before) {
            this.target = target;
            this.code = code;
            this.category = category;
            this.text = text;
            this.at = at;
            this.before = before;
        }

        /** 뒤 채팅 구간을 지금 확정한다(한 번만). */
        synchronized void freeze() {
            if (after == null) after = ChatContext.window(at, at + AFTER_MS, code);
        }

        void post() {
            synchronized (this) {
                if (done) return;
                done = true;
            }
            synchronized (ReportClient.pending) {
                ReportClient.pending.remove(this);
            }
            try {
                ReportClient.send(this);
            } catch (Exception e) {
                LOG.warn("[report] 전송 실패: {}", e.toString());
                tell("instant-p2p.report.msg.failed", e.getClass().getSimpleName());
            }
        }
    }

    //? if >=26.1 {
    /*private static void runOnClient(Runnable r) {
        net.minecraft.client.Minecraft.getInstance().execute(r);
    }

    private static void tell(String key, Object... args) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(key, args));
        });
    }
    *///?} else {
    private static void runOnClient(Runnable r) {
        net.minecraft.client.MinecraftClient.getInstance().execute(r);
    }

    private static void tell(String key, Object... args) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.translatable(key, args), false);
        });
    }
    //?}
}
