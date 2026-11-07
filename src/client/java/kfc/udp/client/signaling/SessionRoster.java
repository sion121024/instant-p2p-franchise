package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 최근 같이 있었던 사람 명단(1.5~) — 7일치·최대 200명(config/instant-p2p/recent_players.json). 차단 탭의 "최근" 목록과,
 * 이미 나간 사람을 신고할 때 "그 사람을 마지막으로 본 방·시각"을 찾는 데 쓴다(ReportClient.submit 의 past 신고).
 * <p>
 * 1초마다 지금 접속자 목록을 훑어 갱신한다. 접속(세션)이 바뀌면 대기 중인 신고를 먼저 보낸다 — 방을 나가면 그 방의
 * 뒤 1분 채팅을 더 모을 수 없기 때문이다.
 */
public final class SessionRoster {

    private static final int INTERVAL_TICKS = 20;
    private static final int MAX_ENTRIES = 200;
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long SAVE_EVERY_MS = 30_000;
    private static final Path FILE = Path.of("config", "instant-p2p", "recent_players.json");

    /** room = 마지막으로 같이 있었던 P2P 방 코드(일반 서버면 null). */
    public record Recent(String uuid, String name, long lastSeen, String room) {}

    /** 넣은 순서 = 마지막으로 본 순서(오래된 것이 앞) — 상한을 넘으면 앞에서부터 버린다. */
    private static final Map<String, Recent> seen = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;
    private static Object session;
    private static int cooldown;

    private SessionRoster() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (--cooldown <= 0) {
                cooldown = INTERVAL_TICKS;
                update();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(SessionRoster::saveNow, "instant-p2p-roster-save"));
    }

    public static synchronized void update() {
        load();
        long now = System.currentTimeMillis();
        Object cur = currentSession();
        if (cur != session) {
            ReportClient.flushAll(); // 방을 옮겼다 — 모으던 신고를 지금 보낸다
            session = cur;
            saveNow();
        }
        if (cur == null) return;
        String room = ReportClient.roomCode();
        for (P2PBanManager.BannedEntry e : online()) {
            if (e.name() == null || e.name().length() > 16) continue;
            Recent old = seen.remove(e.uuid()); // 지우고 다시 넣어 맨 뒤(최근)로
            seen.put(e.uuid(), new Recent(e.uuid(), e.name(), now, room != null ? room : old != null ? old.room() : null));
            dirty = true;
        }
        long cut = now - KEEP_MS;
        seen.values().removeIf(r -> r.lastSeen() < cut);
        while (seen.size() > MAX_ENTRIES) {
            seen.remove(seen.keySet().iterator().next());
            dirty = true;
        }
        if (dirty && now - lastSave > SAVE_EVERY_MS) saveNow();
    }

    /** 최근 명단 중 {@code exclude}(보통 지금 접속자)를 뺀 것 — 이름순. */
    public static synchronized List<P2PBanManager.BannedEntry> offline(Set<String> exclude) {
        load();
        List<P2PBanManager.BannedEntry> out = new ArrayList<>();
        for (Recent r : seen.values()) {
            if (!exclude.contains(r.uuid())) out.add(new P2PBanManager.BannedEntry(r.uuid(), r.name()));
        }
        out.sort(Comparator.comparing(P2PBanManager.BannedEntry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public static synchronized Recent get(String uuid) {
        load();
        return seen.get(uuid);
    }

    public static boolean isOnline(String uuid) {
        for (P2PBanManager.BannedEntry e : online()) {
            if (e.uuid().equals(uuid)) return true;
        }
        return false;
    }

    /** 지금 접속자만 남기고 최근 명단을 지운다 — 그 사람들의 채팅 기록도 같이(ChatContext.removeFrom). */
    public static synchronized void clearOffline() {
        load();
        Set<String> live = online().stream().map(P2PBanManager.BannedEntry::uuid).collect(Collectors.toSet());
        Set<String> names = new HashSet<>();
        for (Recent r : seen.values()) {
            if (!live.contains(r.uuid())) names.add(r.name().toLowerCase(Locale.ROOT));
        }
        seen.keySet().retainAll(live);
        ChatContext.removeFrom(names);
        dirty = true;
        saveNow();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            JsonArray arr = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray();
            List<Recent> list = new ArrayList<>();
            long cut = System.currentTimeMillis() - KEEP_MS;
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                long t = o.get("t").getAsLong();
                if (t < cut) continue;
                list.add(new Recent(o.get("u").getAsString(), o.get("n").getAsString(), t,
                        o.has("r") && !o.get("r").isJsonNull() ? o.get("r").getAsString() : null));
            }
            list.sort(Comparator.comparingLong(Recent::lastSeen));
            for (Recent r : list) seen.put(r.uuid(), r);
        } catch (Exception ignored) {
            // 깨진 명단은 버리고 새로 시작한다(부가 기능)
        }
    }

    private static synchronized void saveNow() {
        if (!loaded || !dirty) return;
        dirty = false;
        lastSave = System.currentTimeMillis();
        try {
            JsonArray arr = new JsonArray();
            for (Recent r : seen.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("u", r.uuid());
                o.addProperty("n", r.name());
                o.addProperty("t", r.lastSeen());
                if (r.room() != null) o.addProperty("r", r.room());
                arr.add(o);
            }
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirty = true;
        }
    }

    //? if >=26.1 {
    /*private static Object currentSession() {
        return net.minecraft.client.Minecraft.getInstance().getConnection();
    }

    // 나를 뺀 지금 접속자 — 이름순.
    public static List<P2PBanManager.BannedEntry> online() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return List.of();
        UUID me = mc.player.getUUID();
        return mc.getConnection().getOnlinePlayers().stream()
                .map(info -> info.getProfile())
                .filter(p -> !P2PBanManager.profileId(p).equals(me))
                .map(p -> new P2PBanManager.BannedEntry(P2PBanManager.profileId(p).toString(), P2PBanManager.profileName(p)))
                .sorted(Comparator.comparing(P2PBanManager.BannedEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
    *///?} else {
    private static Object currentSession() {
        return net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
    }

    /** 나를 뺀 지금 접속자 — 이름순. */
    public static List<P2PBanManager.BannedEntry> online() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || mc.player == null) return List.of();
        UUID me = mc.player.getUuid();
        return mc.getNetworkHandler().getPlayerList().stream()
                .map(entry -> entry.getProfile())
                .filter(p -> !P2PBanManager.profileId(p).equals(me))
                .map(p -> new P2PBanManager.BannedEntry(P2PBanManager.profileId(p).toString(), P2PBanManager.profileName(p)))
                .sorted(Comparator.comparing(P2PBanManager.BannedEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
    //?}
}
