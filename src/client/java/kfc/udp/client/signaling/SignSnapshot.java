package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kfc.udp.client.DevBadge;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
*///?} else {
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
//?}

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 주변 표지판 글(1.5~) — 신고에 "그때 주변 표지판에 뭐라고 적혀 있었는지"를 싣는다(욕설·개인정보·광고 표지판 신고용).
 * <p>
 * P2P 방에 있는 동안 10초마다 주변(반경 32청크) 표지판을 훑어 글이 바뀐 이력(최근 3개)을 기억한다 — 신고 직전에 지운
 * 표지판도 이전 글이 남는다. 신고할 때 가까운 순으로 200개까지 보낸다({@link #capture}).
 * <p>
 * 방장 쪽은 신고를 처리하는 서버가 좌표를 주며 "지금 이 표지판에 뭐라고 적혀 있나"를 물으면 자기 월드에서 읽어 답한다
 * ({@link #hostAnswer}) — 신고자가 표지판 글을 지어내지 못하게 대조하는 것이다.
 */
public final class SignSnapshot {

    private static final int MAX_SIGNS = 200;
    private static final int RADIUS_CHUNKS = 32;
    private static final int MAX_TEXT = 256;
    private static final int OBSERVE_TICKS = 200;
    private static final int MAX_PREV = 3;
    private static final int MAX_TRACKED = 3000;
    /** 조회 응답 크기 상한 — 랑데부 메시지 하나에 담는다. */
    private static final int MAX_REPLY_BYTES = 48 * 1024;

    private static final class Hist {
        String text;
        long first;
        final List<Prev> prev = new ArrayList<>();
    }

    private record Prev(long at, String text) {}

    private record Raw(String key, String dim, String text, int x, int y, int z) {}

    private static final Map<String, Hist> history = new HashMap<>();
    private static int tickCount = 0;

    private SignSnapshot() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (inWorld() && DevBadge.isP2pSessionActive()) {
                if (++tickCount % OBSERVE_TICKS == 0) {
                    try {
                        observe();
                    } catch (Throwable ignored) {
                        // 부가 기능 — 다음 주기에 다시
                    }
                }
            } else {
                if (!history.isEmpty()) history.clear();
                tickCount = 0;
            }
        });
    }

    private static void observe() {
        List<Raw> raws = new ArrayList<>();
        scan(raws);
        long now = System.currentTimeMillis();
        for (Raw r : raws) {
            Hist h = history.get(r.key());
            if (h == null) {
                if (history.size() >= MAX_TRACKED) continue;
                h = new Hist();
                h.text = r.text();
                h.first = now;
                history.put(r.key(), h);
            } else if (!h.text.equals(r.text())) {
                h.prev.add(0, new Prev(now, h.text));
                while (h.prev.size() > MAX_PREV) h.prev.remove(h.prev.size() - 1);
                h.text = r.text();
            }
        }
    }

    /** 신고에 실을 주변 표지판(가까운 순, 최대 200개). 못 읽으면 빈 배열. */
    public static JsonArray capture() {
        JsonArray out = new JsonArray();
        try {
            double[] me = playerPos();
            if (me == null) return out;
            List<Raw> raws = new ArrayList<>();
            scan(raws);
            raws.sort(Comparator.comparingDouble(r -> sq(r.x() - me[0]) + sq(r.y() - me[1]) + sq(r.z() - me[2])));
            observe(); // 신고 직전 상태까지 이력에 넣는다
            for (int i = 0; i < Math.min(MAX_SIGNS, raws.size()); i++) {
                Raw r = raws.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("text", r.text());
                o.addProperty("dist", (int) Math.sqrt(sq(r.x() - me[0]) + sq(r.y() - me[1]) + sq(r.z() - me[2])));
                o.addProperty("d", r.dim());
                o.addProperty("x", r.x());
                o.addProperty("y", r.y());
                o.addProperty("z", r.z());
                Hist h = history.get(r.key());
                if (h != null) {
                    o.addProperty("first", h.first);
                    if (!h.prev.isEmpty()) {
                        JsonArray prev = new JsonArray();
                        for (Prev p : h.prev) {
                            JsonObject po = new JsonObject();
                            po.addProperty("t", p.at());
                            po.addProperty("text", p.text());
                            prev.add(po);
                        }
                        o.add("prev", prev);
                    }
                }
                out.add(o);
            }
            return out;
        } catch (Throwable t) {
            return new JsonArray();
        }
    }

    /** 방장 — 서버가 물은 좌표들({i,d,x,y,z})의 지금 표지판 글. 서버 스레드에서 읽는다(최대 5초 기다림). */
    public static JsonArray hostAnswer(JsonArray items) {
        try {
            return readOnServerThread(items);
        } catch (Throwable t) {
            return new JsonArray();
        }
    }

    private static JsonArray readItems(MinecraftServer server, JsonArray items) {
        JsonArray out = new JsonArray();
        int bytes = 0;
        for (JsonElement e : items) {
            if (out.size() >= MAX_SIGNS) break;
            try {
                JsonObject it = e.getAsJsonObject();
                String t = readAt(server, it.get("d").getAsString(), it.get("x").getAsInt(), it.get("y").getAsInt(), it.get("z").getAsInt());
                if (t == null) continue;
                if ((bytes += t.length() * 3 + 24) > MAX_REPLY_BYTES) break;
                JsonObject o = new JsonObject();
                o.addProperty("i", it.get("i").getAsInt());
                o.addProperty("text", t);
                out.add(o);
            } catch (RuntimeException ignored) {
                // 틀린 항목 하나는 건너뛴다
            }
        }
        return out;
    }

    /** "ResourceKey[minecraft:dimension / minecraft:overworld]" → "minecraft:overworld" (이미 짧으면 그대로). */
    private static String dimId(String s) {
        int i = s.lastIndexOf(" / ");
        if (i >= 0) s = s.substring(i + 3);
        int j = s.indexOf(']');
        return j >= 0 ? s.substring(0, j) : s;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String clip(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** 표지판 블록 엔티티의 글(앞+뒤, 줄은 " / " 로), 표지판이 아니거나 비었으면 null — SignEditLogMixin 이 쓴다. */
    public static String signText(Object be) {
        return be instanceof BlockEntity b ? textOf(b) : null;
    }

    private static JsonArray readOnServerThread(JsonArray items) throws Exception {
        MinecraftServer server = singleplayerServer();
        return server == null ? new JsonArray() : server.submit(() -> readItems(server, items)).get(5, TimeUnit.SECONDS);
    }

    //? if >=26.1 {
    /*private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null;
    }

    private static double[] playerPos() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null ? new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()} : null;
    }

    private static MinecraftServer singleplayerServer() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    private static String readAt(MinecraftServer server, String dim, int x, int y, int z) {
        for (ServerLevel w : server.getAllLevels()) {
            if (!dimId(String.valueOf(w.dimension())).equals(dim)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (!w.hasChunkAt(pos)) return null; // 안 불러온 청크는 건드리지 않는다(불러오면 렉)
            return textOf(w.getBlockEntity(pos));
        }
        return null;
    }

    private static void scan(List<Raw> found) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        String dim = dimId(String.valueOf(mc.level.dimension()));
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    String text = textOf(be);
                    if (text == null) continue;
                    int x = be.getBlockPos().getX(), y = be.getBlockPos().getY(), z = be.getBlockPos().getZ();
                    found.add(new Raw(dim + ":" + x + "," + y + "," + z, dim, text, x, y, z));
                }
            }
        }
    }
    *///?} else {
    private static boolean inWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.player != null;
    }

    private static double[] playerPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.player != null ? new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()} : null;
    }

    private static MinecraftServer singleplayerServer() {
        return MinecraftClient.getInstance().getServer();
    }

    private static String readAt(MinecraftServer server, String dim, int x, int y, int z) {
        for (ServerWorld w : server.getWorlds()) {
            if (!dimId(String.valueOf(w.getRegistryKey().getValue())).equals(dim)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (!w.isChunkLoaded(pos)) return null; // 안 불러온 청크는 건드리지 않는다(불러오면 렉)
            return textOf(w.getBlockEntity(pos));
        }
        return null;
    }

    private static void scan(List<Raw> found) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        String dim = dimId(String.valueOf(mc.world.getRegistryKey().getValue()));
        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    String text = textOf(be);
                    if (text == null) continue;
                    int x = be.getPos().getX(), y = be.getPos().getY(), z = be.getPos().getZ();
                    found.add(new Raw(dim + ":" + x + "," + y + "," + z, dim, text, x, y, z));
                }
            }
        }
    }
    //?}

    // 표지판 앞·뒷면 글 — 26.3 에서 앞/뒤가 SignTextSlot 으로 바뀌었다(서로 안 겹치는 블록 3개).
    //? if >=26.3 {
    /*private static String textOf(BlockEntity be) {
        if (!(be instanceof SignBlockEntity sign)) return null;
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.world.level.block.entity.SignTextSlot slot : net.minecraft.world.level.block.entity.SignTextSlot.values()) {
            for (Component line : sign.getText(slot).getMessages(false)) appendLine(sb, line.getString());
        }
        return sb.length() == 0 ? null : clip(sb.toString());
    }
    *///?}
    //? if >=26.1 <26.3 {
    /*private static String textOf(BlockEntity be) {
        if (!(be instanceof SignBlockEntity sign)) return null;
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.world.level.block.entity.SignText face : new net.minecraft.world.level.block.entity.SignText[]{sign.getFrontText(), sign.getBackText()}) {
            for (Component line : face.getMessages(false)) appendLine(sb, line.getString());
        }
        return sb.length() == 0 ? null : clip(sb.toString());
    }
    *///?}
    //? if <26.1 {
    private static String textOf(BlockEntity be) {
        if (!(be instanceof SignBlockEntity sign)) return null;
        StringBuilder sb = new StringBuilder();
        for (SignText face : new SignText[]{sign.getFrontText(), sign.getBackText()}) {
            for (Text line : face.getMessages(false)) appendLine(sb, line.getString());
        }
        return sb.length() == 0 ? null : clip(sb.toString());
    }
    //?}

    private static void appendLine(StringBuilder sb, String raw) {
        String s = raw.trim();
        if (!s.isEmpty()) sb.append(sb.length() > 0 ? " / " : "").append(s);
    }
}
