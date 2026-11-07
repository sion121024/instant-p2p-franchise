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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
*///?} else {
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
//?}

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 주변 책(1.5~) — 신고에 독서대·액자·떨어진 아이템·다른 사람 손·내 인벤토리의 책 내용을 싣는다(책으로 욕설·개인정보를
 * 퍼뜨리는 경우). {@link SignSnapshot} 과 같은 방식으로 10초마다 훑어 바뀐 이력(최근 2판)과 <b>사라진 책</b>(최근 20권,
 * 어디로 옮겨졌는지)을 기억한다 — 신고 직전에 책을 치워도 내용이 남는다.
 * <p>
 * 방장 쪽은 서버가 "이 책의 지금 해시"를 물으면 자기 월드에서 읽어 답한다({@link #hostAnswer}) — 내용을 다시 보내지 않고
 * 해시만 대조하므로 신고자가 책 내용을 지어내면 들통난다.
 */
public final class BookSnapshot {

    private static final int MAX_BOOKS = 40;
    private static final int RADIUS_CHUNKS = 32;
    private static final int MAX_PAGES_SENT = 12;
    private static final int MAX_PAGE_CHARS = 600;
    private static final int MAX_TOTAL_CHARS = 20_000;
    private static final int MAX_TITLE = 64;
    private static final int MAX_AUTHOR = 32;
    private static final double ENTITY_RADIUS = 64.0;
    private static final int OBSERVE_TICKS = 200;
    private static final int MAX_PREV = 2;
    private static final int PREV_PAGES = 4;
    private static final int PREV_PAGE_CHARS = 300;
    private static final int MAX_TRACKED = 1500;
    private static final int MAX_GONE = 20;

    /**
     * 책 하나. src = lectern(독서대)/frame(액자)/item(떨어진 아이템)/held(남의 손)/inv(내 인벤토리).
     * 독서대는 dim+좌표로, 나머지는 엔티티 uuid+slot 으로 구분한다. kind = written(서명된 책)/writable(책과 깃펜).
     */
    record Book(String src, String dim, int x, int y, int z, String uuid, int slot, String kind, String title,
                String author, int gen, List<String> pages, double dist) {}

    private record Gone(long at, Book last, String movedTo) {}

    private static final class Hist {
        String hash;
        long first;
        final List<PrevBook> prev = new ArrayList<>();
    }

    private record PrevBook(long at, String hash, String kind, String title, String author, List<String> pages) {}

    private static final Map<String, Hist> history = new HashMap<>();
    private static int tickCount = 0;
    private static final Map<String, Book> lastSeen = new HashMap<>();
    private static final List<Gone> goneList = new ArrayList<>();
    private static String lastDim = null;

    private BookSnapshot() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (inWorld() && DevBadge.isP2pSessionActive()) {
                // 표지판(SignSnapshot)과 반 주기 엇갈려 같은 틱에 몰리지 않게
                if (++tickCount % OBSERVE_TICKS == OBSERVE_TICKS / 2) {
                    try {
                        observe();
                    } catch (Throwable ignored) {
                        // 부가 기능 — 다음 주기에 다시
                    }
                }
            } else {
                if (!history.isEmpty()) {
                    history.clear();
                    lastSeen.clear();
                }
                goneList.clear();
                lastDim = null;
                tickCount = 0;
            }
        });
    }

    private static String keyOf(Book b) {
        return b.dim() != null ? b.src() + ":" + b.dim() + ":" + b.x() + "," + b.y() + "," + b.z()
                : b.src() + ":" + b.uuid() + ":" + b.slot();
    }

    private static void observe() {
        List<Book> books = new ArrayList<>();
        scan(books);
        observe(books);
    }

    /** 사라졌을 때 기록할 만큼 가까웠는지 — 멀리 있던 책이 시야(로딩 범위) 밖으로 나간 건 "사라짐"이 아니다. */
    private static boolean nearEnough(Book b) {
        if (b.src().equals("inv")) return true;
        return b.dist() <= (b.src().equals("lectern") ? 96.0 : 48.0);
    }

    private static void observe(List<Book> books) {
        long now = System.currentTimeMillis();
        String dimNow = curDim();
        if (lastDim != null && dimNow != null && !lastDim.equals(dimNow)) { // 차원을 옮기면 이력을 새로 시작
            history.clear();
            lastSeen.clear();
        }
        lastDim = dimNow;

        for (Book b : books) {
            String key = keyOf(b);
            String h = hash(b);
            Hist hs = history.get(key);
            if (hs == null) {
                if (history.size() >= MAX_TRACKED) continue;
                hs = new Hist();
                hs.hash = h;
                hs.first = now;
                history.put(key, hs);
            } else if (!hs.hash.equals(h)) {
                // 내용이 바뀌었다 — 바뀌기 전 판을 앞쪽 몇 쪽만 남긴다
                Book old = lastSeen.get(key);
                if (old != null) {
                    List<String> pg = new ArrayList<>();
                    for (int i = 0; i < Math.min(PREV_PAGES, old.pages().size()); i++) pg.add(clip(old.pages().get(i), PREV_PAGE_CHARS));
                    hs.prev.add(0, new PrevBook(now, hs.hash, old.kind(), clip(old.title(), MAX_TITLE), clip(old.author(), MAX_AUTHOR), pg));
                    while (hs.prev.size() > MAX_PREV) hs.prev.remove(hs.prev.size() - 1);
                }
                hs.hash = h;
            }
            lastSeen.put(key, b);
        }

        // 이번에 안 보인 책 — 가까이 있었으면 "사라짐"으로 남긴다(같은 내용이 다른 곳에 보이면 거기로 옮겨진 것)
        Set<String> present = new HashSet<>();
        for (Book b : books) present.add(keyOf(b));
        Iterator<Map.Entry<String, Hist>> it = history.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Hist> e = it.next();
            if (present.contains(e.getKey())) continue;
            Book last = lastSeen.remove(e.getKey());
            it.remove();
            if (last == null || !nearEnough(last)) continue;
            String moved = null;
            for (Book b : books) {
                if (hash(b).equals(e.getValue().hash)) {
                    moved = b.src();
                    break;
                }
            }
            // 내 인벤토리 안에서 칸만 옮긴 건 사라진 게 아니다
            if (last.src().equals("inv") && "inv".equals(moved)) continue;
            goneList.add(new Gone(now, last, moved));
            while (goneList.size() > MAX_GONE) goneList.remove(0);
        }
    }

    /** 종류·제목·저자·쪽 내용의 SHA-256 — 방장 월드의 책과 대조할 때 쓴다(서버도 같은 방식으로 계산). */
    static String hash(Book b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.kind()).append('\n').append(b.title()).append('\n').append(b.author());
        for (String p : b.pages()) sb.append('\u0001').append(p.replace("\r\n", "\n").stripTrailing());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 책 요약 JSON(앞 4쪽) — 방장 이벤트 기록의 책 수정(HostEventLog.book)에 쓴다. */
    static String describe(Book b) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", b.kind());
        o.addProperty("title", clip(b.title(), MAX_TITLE));
        o.addProperty("author", clip(b.author(), MAX_AUTHOR));
        o.addProperty("h", hash(b));
        o.addProperty("n", b.pages().size());
        JsonArray pages = new JsonArray();
        for (int p = 0; p < Math.min(PREV_PAGES, b.pages().size()); p++) pages.add(clip(b.pages().get(p), PREV_PAGE_CHARS));
        o.add("pages", pages);
        return o.toString();
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 신고에 실을 주변 책(가까운 순 40권, 쪽 내용은 합쳐 2만 자까지) + 최근 사라진 책. 못 읽으면 빈 배열. */
    public static JsonArray capture() {
        JsonArray out = new JsonArray();
        try {
            List<Book> books = new ArrayList<>();
            scan(books);
            books.sort(Comparator.comparingDouble(Book::dist));
            observe(books);
            int total = 0;
            for (int i = 0; i < Math.min(MAX_BOOKS, books.size()); i++) {
                Book b = books.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("src", b.src());
                if (b.dim() != null) {
                    o.addProperty("d", b.dim());
                    o.addProperty("x", b.x());
                    o.addProperty("y", b.y());
                    o.addProperty("z", b.z());
                }
                if (b.uuid() != null) {
                    o.addProperty("u", b.uuid());
                    o.addProperty("slot", b.slot());
                }
                o.addProperty("kind", b.kind());
                o.addProperty("title", clip(b.title(), MAX_TITLE));
                o.addProperty("author", clip(b.author(), MAX_AUTHOR));
                o.addProperty("gen", b.gen());
                o.addProperty("dist", (int) b.dist());
                o.addProperty("n", b.pages().size());
                o.addProperty("h", hash(b));
                Hist hs = history.get(keyOf(b));
                if (hs != null) {
                    o.addProperty("first", hs.first);
                    if (!hs.prev.isEmpty()) {
                        JsonArray prev = new JsonArray();
                        for (PrevBook pb : hs.prev) {
                            JsonObject po = new JsonObject();
                            po.addProperty("t", pb.at());
                            po.addProperty("h", pb.hash());
                            po.addProperty("kind", pb.kind());
                            po.addProperty("title", pb.title());
                            po.addProperty("author", pb.author());
                            JsonArray pg = new JsonArray();
                            for (String t : pb.pages()) pg.add(t);
                            po.add("pages", pg);
                            prev.add(po);
                        }
                        o.add("prev", prev);
                    }
                }
                JsonArray pages = new JsonArray();
                for (int p = 0; p < Math.min(MAX_PAGES_SENT, b.pages().size()); p++) {
                    String t = clip(b.pages().get(p), MAX_PAGE_CHARS);
                    if ((total += t.length()) > MAX_TOTAL_CHARS) break;
                    pages.add(t);
                }
                o.add("pages", pages);
                out.add(o);
            }
            for (Gone g : goneList) {
                Book b = g.last();
                JsonObject o = new JsonObject();
                o.addProperty("src", b.src());
                o.addProperty("gone", true);
                o.addProperty("at", g.at());
                if (g.movedTo() != null) o.addProperty("moved", g.movedTo());
                o.addProperty("kind", b.kind());
                o.addProperty("title", clip(b.title(), MAX_TITLE));
                o.addProperty("author", clip(b.author(), MAX_AUTHOR));
                o.addProperty("n", b.pages().size());
                o.addProperty("h", hash(b));
                JsonArray pages = new JsonArray();
                for (int p = 0; p < Math.min(PREV_PAGES, b.pages().size()); p++) pages.add(clip(b.pages().get(p), PREV_PAGE_CHARS));
                o.add("pages", pages);
                out.add(o);
            }
            return out;
        } catch (Throwable t) {
            return new JsonArray();
        }
    }

    /** 방장 — 서버가 물은 책들({i,src,...})의 지금 해시. 서버 스레드에서 읽는다(최대 5초 기다림). */
    public static JsonArray hostAnswer(JsonArray items) {
        try {
            MinecraftServer server = singleplayerServer();
            if (server == null) return new JsonArray();
            return server.submit(() -> readItems(server, items)).get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return new JsonArray();
        }
    }

    private static JsonArray readItems(MinecraftServer server, JsonArray items) {
        JsonArray out = new JsonArray();
        for (JsonElement e : items) {
            if (out.size() >= MAX_BOOKS) break;
            try {
                JsonObject it = e.getAsJsonObject();
                Book b = readOne(server, it);
                if (b == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("i", it.get("i").getAsInt());
                o.addProperty("h", hash(b));
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

    private static String normTitle(String s) {
        return s == null ? "" : s.strip();
    }

    //? if >=26.1 {
    /*private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null;
    }

    private static String curDim() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : dimId(String.valueOf(mc.level.dimension()));
    }

    private static MinecraftServer singleplayerServer() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    // 아이템 하나의 책 요약 JSON(책이 아니면 "") — BookEditLogMixin 이 수정 전후를 남길 때 쓴다.
    public static String describeStack(Object stack) {
        if (!(stack instanceof ItemStack st)) return "";
        Book b = fromStack(st, "x", null, 0, 0, 0, null, 0, 0.0);
        return b == null ? "" : describe(b);
    }

    private static Book fromStack(ItemStack st, String src, String dim, int x, int y, int z, String uuid, int slot, double dist) {
        if (st == null || st.isEmpty()) return null;
        WrittenBookContent written = st.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            List<String> pages = new ArrayList<>();
            for (Filterable<Component> p : written.pages()) pages.add(p.raw().getString());
            return new Book(src, dim, x, y, z, uuid, slot, "written", normTitle(written.title().raw()),
                    normTitle(written.author()), written.generation(), pages, dist);
        }
        WritableBookContent writable = st.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable == null) return null;
        List<String> pages = new ArrayList<>();
        for (Filterable<String> p : writable.pages()) pages.add(p.raw());
        return new Book(src, dim, x, y, z, uuid, slot, "writable", "", "", 0, pages, dist);
    }

    private static void scan(List<Book> found) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        String dim = dimId(String.valueOf(mc.level.dimension()));
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof LecternBlockEntity l)) continue;
                    int x = be.getBlockPos().getX(), y = be.getBlockPos().getY(), z = be.getBlockPos().getZ();
                    Book b = fromStack(l.getBook(), "lectern", dim, x, y, z, null, 0, Math.sqrt(sq(x - px) + sq(y - py) + sq(z - pz)));
                    if (b != null) found.add(b);
                }
            }
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            double d = Math.sqrt(sq(e.getX() - px) + sq(e.getY() - py) + sq(e.getZ() - pz));
            if (d > ENTITY_RADIUS) continue;
            String u = e.getUUID().toString();
            if (e instanceof ItemFrame f) {
                Book b = fromStack(f.getItem(), "frame", null, 0, 0, 0, u, 0, d);
                if (b != null) found.add(b);
            } else if (e instanceof ItemEntity it) {
                Book b = fromStack(it.getItem(), "item", null, 0, 0, 0, u, 0, d);
                if (b != null) found.add(b);
            } else if (e instanceof Player pl && pl != mc.player) {
                Book m = fromStack(pl.getMainHandItem(), "held", null, 0, 0, 0, u, 0, d);
                if (m != null) found.add(m);
                Book o = fromStack(pl.getOffhandItem(), "held", null, 0, 0, 0, u, 1, d);
                if (o != null) found.add(o);
            }
        }
        String me = mc.player.getUUID().toString();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            Book b = fromStack(mc.player.getInventory().getItem(i), "inv", null, 0, 0, 0, me, i, 0.0);
            if (b != null) found.add(b);
        }
    }

    private static Book readOne(MinecraftServer server, JsonObject it) {
        String src = it.get("src").getAsString();
        if (src.equals("lectern")) {
            String dim = it.get("d").getAsString();
            for (ServerLevel w : server.getAllLevels()) {
                if (!dimId(String.valueOf(w.dimension())).equals(dim)) continue;
                BlockPos pos = new BlockPos(it.get("x").getAsInt(), it.get("y").getAsInt(), it.get("z").getAsInt());
                if (!w.hasChunkAt(pos)) return null;
                return w.getBlockEntity(pos) instanceof LecternBlockEntity l ? fromStack(l.getBook(), src, dim, 0, 0, 0, null, 0, 0.0) : null;
            }
            return null;
        }
        UUID id = UUID.fromString(it.get("u").getAsString());
        int slot = it.has("slot") ? it.get("slot").getAsInt() : 0;
        if (src.equals("held") || src.equals("inv")) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return null;
            ItemStack st = src.equals("inv")
                    ? (slot >= 0 && slot < p.getInventory().getContainerSize() ? p.getInventory().getItem(slot) : null)
                    : (slot == 0 ? p.getMainHandItem() : p.getOffhandItem());
            return fromStack(st, src, null, 0, 0, 0, null, 0, 0.0);
        }
        for (ServerLevel w : server.getAllLevels()) {
            Entity e = w.getEntity(id);
            if (e instanceof ItemFrame f && src.equals("frame")) return fromStack(f.getItem(), src, null, 0, 0, 0, null, 0, 0.0);
            if (e instanceof ItemEntity ie && src.equals("item")) return fromStack(ie.getItem(), src, null, 0, 0, 0, null, 0, 0.0);
        }
        return null;
    }
    *///?} else {
    private static boolean inWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.player != null;
    }

    private static String curDim() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? null : dimId(String.valueOf(mc.world.getRegistryKey().getValue()));
    }

    private static MinecraftServer singleplayerServer() {
        return MinecraftClient.getInstance().getServer();
    }

    /** 아이템 하나의 책 요약 JSON(책이 아니면 "") — BookEditLogMixin 이 수정 전후를 남길 때 쓴다. */
    public static String describeStack(Object stack) {
        if (!(stack instanceof ItemStack st)) return "";
        Book b = fromStack(st, "x", null, 0, 0, 0, null, 0, 0.0);
        return b == null ? "" : describe(b);
    }

    private static Book fromStack(ItemStack st, String src, String dim, int x, int y, int z, String uuid, int slot, double dist) {
        if (st == null || st.isEmpty()) return null;
        WrittenBookContentComponent written = st.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            List<String> pages = new ArrayList<>();
            for (RawFilteredPair<Text> p : written.pages()) pages.add(p.raw().getString());
            return new Book(src, dim, x, y, z, uuid, slot, "written", normTitle(written.title().raw()),
                    normTitle(written.author()), written.generation(), pages, dist);
        }
        WritableBookContentComponent writable = st.get(DataComponentTypes.WRITABLE_BOOK_CONTENT);
        if (writable == null) return null;
        List<String> pages = new ArrayList<>();
        for (RawFilteredPair<String> p : writable.pages()) pages.add(p.raw());
        return new Book(src, dim, x, y, z, uuid, slot, "writable", "", "", 0, pages, dist);
    }

    private static void scan(List<Book> found) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        String dim = dimId(String.valueOf(mc.world.getRegistryKey().getValue()));
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof LecternBlockEntity l)) continue;
                    int x = be.getPos().getX(), y = be.getPos().getY(), z = be.getPos().getZ();
                    Book b = fromStack(l.getBook(), "lectern", dim, x, y, z, null, 0, Math.sqrt(sq(x - px) + sq(y - py) + sq(z - pz)));
                    if (b != null) found.add(b);
                }
            }
        }
        for (Entity e : mc.world.getEntities()) {
            double d = Math.sqrt(sq(e.getX() - px) + sq(e.getY() - py) + sq(e.getZ() - pz));
            if (d > ENTITY_RADIUS) continue;
            String u = e.getUuid().toString();
            if (e instanceof ItemFrameEntity f) {
                Book b = fromStack(f.getHeldItemStack(), "frame", null, 0, 0, 0, u, 0, d);
                if (b != null) found.add(b);
            } else if (e instanceof ItemEntity it) {
                Book b = fromStack(it.getStack(), "item", null, 0, 0, 0, u, 0, d);
                if (b != null) found.add(b);
            } else if (e instanceof PlayerEntity pl && pl != mc.player) {
                Book m = fromStack(pl.getMainHandStack(), "held", null, 0, 0, 0, u, 0, d);
                if (m != null) found.add(m);
                Book o = fromStack(pl.getOffHandStack(), "held", null, 0, 0, 0, u, 1, d);
                if (o != null) found.add(o);
            }
        }
        String me = mc.player.getUuid().toString();
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            Book b = fromStack(mc.player.getInventory().getStack(i), "inv", null, 0, 0, 0, me, i, 0.0);
            if (b != null) found.add(b);
        }
    }

    private static Book readOne(MinecraftServer server, JsonObject it) {
        String src = it.get("src").getAsString();
        if (src.equals("lectern")) {
            String dim = it.get("d").getAsString();
            for (ServerWorld w : server.getWorlds()) {
                if (!dimId(String.valueOf(w.getRegistryKey().getValue())).equals(dim)) continue;
                BlockPos pos = new BlockPos(it.get("x").getAsInt(), it.get("y").getAsInt(), it.get("z").getAsInt());
                if (!w.isChunkLoaded(pos)) return null;
                return w.getBlockEntity(pos) instanceof LecternBlockEntity l ? fromStack(l.getBook(), src, dim, 0, 0, 0, null, 0, 0.0) : null;
            }
            return null;
        }
        UUID id = UUID.fromString(it.get("u").getAsString());
        int slot = it.has("slot") ? it.get("slot").getAsInt() : 0;
        if (src.equals("held") || src.equals("inv")) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) return null;
            ItemStack st = src.equals("inv")
                    ? (slot >= 0 && slot < p.getInventory().size() ? p.getInventory().getStack(slot) : null)
                    : (slot == 0 ? p.getMainHandStack() : p.getOffHandStack());
            return fromStack(st, src, null, 0, 0, 0, null, 0, 0.0);
        }
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(id);
            if (e instanceof ItemFrameEntity f && src.equals("frame")) return fromStack(f.getHeldItemStack(), src, null, 0, 0, 0, null, 0, 0.0);
            if (e instanceof ItemEntity ie && src.equals("item")) return fromStack(ie.getStack(), src, null, 0, 0, 0, null, 0, 0.0);
        }
        return null;
    }
    //?}
}
