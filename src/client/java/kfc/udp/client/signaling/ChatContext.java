package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 최근 채팅 기록(1.5~) — 신고할 때 "신고 시각 앞 5분 + 뒤 1분" 채팅을 같이 보내려고 P2P 방에서 받은 채팅만 3일치·최대
 * 600줄 남긴다(config/instant-p2p/recent_chat.json). 서명된 채팅은 증거(ChatProof)도 같이 — 최근 150줄만.
 * <p>
 * 방장 쪽은 신고를 처리하는 서버가 묻는 구간을 이 기록으로 답한다(QuicBridge.chatWindowJson). 방 밖(일반 서버·싱글)
 * 채팅은 남기지 않는다. 차단 탭에서 최근 명단을 지우면 그 사람들의 채팅도 같이 지운다({@link #removeFrom}).
 */
public final class ChatContext {

    private static final long KEEP_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_LINES = 600;
    private static final int MAX_TEXT = 256;
    private static final int MAX_PROOF_LINES = 150;
    private static final int SAVE_EVERY_TICKS = 600; // 30초
    private static final Path FILE = Path.of("config", "instant-p2p", "recent_chat.json");

    /** t=받은 시각, from=보낸 사람 닉(시스템 메시지면 ""), room=방 코드, uuid=보낸 사람(있으면), proof=서명 증거(있으면). */
    public record Line(long t, String from, String text, String room, String uuid, ChatProof.Proof proof) {}

    private static final ArrayDeque<Line> buf = new ArrayDeque<>();
    private static boolean loaded;
    private static boolean dirty;
    private static int cooldown;

    private ChatContext() {}

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> add(
                sender == null ? "" : P2PBanManager.profileName(sender),
                message.getString(),
                sender == null ? null : P2PBanManager.profileId(sender).toString(),
                ChatProof.capture(signedMessage, sender)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) add("", message.getString(), null, null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (--cooldown <= 0) {
                cooldown = SAVE_EVERY_TICKS;
                saveNow();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(ChatContext::saveNow, "instant-p2p-chat-save"));
    }

    private static synchronized void add(String from, String text, String uuid, ChatProof.Proof proof) {
        String room = ReportClient.roomCode();
        if (room == null) return; // P2P 방이 아니다
        load();
        long now = System.currentTimeMillis();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        if (from.length() > 32) from = from.substring(0, 32);
        boolean unsigned = proof == ChatProof.UNSIGNED;
        buf.addLast(new Line(now, from, text, room, uuid, unsigned ? null : proof));
        dirty = true;
        if (unsigned) ReportClient.observeUnsigned(room);
        while (buf.size() > MAX_LINES || (!buf.isEmpty() && now - buf.peekFirst().t() > KEEP_MS)) buf.pollFirst();
    }

    private static ChatProof.Proof readProof(JsonObject o) {
        if (!o.has("p")) return null;
        JsonObject p = o.getAsJsonObject("p");
        return new ChatProof.Proof(p.get("s").getAsString(), p.get("g").getAsString(), p.get("k").getAsString(),
                p.get("ks").getAsString(), p.get("e").getAsLong());
    }

    private static void writeProof(JsonObject o, ChatProof.Proof pr) {
        JsonObject p = new JsonObject();
        p.addProperty("s", pr.signed());
        p.addProperty("g", pr.sig());
        p.addProperty("k", pr.key());
        p.addProperty("ks", pr.ksig());
        p.addProperty("e", pr.kexp());
        o.add("p", p);
    }

    /** 그 방의 [fromMs, toMs] 구간 채팅. */
    public static synchronized List<Line> window(long fromMs, long toMs, String room) {
        load();
        List<Line> out = new ArrayList<>();
        for (Line l : buf) {
            if (l.t() >= fromMs && l.t() <= toMs && room != null && room.equals(l.room())) out.add(l);
        }
        return out;
    }

    /** 이 사람들(소문자 닉)이 보낸 채팅을 지운다 — 최근 명단 지우기(SessionRoster.clearOffline). */
    public static synchronized void removeFrom(Set<String> lowerNames) {
        load();
        if (buf.removeIf(l -> !l.from().isEmpty() && lowerNames.contains(l.from().toLowerCase(Locale.ROOT)))) dirty = true;
        saveNow();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            JsonArray arr = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray();
            long cut = System.currentTimeMillis() - KEEP_MS;
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                long t = o.get("t").getAsLong();
                if (t < cut) continue;
                buf.addLast(new Line(t, o.get("f").getAsString(), o.get("x").getAsString(),
                        o.has("r") && !o.get("r").isJsonNull() ? o.get("r").getAsString() : null,
                        o.has("u") ? o.get("u").getAsString() : null,
                        readProof(o)));
            }
            while (buf.size() > MAX_LINES) buf.pollFirst();
        } catch (Exception ignored) {
            // 깨진 기록은 버리고 새로 시작한다(부가 기능)
        }
    }

    private static synchronized void saveNow() {
        if (!loaded || !dirty) return;
        dirty = false;
        try {
            JsonArray arr = new JsonArray();
            int idx = 0;
            for (Line l : buf) {
                JsonObject o = new JsonObject();
                o.addProperty("t", l.t());
                o.addProperty("f", l.from());
                o.addProperty("x", l.text());
                if (l.room() != null) o.addProperty("r", l.room());
                if (l.uuid() != null) o.addProperty("u", l.uuid());
                // 증거는 커서(줄마다 수백 바이트) 최근 것만 파일에 남긴다
                if (l.proof() != null && idx >= buf.size() - MAX_PROOF_LINES) writeProof(o, l.proof());
                idx++;
                arr.add(o);
            }
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirty = true; // 다음 주기에 다시
        }
    }
}
