package kfc.udp.client.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//? if >=26.1 {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
//?}

/**
 * 리소스팩 연동(가맹점 F4.0) — 접속자의 리소스팩이 <b>방장 리소스팩을 그대로 따라간다</b>(P2P 연결로 전달).
 * <p>
 * 바닐라 서버 리소스팩은 접속자가 URL 에서 내려받는 방식이라 P2P 방(방장 PC = 서버)에서는 못 쓴다. 그래서:
 * <ol>
 *   <li>방장이 방 설정에서 「리소스팩 공유」를 켜면, 방장 게임에 켜진 파일 리소스팩 <b>전부를 순서 그대로</b>
 *       ({@code resourcepacks/} 안의 zip·폴더 — 폴더는 zip 으로 묶는다, 최대 {@link #MAX_PACKS}개·합계 {@link #MAX_TOTAL})
 *       목록(manifest)으로 만들어 접속자에게 보낸다. 방장이 팩을 켜고 끄거나 순서를 바꾸면 2초 안에 새 목록을 보낸다.
 *       공유를 끄면 빈 목록을 보내 접속자 쪽에서도 빠진다.</li>
 *   <li>접속자는 <b>접속마다 처음 한 번</b> "방장 리소스팩을 따라갈까요?"에 답한다. 따라가기로 하면 그 뒤 목록이 바뀔 때마다
 *       묻지 않고 그대로 맞춘다. 없는 팩만 조각({@link #CHUNK} 바이트)을 {@link #WINDOW}개씩 요청해 받고, SHA-1 을 확인해
 *       config/instant-p2p/pack-cache 에 둔다(최근 {@link #CACHE_KEEP}개). 이미 받은 팩이면 바로 적용.</li>
 *   <li>적용은 바닐라 서버 리소스팩 장치({@code DownloadedPackSource.pushLocalPack/popPack},
 *       Yarn {@code ServerResourcePackLoader.addResourcePack(UUID, Path)/remove}) — 방에서 나가면 바닐라가 자동으로 뺀다.</li>
 * </ol>
 * 방장 보호: 접속자별 동시 요청 {@code WINDOW*2}, 같은 팩은 방 동안 팩 크기의 3배까지만 보낸다.
 * 접속자 보호: 목록·크기·조각 길이·SHA-1 을 확인하고, 묻는 창은 접속마다 한 번.
 */
public final class PackShare {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-pack");

    static final int CHUNK = 192 * 1024;
    static final int WINDOW = 4;
    static final long MAX_PACK = 100L * 1024 * 1024;
    static final long MAX_TOTAL = 250L * 1024 * 1024;
    static final int MAX_PACKS = 8;
    static final int CACHE_KEEP = 12;
    static final int META_MAX = 2048;
    private static final Path CACHE_DIR = Path.of("config", "instant-p2p", "pack-cache");
    private static final Path SHARE_DIR = Path.of("config", "instant-p2p", "pack-share");

    private PackShare() {}

    /** 팩 하나. sha1 은 소문자 40글자. file 은 방장 쪽에서만 있다. */
    record Pack(String sha1, String name, long size, Path file) {
        int total() { return (int) ((size + CHUNK - 1) / CHUNK); }
    }

    // ── 방장 쪽 상태 ──────────────────────────────────────────────────────────
    /** 지금 공유 중인 목록(아래 → 위 순서). 공유 꺼짐이면 빈 목록. */
    private static volatile List<Pack> shared = List.of();
    /** 팩 id|수정 시각 → 준비한 팩(다시 해시하지 않게). */
    private static final Map<String, Pack> prepared = new ConcurrentHashMap<>();
    /** 방장 게임에 켜진 리소스팩 id — 클라이언트 스레드에서만 떠 둔다. */
    private static volatile List<String> packIds = List.of();
    private static int tickCounter;
    private static final Map<String, Long> served = new ConcurrentHashMap<>();
    private static final Map<UUID, java.util.concurrent.atomic.AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-pack-io");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-pack-later");
        t.setDaemon(true);
        return t;
    });

    // ── 접속자 쪽 상태(클라이언트 스레드에서만) ─────────────────────────────────
    private static final class Download {
        final Pack pack;
        final Path tmp;
        final RandomAccessFile out;
        final boolean[] got;
        final long[] askedAt;
        int nextIndex, received, lastPct = -1, retries;
        long lastChunkAt = System.currentTimeMillis();

        Download(Pack pack) throws IOException {
            this.pack = pack;
            Files.createDirectories(CACHE_DIR);
            this.tmp = CACHE_DIR.resolve(pack.sha1() + ".part");
            this.out = new RandomAccessFile(tmp.toFile(), "rw");
            this.out.setLength(pack.size());
            this.got = new boolean[pack.total()];
            this.askedAt = new long[pack.total()];
        }
    }

    private static Download download;
    /** 방장이 보낸 최신 목록(접속자 쪽). */
    private static List<Pack> wanted = List.of();
    /** 지금 적용해 둔 방장 팩 sha1(아래 → 위). */
    private static List<String> appliedSet = List.of();
    /** 이번 접속에서 따라가기로 했는지: null = 아직 안 물음, TRUE/FALSE = 답. */
    private static Boolean follow;
    private static boolean asking;
    private static final long STALL_MS = 15_000;
    private static final int MAX_RETRIES = 4;

    // ═══════════════════════════════════════════════════════════════════════
    //  방장
    // ═══════════════════════════════════════════════════════════════════════

    /** 방을 열 때·설정을 바꿀 때·방장이 팩을 바꿨을 때(클라이언트 스레드). */
    public static void onSettingsChanged() {
        packIds = P2PConfig.isSharePack() ? List.copyOf(selectedPackIds()) : List.of();
        IO.execute(() -> {
            List<Pack> before = shared;
            shared = P2PConfig.isSharePack() ? prepareAll(packIds) : List.of();
            if (!sameSet(before, shared)) withGuests(PackShare::offer);
        });
    }

    /** 2초마다(클라이언트 틱) — 공유 중이고 방을 열어 둔 동안 방장 팩 목록이 바뀌었으면 다시 보낸다. */
    static void onClientTick() {
        if (++tickCounter < 40) return;
        tickCounter = 0;
        if (!P2PConfig.isSharePack() || !hostingServer()) return;
        List<String> now = selectedPackIds();
        if (!now.equals(packIds)) onSettingsChanged();
    }

    private static boolean sameSet(List<Pack> a, List<Pack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).sha1().equals(b.get(i).sha1())) return false;
        return true;
    }

    /** 켜진 파일 팩 전부를 아래 → 위 순서로 준비(IO 스레드). 너무 크면 아래(우선순위 낮은) 것부터 뺀다. */
    private static synchronized List<Pack> prepareAll(List<String> ids) {
        List<Pack> out = new ArrayList<>();
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks").normalize();
        for (String id : ids) {
            if (id == null || !id.startsWith("file/")) continue;
            try {
                Path src = dir.resolve(id.substring("file/".length())).normalize();
                if (!src.startsWith(dir) || !Files.exists(src)) continue;
                String key = id + "|" + Files.getLastModifiedTime(src).toMillis();
                Pack p = prepared.get(key);
                if (p == null) {
                    Path file = Files.isDirectory(src) ? zipFolder(src) : src;
                    long size = Files.size(file);
                    if (size <= 0 || size > MAX_PACK) {
                        LOG.warn("[pack] 너무 크거나 비어 있어 뺀다: {} ({} 바이트)", src.getFileName(), size);
                        continue;
                    }
                    String name = src.getFileName().toString();
                    if (name.length() > 64) name = name.substring(0, 64);
                    p = new Pack(sha1(file), name.replace('\t', ' ').replace('\n', ' '), size, file);
                    prepared.put(key, p);
                }
                out.add(p);
            } catch (Exception e) {
                LOG.warn("[pack] 팩 준비 실패({}): {}", id, e.toString());
            }
        }
        // 위(우선순위 높은) 것을 남기고 개수·합계 상한에 맞춘다
        while (out.size() > MAX_PACKS || total(out) > MAX_TOTAL) out.remove(0);
        if (!out.isEmpty()) LOG.info("[pack] 공유 목록: {}개, {} KB", out.size(), total(out) / 1024);
        return List.copyOf(out);
    }

    private static long total(List<Pack> l) {
        long t = 0;
        for (Pack p : l) t += p.size();
        return t;
    }

    private static Path zipFolder(Path src) throws IOException {
        Files.createDirectories(SHARE_DIR);
        String base = HexFormat.of().formatHex(java.util.Arrays.copyOf(
                sha1Bytes(src.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8)), 8));
        Path out = SHARE_DIR.resolve(base + ".zip");
        Path tmp = SHARE_DIR.resolve(base + ".zip.tmp");
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp));
             Stream<Path> walk = Files.walk(src)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path f : files) {
                String rel = src.relativize(f).toString().replace('\\', '/');
                total += Files.size(f);
                if (total > MAX_PACK) throw new IOException("too big");
                ZipEntry e = new ZipEntry(rel);
                e.setTime(0); // 같은 내용이면 같은 해시 — 접속자 캐시가 맞게
                zip.putNextEntry(e);
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    private static byte[] sha1Bytes(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha1(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static boolean isHost(UUID id) {
        String me = MojangAuth.currentUuidOrNull();
        return me != null && me.replace("-", "").equalsIgnoreCase(id.toString().replace("-", ""));
    }

    /** 접속자가 들어오면(서버 스레드) — 3초 뒤 지금 목록을 보낸다. 방장 자신에게는 보내지 않는다. */
    static void onGuestJoin(Object player, UUID id) {
        if (!P2PConfig.isSharePack() || isHost(id)) return;
        LATER.schedule(() -> IO.execute(() -> offer(player)), 3, TimeUnit.SECONDS);
    }

    private static String manifestMeta(List<Pack> l) {
        StringBuilder b = new StringBuilder();
        for (Pack p : l) {
            if (b.length() > 0) b.append('\n');
            b.append(p.sha1()).append('\t').append(p.size()).append('\t').append(p.name());
        }
        return b.length() > META_MAX ? "" : b.toString();
    }

    private static void offer(Object player) {
        if (isGone(player) || !canSend(player)) return;
        List<Pack> l = shared;
        send(player, new Data(Data.KIND_MANIFEST, "", manifestMeta(l), l.size(), 0, 0, new byte[0]));
    }

    private static Pack sharedPack(String sha1) {
        for (Pack p : shared) if (p.sha1().equals(sha1)) return p;
        return null;
    }

    /** 접속자의 조각 요청(네트워크 스레드) — 확인하고 IO 스레드에서 읽어 보낸다. */
    static void onRequest(Object player, UUID id, String sha1, int index) {
        Pack s = sharedPack(sha1);
        if (s == null || index < 0 || index >= s.total() || !P2PConfig.isSharePack()) return;
        java.util.concurrent.atomic.AtomicInteger busy = inFlight.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicInteger());
        if (busy.incrementAndGet() > WINDOW * 2) {
            busy.decrementAndGet();
            return; // 창을 안 지키는 요청 — 버린다(정상 클라이언트는 다시 요청한다)
        }
        long used = served.merge(id + "|" + sha1, (long) CHUNK, Long::sum);
        if (used > s.size() * 3 + CHUNK) {
            busy.decrementAndGet();
            if (used < s.size() * 3 + CHUNK * 2L) LOG.warn("[pack] {} 이(가) 팩을 너무 많이 받아 가 더 보내지 않는다", id);
            return;
        }
        if (served.size() > 4096) served.clear();
        IO.execute(() -> {
            try (RandomAccessFile in = new RandomAccessFile(s.file().toFile(), "r")) {
                long off = (long) index * CHUNK;
                int len = (int) Math.min(CHUNK, s.size() - off);
                byte[] b = new byte[len];
                in.seek(off);
                in.readFully(b);
                if (!isGone(player)) send(player, new Data(Data.KIND_CHUNK, s.sha1(), "", s.size(), s.total(), index, b));
            } catch (IOException e) {
                LOG.warn("[pack] 조각 읽기 실패: {}", e.toString());
            } finally {
                busy.decrementAndGet();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  접속자(전부 클라이언트 스레드)
    // ═══════════════════════════════════════════════════════════════════════

    static void onData(Data d) {
        if (d.kind() == Data.KIND_MANIFEST) onManifest(d);
        else if (d.kind() == Data.KIND_CHUNK) onChunk(d);
    }

    /** 방장 목록 파싱 — 형식이 틀리면 null. */
    private static List<Pack> parseManifest(String meta) {
        List<Pack> out = new ArrayList<>();
        if (meta == null || meta.isEmpty()) return out;
        String[] lines = meta.split("\n");
        if (lines.length > MAX_PACKS) return null;
        long sum = 0;
        for (String line : lines) {
            String[] f = line.split("\t", 3);
            if (f.length != 3 || !validSha1(f[0])) return null;
            long size;
            try { size = Long.parseLong(f[1]); } catch (NumberFormatException e) { return null; }
            if (size <= 0 || size > MAX_PACK) return null;
            sum += size;
            out.add(new Pack(f[0], f[2].replaceAll("[§\\p{Cntrl}]", ""), size, null));
        }
        return sum > MAX_TOTAL ? null : out;
    }

    private static void onManifest(Data d) {
        List<Pack> l = parseManifest(d.meta());
        if (l == null) return;
        wanted = l;
        if (l.isEmpty()) {
            cancelDownload();
            applySet(List.of()); // 방장이 공유를 끔·팩을 다 뺌 → 접속자도 뺀다
            return;
        }
        if (Boolean.FALSE.equals(follow)) return;
        if (follow == null) {
            List<Pack> missing = missing(l);
            if (missing.isEmpty()) { // 예전에 받아 둔 팩뿐이면 묻지 않고 맞춘다
                follow = Boolean.TRUE;
                sync();
                return;
            }
            if (asking) return;
            asking = true;
            StringBuilder names = new StringBuilder();
            for (Pack p : l) {
                if (names.length() > 0) names.append(", ");
                names.append(p.name());
            }
            String mb = String.format("%.1f", total(missing) / 1048576.0);
            askYesNo(L10n.t("pack.offer.title"),
                    L10n.t("pack.offer.body").replace("{name}", names.toString()).replace("{size}", mb), yes -> {
                        asking = false;
                        follow = yes;
                        if (yes) sync();
                    });
            return;
        }
        sync();
    }

    private static List<Pack> missing(List<Pack> l) {
        List<Pack> m = new ArrayList<>();
        for (Pack p : l) if (!Files.isRegularFile(CACHE_DIR.resolve(p.sha1() + ".zip"))) m.add(p);
        return m;
    }

    /** 지금 원하는 목록에 맞춘다 — 없는 팩을 하나씩 받고, 다 있으면 적용한다. */
    private static void sync() {
        List<Pack> l = wanted;
        List<Pack> m = missing(l);
        if (m.isEmpty()) {
            cancelDownload();
            List<String> set = new ArrayList<>();
            for (Pack p : l) set.add(p.sha1());
            applySet(set);
            return;
        }
        Pack next = m.get(0);
        if (download != null && download.pack.sha1().equals(next.sha1())) return; // 이미 받는 중
        cancelDownload();
        try {
            download = new Download(next);
            for (int i = 0; i < WINDOW; i++) requestNext();
            scheduleStallCheck(download);
        } catch (IOException e) {
            download = null;
            LOG.warn("[pack] 받을 준비 실패: {}", e.toString());
        }
    }

    private static void requestNext() {
        Download dl = download;
        if (dl == null) return;
        while (dl.nextIndex < dl.got.length && dl.got[dl.nextIndex]) dl.nextIndex++;
        if (dl.nextIndex >= dl.got.length) return;
        int i = dl.nextIndex++;
        dl.askedAt[i] = System.currentTimeMillis();
        sendToHost(new Req(dl.pack.sha1(), i));
    }

    /** 5초마다 — 조각이 한동안 안 오면 못 받은 조각을 다시 요청하고, 몇 번 해도 안 되면 그만둔다. */
    private static void scheduleStallCheck(Download mine) {
        LATER.schedule(() -> StreamerRelay.runOnClient(() -> {
            Download dl = download;
            if (dl == null || dl != mine) return;
            long now = System.currentTimeMillis();
            if (now - dl.lastChunkAt >= STALL_MS) {
                if (++dl.retries > MAX_RETRIES) {
                    cancelDownload();
                    overlay(L10n.t("pack.bad"));
                    return;
                }
                dl.lastChunkAt = now;
                int asked = 0;
                for (int i = 0; i < dl.got.length && asked < WINDOW; i++) {
                    if (!dl.got[i] && dl.askedAt[i] != 0) {
                        dl.askedAt[i] = now;
                        sendToHost(new Req(dl.pack.sha1(), i));
                        asked++;
                    }
                }
                for (; asked < WINDOW; asked++) requestNext();
            }
            scheduleStallCheck(mine);
        }), 5, TimeUnit.SECONDS);
    }

    private static void onChunk(Data d) {
        Download dl = download;
        if (dl == null || !dl.pack.sha1().equals(d.sha1()) || d.index() < 0 || d.index() >= dl.got.length || dl.got[d.index()]) return;
        long off = (long) d.index() * CHUNK;
        int want = (int) Math.min(CHUNK, dl.pack.size() - off);
        if (d.bytes().length != want) return;
        try {
            dl.out.seek(off);
            dl.out.write(d.bytes());
        } catch (IOException e) {
            cancelDownload();
            return;
        }
        dl.got[d.index()] = true;
        dl.received++;
        dl.lastChunkAt = System.currentTimeMillis();
        dl.retries = 0;
        int pct = (int) (dl.received * 100L / dl.got.length);
        if (pct / 10 != dl.lastPct / 10) {
            dl.lastPct = pct;
            overlay(L10n.t("pack.progress").replace("{name}", dl.pack.name()).replace("{pct}", Integer.toString(pct)));
        }
        if (dl.received < dl.got.length) {
            requestNext();
            return;
        }
        download = null;
        try {
            dl.out.close();
            if (!sha1(dl.tmp).equals(dl.pack.sha1())) {
                Files.deleteIfExists(dl.tmp);
                overlay(L10n.t("pack.bad"));
                return;
            }
            Files.move(dl.tmp, CACHE_DIR.resolve(dl.pack.sha1() + ".zip"), StandardCopyOption.REPLACE_EXISTING);
            pruneCache();
        } catch (Exception e) {
            LOG.warn("[pack] 마무리 실패: {}", e.toString());
            return;
        }
        sync(); // 다음 팩, 또는 다 받았으면 적용
    }

    /** 방장 팩 묶음을 그대로 맞춘다 — 예전 것을 빼고 새 목록을 아래 → 위로 넣는다. 같으면 아무것도 안 한다. */
    private static void applySet(List<String> set) {
        if (set.equals(appliedSet)) return;
        for (String old : appliedSet) pop(packId(old));
        for (String sha : set) push(packId(sha), CACHE_DIR.resolve(sha + ".zip").toAbsolutePath());
        boolean was = !appliedSet.isEmpty();
        appliedSet = List.copyOf(set);
        if (!set.isEmpty()) {
            allow();
            overlay(L10n.t("pack.applied"));
        } else if (was) {
            overlay(L10n.t("pack.removed"));
        }
    }

    static void cancelDownload() {
        Download dl = download;
        download = null;
        if (dl == null) return;
        try {
            dl.out.close();
            Files.deleteIfExists(dl.tmp);
        } catch (IOException ignored) {
        }
    }

    /** 방에서 나가면(클라이언트 스레드) — 받던 것·이번 접속 기록을 비운다(적용한 팩은 바닐라가 뺀다). */
    static void onDisconnect() {
        cancelDownload();
        wanted = List.of();
        appliedSet = List.of();
        follow = null;
        asking = false;
    }

    private static void pruneCache() {
        try (Stream<Path> s = Files.list(CACHE_DIR)) {
            List<Path> zips = new ArrayList<>(s.filter(p -> p.toString().endsWith(".zip")).toList());
            zips.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            for (int i = CACHE_KEEP; i < zips.size(); i++) Files.deleteIfExists(zips.get(i));
        } catch (IOException ignored) {
        }
    }

    static boolean validSha1(String s) {
        return s != null && s.length() == 40 && s.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
    }

    private static UUID packId(String sha1) {
        return UUID.nameUUIDFromBytes(("instant-p2p-pack:" + sha1).getBytes(StandardCharsets.UTF_8));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  버전별
    // ═══════════════════════════════════════════════════════════════════════

    //? if >=26.1 {
    /*public record Data(int kind, String sha1, String meta, long size, int total, int index, byte[] bytes)
            implements CustomPacketPayload {
        public static final int KIND_MANIFEST = 2, KIND_CHUNK = 1;
        public static final CustomPacketPayload.Type<Data> ID = new CustomPacketPayload.Type<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", "pack"));
        public static final StreamCodec<FriendlyByteBuf, Data> CODEC = CustomPacketPayload.codec(Data::write, Data::new);
        private Data(FriendlyByteBuf b) {
            this(b.readVarInt(), b.readUtf(40), b.readUtf(META_MAX), b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK));
        }
        private void write(FriendlyByteBuf b) {
            b.writeVarInt(kind); b.writeUtf(sha1, 40); b.writeUtf(meta, META_MAX); b.writeVarLong(size);
            b.writeVarInt(total); b.writeVarInt(index); b.writeByteArray(bytes);
        }
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record Req(String sha1, int index) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Req> ID = new CustomPacketPayload.Type<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", "pack_req"));
        public static final StreamCodec<FriendlyByteBuf, Req> CODEC = CustomPacketPayload.codec(Req::write, Req::new);
        private Req(FriendlyByteBuf b) { this(b.readUtf(40), b.readVarInt()); }
        private void write(FriendlyByteBuf b) { b.writeUtf(sha1, 40); b.writeVarInt(index); }
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Data.ID, Data.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Req.ID, Req.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Req.ID, (payload, ctx) ->
                onRequest(ctx.player(), ctx.player().getUUID(), payload.sha1(), payload.index()));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Data.ID, (payload, ctx) ->
                ctx.client().execute(() -> onData(payload)));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                onGuestJoin(handler.player, handler.player.getUUID()));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(PackShare::onDisconnect));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> onClientTick());
    }

    private static List<String> selectedPackIds() {
        return new ArrayList<>(net.minecraft.client.Minecraft.getInstance().options.resourcePacks);
    }

    // 접속자 목록은 서버 스레드에서 떠서, 각자에게 할 일은 IO 스레드에서.
    private static void withGuests(java.util.function.Consumer<Object> action) {
        net.minecraft.server.MinecraftServer server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            List<Object> out = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!isHost(p.getUUID())) out.add(p);
            }
            IO.execute(() -> out.forEach(action));
        });
    }

    private static boolean isGone(Object player) {
        return ((ServerPlayer) player).hasDisconnected();
    }

    private static boolean canSend(Object player) {
        return net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend((ServerPlayer) player, Data.ID);
    }

    private static void send(Object player, Data d) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send((ServerPlayer) player, d);
    }

    private static void sendToHost(Req r) {
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(Req.ID)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(r);
        }
    }

    private static void push(UUID id, Path file) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getDownloadedPackSource().pushLocalPack(id, file);
    }

    private static void pop(UUID id) {
        net.minecraft.client.Minecraft.getInstance().getDownloadedPackSource().popPack(id);
    }

    private static void allow() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getDownloadedPackSource().allowServerPacks();
    }

    private static boolean hostingServer() {
        return net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null;
    }

    private static void overlay(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(text));
    }

    private static void askYesNo(String title, String body, it.unimi.dsi.fastutil.booleans.BooleanConsumer cb) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.gui.screens.Screen prev = currentScreen(mc);
        mc.setScreenAndShow(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
            mc.setScreenAndShow(prev);
            cb.accept(yes);
        }, net.minecraft.network.chat.Component.literal(title), net.minecraft.network.chat.Component.literal(body)));
    }
    *///?}
    //? if >=26.2 {
    /*private static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft mc) {
        return mc.gui.screen();
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft mc) {
        return mc.screen;
    }
    *///?}
    //? if <26.1 {
    public record Data(int kind, String sha1, String meta, long size, int total, int index, byte[] bytes)
            implements CustomPayload {
        public static final int KIND_MANIFEST = 2, KIND_CHUNK = 1;
        public static final CustomPayload.Id<Data> ID = new CustomPayload.Id<>(net.minecraft.util.Identifier.of("instant-p2p", "pack"));
        public static final PacketCodec<PacketByteBuf, Data> CODEC = CustomPayload.codecOf(Data::write, Data::new);
        private Data(PacketByteBuf b) {
            this(b.readVarInt(), b.readString(40), b.readString(META_MAX), b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK));
        }
        private void write(PacketByteBuf b) {
            b.writeVarInt(kind); b.writeString(sha1, 40); b.writeString(meta, META_MAX); b.writeVarLong(size);
            b.writeVarInt(total); b.writeVarInt(index); b.writeByteArray(bytes);
        }
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public record Req(String sha1, int index) implements CustomPayload {
        public static final CustomPayload.Id<Req> ID = new CustomPayload.Id<>(net.minecraft.util.Identifier.of("instant-p2p", "pack_req"));
        public static final PacketCodec<PacketByteBuf, Req> CODEC = CustomPayload.codecOf(Req::write, Req::new);
        private Req(PacketByteBuf b) { this(b.readString(40), b.readVarInt()); }
        private void write(PacketByteBuf b) { b.writeString(sha1, 40); b.writeVarInt(index); }
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(Data.ID, Data.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(Req.ID, Req.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Req.ID, (payload, ctx) ->
                onRequest(ctx.player(), ctx.player().getUuid(), payload.sha1(), payload.index()));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Data.ID, (payload, ctx) ->
                ctx.client().execute(() -> onData(payload)));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                onGuestJoin(handler.player, handler.player.getUuid()));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(PackShare::onDisconnect));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> onClientTick());
    }

    private static List<String> selectedPackIds() {
        return new ArrayList<>(net.minecraft.client.MinecraftClient.getInstance().options.resourcePacks);
    }

    // 접속자 목록은 서버 스레드에서 떠서, 각자에게 할 일은 IO 스레드에서.
    private static void withGuests(java.util.function.Consumer<Object> action) {
        net.minecraft.server.MinecraftServer server = net.minecraft.client.MinecraftClient.getInstance().getServer();
        if (server == null) return;
        server.execute(() -> {
            List<Object> out = new ArrayList<>();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!isHost(p.getUuid())) out.add(p);
            }
            IO.execute(() -> out.forEach(action));
        });
    }

    private static boolean isGone(Object player) {
        return ((ServerPlayerEntity) player).isDisconnected();
    }

    private static boolean canSend(Object player) {
        return net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend((ServerPlayerEntity) player, Data.ID);
    }

    private static void send(Object player, Data d) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send((ServerPlayerEntity) player, d);
    }

    private static void sendToHost(Req r) {
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(Req.ID)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(r);
        }
    }

    private static void push(UUID id, Path file) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != null) mc.getServerResourcePackProvider().addResourcePack(id, file);
    }

    private static void pop(UUID id) {
        net.minecraft.client.MinecraftClient.getInstance().getServerResourcePackProvider().remove(id);
    }

    private static void allow() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != null) mc.getServerResourcePackProvider().acceptAll();
    }

    private static boolean hostingServer() {
        return net.minecraft.client.MinecraftClient.getInstance().getServer() != null;
    }

    private static void overlay(String text) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(text), true);
    }

    private static void askYesNo(String title, String body, it.unimi.dsi.fastutil.booleans.BooleanConsumer cb) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.client.gui.screen.Screen prev = mc.currentScreen;
        mc.setScreen(new net.minecraft.client.gui.screen.ConfirmScreen(yes -> {
            mc.setScreen(prev);
            cb.accept(yes);
        }, net.minecraft.text.Text.literal(title), net.minecraft.text.Text.literal(body)));
    }
    //?}
}
