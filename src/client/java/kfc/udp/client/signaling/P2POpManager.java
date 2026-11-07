package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 방장이 /op 로 준 OP 를 월드와 상관없이 기억한다(1.5~, config/instant-p2p/ops.json).
 * <p>
 * 바닐라 OP 는 월드(통합 서버)마다 따로라, 다른 월드로 방을 열면 믿는 친구에게 OP 를 매번 다시 줘야 했다. 방을 열 때
 * {@link #applyToServer} 로 이 명단을 그 월드에 넣고, 방을 닫을 때 {@link #revokeFromServer} 로 이번에 넣은 것을 다시
 * 뺀다 — 방을 닫은 뒤 그 월드를 싱글로 열었을 때 남의 OP 가 남아 있지 않게.
 */
public final class P2POpManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p");
    private static final Path OPS_FILE = Path.of("config", "instant-p2p", "ops.json");
    /** 소문자 UUID → ops.json 항목(uuid, name, level, bypassesPlayerLimit — 바닐라 ops.json 과 같은 모양). */
    private static final Map<String, JsonObject> ops = new LinkedHashMap<>();
    /** 이번 방에서 월드에 넣은 OP — 방을 닫을 때 뺀다. */
    private static final Set<String> grantedHere = new LinkedHashSet<>();

    private P2POpManager() {}

    private static String key(String uuid) {
        return uuid == null ? "" : uuid.toLowerCase(Locale.ROOT);
    }

    public static synchronized void load() {
        ops.clear();
        JsonArray arr = JsonFiles.readArray(OPS_FILE);
        if (arr == null) return;
        for (JsonElement e : arr) {
            try {
                JsonObject o = e.getAsJsonObject();
                String uuid = o.get("uuid").getAsString();
                UUID.fromString(uuid); // 형식 확인
                if (o.has("name")) ops.put(key(uuid), o);
            } catch (Exception ex) {
                LOG.warn("[instant-p2p] skipped a bad ops.json entry");
            }
        }
    }

    private static void save() {
        JsonArray arr = new JsonArray();
        ops.values().forEach(arr::add);
        JsonFiles.writeAtomic(OPS_FILE, arr);
    }

    public static synchronized boolean contains(String uuid) {
        return ops.containsKey(key(uuid));
    }

    public static synchronized void add(String uuid, String name) {
        if (ops.containsKey(key(uuid))) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        o.addProperty("level", 2);
        o.addProperty("bypassesPlayerLimit", false);
        ops.put(key(uuid), o);
        save();
    }

    public static synchronized boolean remove(String uuid) {
        boolean removed = ops.remove(key(uuid)) != null;
        if (removed) save();
        grantedHere.remove(key(uuid));
        return removed;
    }

    public static synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        for (JsonObject o : ops.values()) out.add(o.get("name").getAsString());
        return out;
    }

    public static synchronized void noteGranted(String uuid) {
        grantedHere.add(key(uuid));
    }

    /** 방을 열 때 — 명단을 이 월드 OP 로 넣는다(레벨은 항목 값, 1~4로 자른다). */
    public static void applyToServer(MinecraftServer server) {
        load();
        List<JsonObject> entries;
        synchronized (P2POpManager.class) {
            entries = new ArrayList<>(ops.values());
        }
        for (JsonObject o : entries) {
            try {
                String uuid = o.get("uuid").getAsString();
                String name = o.get("name").getAsString();
                P2PBanManager.ProfileLookup lookup = new P2PBanManager.ProfileLookup(UUID.fromString(uuid), name);
                int level = o.has("level") ? Math.max(1, Math.min(4, o.get("level").getAsInt())) : 2;
                P2PBanManager.grantOpPublic(server, lookup, level);
                noteGranted(uuid);
            } catch (Exception e) {
                LOG.warn("[instant-p2p] could not apply an ops.json entry: {}", e.getMessage());
            }
        }
    }

    /** 방을 닫을 때 — 이번에 넣은 OP(+명단 전체)를 이 월드에서 뺀다. */
    public static void revokeFromServer(MinecraftServer server) {
        List<String> granted;
        synchronized (P2POpManager.class) {
            Set<String> all = new LinkedHashSet<>(grantedHere);
            all.addAll(ops.keySet());
            granted = new ArrayList<>(all);
            grantedHere.clear();
        }
        for (String uuid : granted) {
            try {
                String name = "?";
                synchronized (P2POpManager.class) {
                    JsonObject o = ops.get(uuid);
                    if (o != null) name = o.get("name").getAsString();
                }
                P2PBanManager.revokeOpPublic(server, new P2PBanManager.ProfileLookup(UUID.fromString(uuid), name));
            } catch (Exception e) {
                LOG.warn("[instant-p2p] could not revoke op: {}", e.getMessage());
            }
        }
    }
}
