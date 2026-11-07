package kfc.udp.client.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 가맹점판 자동 업데이트 — 가맹점 서버(/api/v1/update)에 내 마크 버전용 최신판이 있는지 물어보고, 있으면 메인 화면
 * 오른쪽에 안내(UpdateBanner)를 띄운다. [업데이트]를 누르면:
 * <ol>
 *   <li>jar를 mods 폴더에 {@code <새이름>.jar.pending}(파브릭이 안 읽는 이름)으로 받는다.</li>
 *   <li>서버가 알려 준 SHA-256과 맞는지, 안에 fabric.mod.json(id instant-p2p)이 있는지 확인한다 — 틀리면 지운다.</li>
 *   <li>게임이 꺼질 때 지금 jar를 지우고 .pending을 .jar로 바꾼다. 윈도우는 실행 중인 jar가 잠겨 있어서, 게임이
 *       완전히 꺼진 뒤 지우고 바꾸는 작은 배치 파일을 띄운다. <b>옛 jar를 못 지우면 바꾸지 않는다</b> — 같은 모드가
 *       두 개 깔려 게임이 안 켜지는 일이 없게(그대로 옛 판으로 켜지고, 다음에 다시 시도한다).</li>
 * </ol>
 * 가맹점 약관에 동의한 사람만 확인한다(동의 전·본점 사용자는 가맹점 서버에 접속하지 않는다 — 약관 3조).
 */
public final class AutoUpdate {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-update");
    private static final String MOD_ID = "instant-p2p";
    private static final String PENDING = ".pending";
    private static final long CHECK_EVERY_MS = 10 * 60_000L;
    private static final Pattern JAR_NAME = Pattern.compile("^[A-Za-z0-9._+-]{1,120}\\.jar$");
    private static final Pattern MOD_ID_JSON = Pattern.compile("\"id\"\\s*:\\s*\"instant-p2p\"");

    public enum State { NONE, AVAILABLE, DOWNLOADING, READY, FAILED }

    public record Info(String latest, String notes, String url, String sha256, long size, String file) {}

    private static volatile Info info;
    private static volatile State state = State.NONE;
    private static volatile int percent;
    private static volatile String error = "";
    private static volatile long nextCheckMs;
    private static volatile boolean swapArmed;

    private AutoUpdate() {}

    public static State state() { return state; }
    public static Info info() { return info; }
    public static int percent() { return percent; }
    public static String error() { return error; }

    /** 메인 화면이 열릴 때마다 불러도 된다(10분에 한 번만 실제로 묻는다). */
    public static void checkIfDue() {
        if (!P2PConfig.franchiseConsented()) return;
        if (state == State.DOWNLOADING || state == State.READY) return;
        long now = System.currentTimeMillis();
        if (now < nextCheckMs) return;
        nextCheckMs = now + CHECK_EVERY_MS;
        Thread t = new Thread(AutoUpdate::check, "instant-p2p-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void check() {
        try {
            String url = P2PConfig.franchiseHttpUrl() + "/api/v1/update?mc=" + enc(P2PConfig.MC_VERSION)
                    + "&v=" + enc(P2PConfig.FRANCHISE_BUILD);
            HttpResponse<String> res = Http.CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            if (!o.has("available") || !o.get("available").getAsBoolean()) {
                if (state == State.AVAILABLE) state = State.NONE;
                return;
            }
            String dl = str(o, "url"), file = str(o, "file"), sha = str(o, "sha256");
            // 다른 곳에서 받으라고 하면 안 받는다 — 가맹점 서버 주소에서만.
            if (!dl.startsWith(P2PConfig.franchiseHttpUrl() + "/dl/") || !JAR_NAME.matcher(file).matches()
                    || !sha.matches("[0-9a-f]{64}")) {
                LOG.warn("[update] 서버 응답이 이상해서 무시한다: url={} file={}", dl, file);
                return;
            }
            info = new Info(str(o, "latest"), str(o, "notes"), dl, sha, o.has("size") ? o.get("size").getAsLong() : 0, file);
            if (ownJar() == null) {
                LOG.info("[update] 새 판 {}이 있지만 이 실행은 jar로 깔린 게 아니라(개발 환경 등) 자동 설치는 끈다", info.latest());
            }
            state = State.AVAILABLE;
        } catch (Exception e) {
            LOG.debug("[update] 확인 실패(무시): {}", e.getMessage());
        }
    }

    /** [업데이트] 버튼. */
    public static void startDownload() {
        Info i = info;
        if (i == null || state == State.DOWNLOADING || state == State.READY) return;
        state = State.DOWNLOADING;
        percent = 0;
        error = "";
        Thread t = new Thread(() -> download(i), "instant-p2p-update-download");
        t.setDaemon(true);
        t.start();
    }

    private static void download(Info i) {
        Path own = ownJar();
        if (own == null) {
            fail("이 설치에서는 자동 업데이트를 할 수 없습니다(모드 jar를 찾지 못함)");
            return;
        }
        Path mods = own.getParent();
        Path pending = mods.resolve(i.file() + PENDING);
        Path part = mods.resolve(i.file() + ".part");
        try {
            HttpResponse<InputStream> res = Http.CLIENT.send(HttpRequest.newBuilder(URI.create(i.url()))
                    .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                fail("내려받기 실패(HTTP " + res.statusCode() + ")");
                return;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = i.size() > 0 ? i.size() : res.headers().firstValueAsLong("content-length").orElse(0);
            long got = 0;
            try (InputStream in = res.body(); var out = Files.newOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (got > 64L << 20) throw new java.io.IOException("파일이 너무 큽니다");
                    if (total > 0) percent = (int) Math.min(99, got * 100 / total);
                }
            }
            String sha = HexFormat.of().formatHex(md.digest());
            if (!sha.equalsIgnoreCase(i.sha256())) {
                Files.deleteIfExists(part);
                fail("받은 파일이 서버가 알려 준 것과 다릅니다(SHA-256 불일치) — 설치하지 않았습니다");
                return;
            }
            if (!isOurMod(part)) {
                Files.deleteIfExists(part);
                fail("받은 파일이 이 모드가 아닙니다 — 설치하지 않았습니다");
                return;
            }
            Files.move(part, pending, StandardCopyOption.REPLACE_EXISTING);
            armSwap(own, pending, mods.resolve(i.file()));
            percent = 100;
            state = State.READY;
            LOG.info("[update] {} 받음 — 게임을 다시 켜면 적용된다", i.file());
        } catch (Exception e) {
            try { Files.deleteIfExists(part); } catch (Exception ignored) {}
            fail("내려받기 실패: " + e.getMessage());
        }
    }

    private static void fail(String msg) {
        error = msg;
        state = State.FAILED;
        LOG.warn("[update] {}", msg);
    }

    private static boolean isOurMod(Path jar) {
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if ("fabric.mod.json".equals(e.getName())) {
                    String json = new String(z.readNBytes(64 << 10), StandardCharsets.UTF_8);
                    return MOD_ID_JSON.matcher(json).find();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 지금 실행 중인 이 모드의 jar 경로 — mods 폴더의 일반 .jar일 때만(개발 환경·중첩 jar면 null). */
    static Path ownJar() {
        try {
            var c = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
            if (c == null || c.getOrigin().getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) return null;
            var paths = c.getOrigin().getPaths();
            if (paths.size() != 1) return null;
            Path p = paths.get(0).toAbsolutePath();
            return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 게임을 켤 때 부른다 — 지난번에 받아 놓고 못 바꾼 .pending이 있으면(옛 jar가 잠겨 있었다 등) 이번 종료 때 다시 시도.
     */
    public static void resumePendingSwap() {
        Path own = ownJar();
        if (own == null) return;
        try (var s = Files.list(own.getParent())) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar" + PENDING)).findFirst().ifPresent(p -> {
                String name = p.getFileName().toString();
                Path target = own.getParent().resolve(name.substring(0, name.length() - PENDING.length()));
                if (!isOurMod(p)) return;
                armSwap(own, p, target);
                state = State.READY;
                LOG.info("[update] 지난번에 받은 {}을 이번 종료 때 적용한다", target.getFileName());
            });
        } catch (Exception ignored) {}
    }

    private static synchronized void armSwap(Path oldJar, Path pending, Path target) {
        if (swapArmed) return;
        swapArmed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> swap(oldJar, pending, target), "instant-p2p-update-swap"));
    }

    private static void swap(Path oldJar, Path pending, Path target) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            try {
                if (!oldJar.equals(target)) Files.deleteIfExists(oldJar);
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.warn("[update] 바꿔 끼우기 실패: {}", e.toString());
            }
            return;
        }
        // 윈도우: 실행 중인 jar는 잠겨 있다 — 게임이 완전히 꺼질 때까지(최대 60초) 옛 jar 지우기를 시도하고,
        // 지워졌을 때만 새 jar로 이름을 바꾼다. 못 지우면 아무것도 안 바꾼다(다음 실행 때 다시 시도).
        try {
            Path bat = Files.createTempFile("instant-p2p-update-", ".cmd");
            String o = oldJar.toString(), p = pending.toString(), t = target.toString();
            String script = "@echo off\r\nchcp 65001 >nul\r\n"
                    + "for /l %%i in (1,1,60) do (\r\n"
                    + (oldJar.equals(target) ? "" : "  del /f /q \"" + o + "\" >nul 2>nul\r\n")
                    + (oldJar.equals(target)
                        ? "  move /y \"" + p + "\" \"" + t + "\" >nul 2>nul && goto done\r\n"
                        : "  if not exist \"" + o + "\" goto swap\r\n")
                    + "  ping -n 2 127.0.0.1 >nul\r\n"
                    + ")\r\n"
                    + "goto end\r\n"
                    + ":swap\r\n"
                    + "move /y \"" + p + "\" \"" + t + "\" >nul 2>nul\r\n"
                    + ":done\r\n"
                    + ":end\r\n"
                    + "del \"%~f0\" >nul 2>nul\r\n";
            Files.writeString(bat, script, StandardCharsets.UTF_8);
            new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", "cmd", "/c", bat.toString())
                    .redirectErrorStream(true).start();
        } catch (Exception e) {
            LOG.warn("[update] 바꿔 끼우기 준비 실패: {}", e.toString());
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
