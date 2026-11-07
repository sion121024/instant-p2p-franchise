package kfc.udp.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kfc.udp.client.signaling.Http;
import kfc.udp.client.signaling.MojangAuth;
import kfc.udp.client.signaling.P2PConfig;
import kfc.udp.client.signaling.StreamerRelay;
//? if >=26.1 {
/*import net.minecraft.client.gui.screens.Screen;
*///?} else {
import net.minecraft.client.gui.screen.Screen;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 이용 동의(1.5~, 1.5.1 에서 통합).
 * <ul>
 *   <li><b>본점(OFFICIAL)</b> — 1.5.1 과 똑같다. 멀티플레이(커스텀 방 만들기·방 들어가기) 화면에 들어가기 전에 통합 안내
 *       4장({@code public_consent.p1..p4})을 보여 주고, 동의하면 서버에 {@code POST /api/v1/consent {"rev":3,"role":…}}
 *       를 host·join 둘 다 <b>성공해야</b> 로컬에 기록한다(본점은 서버 쪽에서 동의 기록 없는 방·접속을
 *       {@code need-consent-*} 로 거절한다). 초대 코드 방에도 적용된다.</li>
 *   <li><b>가맹점(FRANCHISE)</b> — F3.5 방식 유지. 공개 방을 열거나 공개 방 목록을 쓸 때만 역할별 안내를 보여 주고,
 *       서버 기록은 증빙용이라 실패해도 로컬 기록으로 진행한다(뒤에서 몇 번 다시 보낸다). 초대 코드 방은 해당 없음.
 *       가맹점도 통합 방식으로 바꾸려면 {@link #unified()} 가 항상 true 를 돌려주게 하면 된다.</li>
 * </ul>
 * 동의는 계정(UUID)마다 config/instant-p2p/public_consent.json 에 남는다. 통합 동의(rev 3)는 역할별 동의(rev 1)도 만족한다.
 */
public final class PublicConsent {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-consent");
    /** 통합 동의 판(1.5.1 = 3). */
    public static final int REV = 3;
    /** 가맹점의 역할별(공개 방 전용) 동의 판. */
    private static final int ROLE_REV = 1;
    private static final Path FILE = Path.of("config", "instant-p2p", "public_consent.json");
    private static final int PAGES = 4;

    private PublicConsent() {}

    /** 통합 동의(모든 멀티플레이에 필요)를 쓰는지 — 본점에 붙어 있을 때. */
    public static boolean unified() {
        return P2PConfig.getServerProfile() == P2PConfig.ServerProfile.OFFICIAL;
    }

    private static int requiredRev() {
        return unified() ? REV : ROLE_REV;
    }

    /** 지금 계정이 이 역할(host/join)에 동의했는지. */
    public static synchronized boolean has(String role) {
        String me = MojangAuth.currentUuidOrNull();
        if (me == null) return false;
        try {
            if (!Files.exists(FILE)) return false;
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject acc = o.has(me) && o.get(me).isJsonObject() ? o.getAsJsonObject(me) : null;
            return acc != null && acc.has(role) && acc.get(role).getAsInt() >= requiredRev();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasAll() {
        return has("host") && has("join");
    }

    /** 방을 열 수 있는지 — 본점은 통합 동의가 있어야, 가맹점은 언제나(공개 여부는 따로 {@link #has} 로 거른다). */
    public static boolean mayUseMultiplayer() {
        return !unified() || hasAll();
    }

    /**
     * 멀티플레이 화면에 들어가기 전(1.5.1). 본점이면 통합 동의를 받고 나서 {@code afterAgree}, 가맹점이면 바로 {@code afterAgree}.
     */
    public static void ask(Screen parent, Runnable afterAgree) {
        if (!unified() || hasAll()) afterAgree.run();
        else showPage(parent, null, 1, afterAgree);
    }

    /** 공개 방 켜기·공개 목록 쓰기 전(1.5~). 본점이면 통합 동의, 가맹점이면 그 역할의 안내 4장. */
    public static void ask(Screen parent, String role, Runnable afterAgree) {
        if (unified()) {
            ask(parent, afterAgree);
            return;
        }
        if (has(role)) afterAgree.run();
        else showPage(parent, role, 1, afterAgree);
    }

    /** role == null 이면 통합 안내(public_consent.pN), 아니면 역할별 안내(public_consent.ROLE.pN). */
    private static void showPage(Screen parent, String role, int n, Runnable afterAgree) {
        String k = role == null ? "instant-p2p.public_consent.p" + n : "instant-p2p.public_consent." + role + ".p" + n;
        boolean last = n == PAGES;
        SafetyWarningScreen.open(new SafetyWarningScreen(parent, k + ".title", k + ".body", 0xFFFFD866, 0xFF201800,
                last ? "instant-p2p.public_consent.agree" : "instant-p2p.public_consent.next",
                last ? () -> {
                    if (role == null) confirmUnified(afterAgree);
                    else recordRole(role, afterAgree);
                } : () -> showPage(parent, role, n + 1, afterAgree)));
    }

    private static synchronized void write(String role, int rev) {
        String me = MojangAuth.currentUuidOrNull();
        if (me == null) return;
        try {
            JsonObject o = new JsonObject();
            if (Files.exists(FILE)) {
                try {
                    o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (Exception ignored) {
                    // 깨졌으면 새로 쓴다
                }
            }
            JsonObject acc = o.has(me) && o.get(me).isJsonObject() ? o.getAsJsonObject(me) : new JsonObject();
            acc.addProperty(role, rev);
            acc.addProperty(role + "At", System.currentTimeMillis());
            o.add(me, acc);
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[consent] 동의 기록을 저장하지 못했다: {}", e.toString());
        }
    }

    /** 서버(본점)가 동의 기록이 없다고 거절하면(need-consent-*) 로컬 기록도 지운다 — 다음에 동의서가 다시 뜬다. */
    public static synchronized void forget(String role) {
        String me = MojangAuth.currentUuidOrNull();
        if (me == null) return;
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has(me) || !o.get(me).isJsonObject()) return;
            JsonObject acc = o.getAsJsonObject(me);
            acc.remove(role);
            acc.remove(role + "At");
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[consent] 동의 기록을 지우지 못했다: {}", e.toString());
        }
    }

    /** 통합 동의 — host·join 을 서버에 둘 다 기록해야 로컬에도 남기고 진행한다(1.5.1). 실패하면 알림만 띄운다. */
    private static void confirmUnified(Runnable afterAgree) {
        Thread t = new Thread(() -> {
            boolean ok = post("host", REV, 3) && post("join", REV, 3);
            StreamerRelay.runOnClient(() -> {
                if (ok) {
                    write("host", REV);
                    write("join", REV);
                    afterAgree.run();
                } else {
                    StreamerRelay.toast("instant-p2p.consent.save_failed.title", "instant-p2p.consent.save_failed.body");
                }
            });
        }, "instant-p2p-consent");
        t.setDaemon(true);
        t.start();
    }

    /** 가맹점 역할별 동의 — 로컬에 바로 남기고 서버엔 뒤에서 알린다(실패해도 막지 않는다). */
    private static void recordRole(String role, Runnable afterAgree) {
        write(role, ROLE_REV);
        afterAgree.run();
        Thread t = new Thread(() -> post(role, ROLE_REV, 5), "instant-p2p-consent");
        t.setDaemon(true);
        t.start();
    }

    /** 동의를 서버에 기록한다. 2xx 면 true. 인증 실패(401/403)·과다 요청(429)이면 바로 그만둔다. */
    private static boolean post(String role, int rev, int tries) {
        // 가맹점은 토큰 없이도 받는 서버가 있어 그대로 보낸다. 본점은 토큰이 있어야 기록된다.
        if (MojangAuth.ensureToken() == null && unified()) {
            LOG.warn("[consent] 계정 확인(토큰)을 못 받아 동의를 서버에 기록하지 못했다: {}", MojangAuth.lastError());
            return false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("rev", rev);
        body.addProperty("role", role);
        for (int i = 0; i < tries; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(MojangAuth.withToken(P2PConfig.signalingHttpUrl() + "/api/v1/consent")))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) return true;
                if (res.statusCode() == 401 || res.statusCode() == 403 || res.statusCode() == 429) return false;
            } catch (InterruptedException e) {
                return false;
            } catch (Exception ignored) {
                // 다시 시도
            }
            try {
                Thread.sleep(1000L << Math.min(i, 3));
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }
}
