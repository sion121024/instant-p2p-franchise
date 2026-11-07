package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kfc.udp.client.KfcudpClient;
import kfc.udp.client.quic.QuicBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 공개 방 방장의 이벤트 기록(1.5~) — 입장·퇴장·명령·상자 열기·표지판/책 수정·사망·블록 변경을 랑데부 서버로 보낸다
 * (QuicHost.sendHostLog — 해시 사슬로 묶여 방장이 골라 지울 수 없다). 신고를 처리할 때 "그 시각 그 방에서 무슨 일이
 * 있었는지"를 신고자 말만이 아니라 방장 기록과 대조하려는 것이다.
 * <p>
 * <b>공개 방일 때만 보낸다</b>(초대 코드 방은 친구끼리라 기록하지 않는다). 채팅류 명령(/msg 등)은 내용 없이 명령
 * 이름만 남긴다. 블록 변경은 청크·사람 단위로 1분씩 모아 개수와 많이 쓴 블록 3개만 보낸다 — 건축 한 번에 수천 줄이
 * 나가지 않게. 30초마다 살아 있다는 표시(hb)를 보낸다.
 */
public final class HostEventLog {

    /** 내용을 남기지 않는 명령 — 귓속말·채팅과 같은 사적인 말이다. */
    private static final Set<String> CHAT_LIKE = Set.of("me", "msg", "tell", "w", "teammsg", "tm", "say", "tellraw", "title");
    private static final Object lock = new Object();
    private static ScheduledExecutorService timer;
    private static int tickCount = 0;
    private static long cmdWindow = 0;
    private static int cmdCount = 0;
    /** 같은 상자를 연달아 여닫는 걸 10초에 한 번만 남긴다. */
    private static final Map<String, Long> openSeen = new HashMap<>();
    /** 명령 기록 상한 — 분당 40개(도배로 기록이 넘치지 않게). */
    private static final int CMD_PER_MIN = 40;
    private static final Map<String, Agg> blocks = new HashMap<>();

    private HostEventLog() {}

    public static void start() {
        synchronized (lock) {
            if (timer != null) return;
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "instant-p2p-hostlog");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleAtFixedRate(() -> {
                try {
                    tick();
                } catch (RuntimeException ignored) {
                    // 기록은 부가 기능이다 — 실패해도 다음 주기에 다시 한다
                }
            }, 5, 30, TimeUnit.SECONDS);
        }
    }

    /**
     * 방 안 기록을 남길지(1.5.1) — 본점은 1.5.1 처럼 초대코드 방까지 모든 방(통합 동의에 들어 있다),
     * 가맹점은 F3.5 처럼 공개 방만(가맹점 동의서는 초대코드 방을 빼고 받는다).
     */
    private static boolean logging() {
        if (!KfcudpClient.isRoomActive()) return false;
        return kfc.udp.client.gui.PublicConsent.unified() || KfcudpClient.isActivePublicRoom();
    }

    private static void tick() {
        if (!logging()) {
            synchronized (lock) {
                blocks.clear();
            }
            return;
        }
        JsonObject hb = new JsonObject();
        hb.addProperty("k", "hb");
        QuicBridge.hostLog(hb.toString());
        if (++tickCount % 2 == 0) flushBlocks(); // 1분마다
    }

    /** "ResourceKey[minecraft:dimension / minecraft:overworld]" → "minecraft:overworld" (이미 짧으면 그대로). */
    private static String dimId(String s) {
        int i = s.lastIndexOf(" / ");
        if (i >= 0) s = s.substring(i + 3);
        int j = s.indexOf(']');
        return j >= 0 ? s.substring(0, j) : s;
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    private static void emit(JsonObject ev) {
        if (logging()) QuicBridge.hostLog(ev.toString());
    }

    public static void join(String uuid, String nick) {
        simple("join", uuid, nick);
    }

    public static void leave(String uuid, String nick) {
        simple("leave", uuid, nick);
    }

    private static void simple(String kind, String uuid, String nick) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("k", kind);
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    public static void command(String uuid, String nick, String command) {
        try {
            String c = command == null ? "" : command.strip();
            if (c.startsWith("/")) c = c.substring(1);
            if (c.isEmpty()) return;
            int sp = c.indexOf(' ');
            String head = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
            if (head.startsWith("minecraft:")) head = head.substring("minecraft:".length());
            String arg = !CHAT_LIKE.contains(head) && sp >= 0 ? c.substring(sp + 1) : "";
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (now - cmdWindow > 60_000) {
                    cmdWindow = now;
                    cmdCount = 0;
                }
                if (++cmdCount > CMD_PER_MIN) return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("k", "cmd");
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            o.addProperty("cmd", clip(head, 32));
            o.addProperty("arg", clip(arg, 120));
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    public static void open(String uuid, String nick, String dim, int x, int y, int z, String block) {
        try {
            long now = System.currentTimeMillis();
            String key = uuid + "|" + dim + "|" + x + "," + y + "," + z;
            synchronized (lock) {
                Long last = openSeen.get(key);
                if (last != null && now - last < 10_000) return;
                if (openSeen.size() > 500) openSeen.clear();
                openSeen.put(key, now);
            }
            JsonObject o = new JsonObject();
            o.addProperty("k", "open");
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            o.addProperty("d", dimId(dim));
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("block", clip(block, 48));
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    public static void signBroken(String uuid, String nick, String dim, int x, int y, int z) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("k", "sbreak");
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            o.addProperty("d", dimId(dim));
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    public static void death(String uuid, String nick, String dim, int x, int y, int z, String killer, String kname) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("k", "death");
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            o.addProperty("d", dimId(dim));
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            if (killer != null) o.addProperty("killer", killer);
            o.addProperty("kname", clip(kname, 40));
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    /** 블록을 부수거나(placed=false) 놓았다 — 바로 보내지 않고 사람·청크 단위로 모은다(flushBlocks). */
    public static void block(String uuid, String nick, String dim, int x, int y, int z, boolean placed, String blockId) {
        try {
            if (!logging()) return;
            String d = dimId(dim);
            int cx = x >> 4;
            int cz = z >> 4;
            String key = uuid + "|" + d + "|" + cx + "," + cz;
            synchronized (lock) {
                Agg a = blocks.get(key);
                if (a == null) {
                    if (blocks.size() >= 500) return;
                    a = new Agg();
                    a.uuid = uuid;
                    a.nick = nick;
                    a.dim = d;
                    a.cx = cx;
                    a.cz = cz;
                    blocks.put(key, a);
                }
                if (placed) a.placed++;
                else a.broke++;
                if (a.kinds.size() < 20 || a.kinds.containsKey(blockId)) a.kinds.merge(blockId, 1, Integer::sum);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void flushBlocks() {
        List<Agg> out;
        synchronized (lock) {
            out = new ArrayList<>(blocks.values());
            blocks.clear();
        }
        for (Agg a : out) {
            JsonObject o = new JsonObject();
            o.addProperty("k", "blocks");
            o.addProperty("by", a.uuid);
            o.addProperty("nick", clip(a.nick, 32));
            o.addProperty("d", a.dim);
            o.addProperty("cx", a.cx);
            o.addProperty("cz", a.cz);
            o.addProperty("broke", a.broke);
            o.addProperty("placed", a.placed);
            JsonArray top = new JsonArray();
            a.kinds.entrySet().stream()
                    .sorted((p, q) -> q.getValue() - p.getValue())
                    .limit(3)
                    .forEach(e -> top.add(clip(e.getKey(), 40) + " x" + e.getValue()));
            o.add("top", top);
            emit(o);
        }
    }

    public static void sign(String uuid, String nick, String dim, int x, int y, int z, String before, String after) {
        try {
            String b = before == null ? "" : before;
            String a = after == null ? "" : after;
            if (b.equals(a)) return;
            JsonObject o = new JsonObject();
            o.addProperty("k", "sign");
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            o.addProperty("d", dimId(dim));
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("before", clip(b, 512));
            o.addProperty("after", clip(a, 512));
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    /** 책 수정 — before/after 는 BookSnapshot.describe 가 만든 JSON(빈 문자열이면 책이 아니었다). */
    public static void book(String uuid, String nick, int slot, String beforeJson, String afterJson) {
        try {
            String b = beforeJson == null ? "" : beforeJson;
            String a = afterJson == null ? "" : afterJson;
            if (b.equals(a)) return;
            JsonObject o = new JsonObject();
            o.addProperty("k", "book");
            o.addProperty("by", uuid);
            o.addProperty("nick", clip(nick, 32));
            o.addProperty("slot", slot);
            if (!b.isEmpty()) o.add("before", JsonParser.parseString(b));
            if (!a.isEmpty()) o.add("after", JsonParser.parseString(a));
            emit(o);
        } catch (RuntimeException ignored) {
        }
    }

    private static final class Agg {
        String uuid;
        String nick;
        String dim;
        int cx;
        int cz;
        int broke;
        int placed;
        final Map<String, Integer> kinds = new HashMap<>();
    }
}
