package kfc.udp.client.signaling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P2P 인프라 접속 설정 (시그널링/STUN/TURN) — 호스트·조인 공용.
 * <p>
 * 서버 배치가 바뀌면 이 파일만 고치면 된다. 빌드 없이 JVM 프로퍼티로도 재정의 가능:
 * <pre>
 *   -Dkfcudp.signaling=wss://HOST   (로컬 테스트 서버면 ws://HOST:PORT)
 *   -Dkfcudp.stun=stun:HOST:3478
 *   -Dkfcudp.turn=turn:HOST:3478
 *   -Dkfcudp.turn.user=USER  -Dkfcudp.turn.pass=PASS
 * </pre>
 * 현재 배치 (오라클 클라우드 kite-private-cloud.kro.kr):
 * mc-signaling → 127.0.0.1:8090 을 Caddy 가 443(TLS)으로 공개, coturn → *:3478.
 */
public final class P2PConfig {

    /**
     * 지금 실행 중인 마인크래프트 버전 문자열(예: "1.21.5", "26.2") — 공개 방 목록을
     * 서로 접속 자체가 안 되는 버전끼리 자동으로 분리하는 데 쓴다(PublicRoomAnnouncer/
     * PublicRoomBrowser 참고). Fabric Loader API로 구하므로 Yarn/Mojang 매핑 어느
     * 쪽으로 빌드해도 동일하게 동작 — 버전별 분기가 필요 없다. 채널(사용자가 직접
     * 고르는 값)과는 완전히 별개 축이라, 채널이 같아도 버전이 다르면 목록에 안
     * 뜬다(반대도 마찬가지) — 애초에 접속이 안 되는 상대를 목록에 보여줄 이유가
     * 없어서 사용자 입력 없이 항상 강제 적용한다.
     */
    public static final String MC_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    /** 지금 실행 중인 모드(instant-p2p) 버전 문자열(예: "1.2.1", stonecutter.properties.toml의
     * mod.version) — MC_VERSION만으로는 같은 마인크래프트 버전에서 모드 버전이 다른(=프로토콜이
     * 다를 수 있는) 방끼리도 서로 보였다. 마인크래프트 버전과 별개로 이것도 정확히 같아야만
     * 접속 가능한 방으로 친다(RoomEntry.sameVersion) — 패치 하나 차이라도 다르면 회색 처리.
     */
    /** 가맹점 수정판 번호 — 화면·신고에만 쓴다. MOD_VERSION(=원본 버전)은 로비 구분 해시에 들어가서
     *  바꾸면 같은 원본 버전 사람들과 방 목록이 갈라지므로 그대로 둔다. */
    public static final String FRANCHISE_BUILD = "F4.0.2";

    public static final String MOD_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("instant-p2p")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    /**
     * 원작자 공식 서버(기본값) — <b>TLS(wss)</b>. 같은 서버의 Caddy 가 443 에서 받아
     * 127.0.0.1:8090 으로 넘긴다({@code signaling thing/deploy-caddy.sh}).
     */
    private static final String OFFICIAL_SIGNALING_URL = "wss://kite-private-cloud.kro.kr";
    private static final String OFFICIAL_STUN_URL = "stun:kite-private-cloud.kro.kr:3490";
    private static final String OFFICIAL_TURN_URL = "turn:kite-private-cloud.kro.kr:3490";

    /**
     * <b>사설 서버(고정)</b> — 사용자가 직접 URL을 입력하는 게 아니라, 이 세 값 자체가 "우리
     * 사설 서버" 주소다. 실제 서버를 배치한 뒤 이 세 줄만 실제 도메인으로 바꿔서 빌드하면 된다
     * (지금은 아직 서버 주소가 없어서 placeholder — {@code mc-relay-server}를 이 도메인으로 배치할 것).
     * <pre>
     *   FRANCHISE_SIGNALING_URL = "wss://relay.example.com"
     *   FRANCHISE_STUN_URL      = "stun:relay.example.com:3490"
     *   FRANCHISE_TURN_URL      = "turn:relay.example.com:3490"
     * </pre>
     * (mc-relay-server의 docker-compose.yml 포트 설정과 반드시 일치해야 한다.)
     */
    private static final String FRANCHISE_SIGNALING_URL = "wss://p2p.sionserver.com"; // 가맹점(시온 사설 중계 서버, Cloudflare 경유 — 옛 주소 sion-p2p-server.kro.kr은 F3c 이하용으로 유지)
    private static final String FRANCHISE_STUN_URL = "stun:turn.sionserver.com:3490"; // 가맹점 STUN(Cloudflare 미경유 — UDP라 직접)
    private static final String FRANCHISE_TURN_URL = "turn:turn.sionserver.com:3490"; // 가맹점 TURN(Cloudflare 미경유 — UDP라 직접)

    /**
     * <b>서버 프로필</b> — "official"(원작자 서버) 또는 "custom"(위 {@code FRANCHISE_*} 상수로 고정된
     * 우리 사설 서버). 사용자가 URL을 직접 입력하지 않는다 — 프로필 화면에서는 둘 중 하나를 고를
     * 뿐이다. 방 목록·초대 코드·공개 방은 서버 단위로 완전히 분리된 세계이므로, 공식 서버 접속자와
     * 사설 서버 접속자는 애초에 서로를 볼 수도 만날 수도 없다 — 방을 열기/찾기 전에 반드시 같은
     * 프로필이어야 한다.
     * <p>
     * {@code -Dkfcudp.signaling} 등 JVM 프로퍼티가 있으면 <b>프로필과 무관하게 항상 우선</b>한다
     * (로컬 테스트/CI 용도 — 예전부터 있던 오버라이드 동작을 그대로 유지).
     */
    /** OFFICIAL = 본점(원작자 공식 서버), FRANCHISE = 가맹점(우리 사설 중계 서버). */
    public enum ServerProfile { OFFICIAL, FRANCHISE }

    // 처음 쓸 때 읽는다(지연 초기화). 클래스 초기화 때 바로 읽으면, 아래쪽에 선언된 SETTINGS_FILE이
    // 아직 null이라 NullPointerException으로 P2PConfig 전체가 초기화에 실패한다(게임 크래시 원인이었음).
    private static volatile ServerProfile serverProfile;

    public static ServerProfile getServerProfile() {
        ServerProfile p = serverProfile;
        if (p == null) {
            p = loadServerProfile();
            serverProfile = p;
        }
        return p;
    }

    /** 서버 프로필만 저장한다 — 사설 서버 주소는 입력받지 않고 {@code FRANCHISE_*} 상수로 고정. */
    public static void setServerProfile(ServerProfile profile) {
        ServerProfile before = serverProfile;
        serverProfile = profile;
        updateSettingsFile(o -> o.addProperty("serverProfile", profile.name()));
        // 서버가 바뀌면 이전 서버에서 받은 게시 토큰·중계 계정은 새 서버에서 안 통한다.
        if (before != profile) MojangAuth.forgetServerState();
        ServerStatus.reset();
        BanStatus.reset();
        BanStatus.refreshAsync();
    }

    // ── 초대 코드 표시 — 사설 서버 방은 앞에 표시를 붙여, 코드만 보고 어느 서버인지 알게 한다 ──────
    //
    // 공식 서버 코드는 CODE_CHARS(대문자+숫자, I·O·0·1 제외) 10자뿐이라 하이픈이 절대 안 들어간다.
    // 그래서 "F-" 접두어가 붙은 코드는 확실히 우리(사설) 서버 방이다. 코드 입력칸에 넣으면
    // routeInviteCode가 접두어를 보고 서버를 자동으로 맞춘다 — 붙어 있으면 사설, 없으면 공식.

    /** 사설 서버 방 코드 접두어. */
    public static final String FRANCHISE_CODE_PREFIX = "F-";
    /** 예전 수정판(sion2)이 쓰던 접두어 — 이미 퍼진 코드도 계속 가맹점으로 보낸다. */
    private static final String LEGACY_FRANCHISE_CODE_PREFIX = "S-";

    /** 방장이 친구에게 줄 코드 — 지금 사설 서버면 접두어를 붙인다. */
    public static String displayCode(String code) {
        if (code == null) return null;
        return getServerProfile() == ServerProfile.FRANCHISE ? FRANCHISE_CODE_PREFIX + code : code;
    }

    /**
     * 사용자가 입력한 초대 코드 → 실제 방 코드. 접두어로 서버를 판단해 필요하면 서버 설정을 바꾼다.
     * 공백·소문자는 정리한다.
     *
     * @return {@code [방 코드, 서버가 바뀌었으면 "switched" 아니면 null]}
     */
    /** 입력한 초대 코드가 가맹점 코드("F-", 예전 "S-")인지. */
    public static boolean isFranchiseCode(String input) {
        String c = input == null ? "" : input.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        return c.startsWith(FRANCHISE_CODE_PREFIX) || c.startsWith(LEGACY_FRANCHISE_CODE_PREFIX);
    }

    // ── 특권 명단(/api/v1/roles) 서명 키 — 매장마다 다르다 ────────────────────────
    //
    // 본점 명단은 원작자 서버가 원작자 개인키로 서명한다(Roles.PUBLIC_KEY_B64). 가맹점 서버는 그 키가 없으니
    // 자기 키로 서명한다 — 그 공개키를 여기 둔다(raw 32바이트 Ed25519 공개키의 base64, 서버 -roles-sign-key 의 짝).

    /** 자리표시자 — 이 값 그대로면 "가맹점 키 없음"으로 보고 F3.4처럼 본점 명단만 쓴다(가맹점 명단은 받지 않는다). */
    public static final String FRANCHISE_ROLES_PUBKEY_PLACEHOLDER = "<<FRANCHISE_ROLES_PUBKEY_BASE64>>";

    /**
     * ⚠ <b>가맹점 서버 명단 서명 공개키 — 실제 키로 바꿔서 빌드할 것.</b> 바꾸기 전까지는 위 자리표시자 그대로 두면
     * 된다(전부 거절하지 않고 F3.4 동작으로 돌아간다 — Roles.refreshNow 참고).
     */
    public static final String FRANCHISE_ROLES_PUBKEY = FRANCHISE_ROLES_PUBKEY_PLACEHOLDER;

    /** 가맹점 명단 키가 실제로 들어 있는지 — 자리표시자거나 비어 있으면 false. */
    public static boolean isFranchiseRolesKeyConfigured() {
        String k = FRANCHISE_ROLES_PUBKEY;
        return k != null && !k.isBlank() && !k.equals(FRANCHISE_ROLES_PUBKEY_PLACEHOLDER);
    }

    public static String[] routeInviteCode(String input) {
        String c = input == null ? "" : input.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        boolean franchise = false;
        for (String prefix : new String[]{FRANCHISE_CODE_PREFIX, LEGACY_FRANCHISE_CODE_PREFIX}) {
            if (c.startsWith(prefix)) {
                c = c.substring(prefix.length());
                franchise = true;
                break;
            }
        }
        ServerProfile want = franchise ? ServerProfile.FRANCHISE : ServerProfile.OFFICIAL;
        boolean switched = getServerProfile() != want;
        if (switched) setServerProfile(want);
        return new String[]{c, switched ? "switched" : null};
    }

    // ── 안티치트 설정(방 설정 → 안티치트 설정) — 서버 프로필과 같은 이유로 지연 로드 ──────────
    private static volatile AntiCheatSettings antiCheat;

    public static AntiCheatSettings getAntiCheat() {
        AntiCheatSettings a = antiCheat;
        if (a == null) {
            JsonObject o = readSettingsFile();
            a = AntiCheatSettings.fromJson(o != null && o.has("antiCheat") && o.get("antiCheat").isJsonObject()
                    ? o.getAsJsonObject("antiCheat") : null);
            antiCheat = a;
        }
        return a;
    }

    public static void setAntiCheat(AntiCheatSettings a) {
        antiCheat = a.copy();
        updateSettingsFile(o -> o.add("antiCheat", a.toJson()));
    }

    private static ServerProfile loadServerProfile() {
        JsonObject o = readSettingsFile();
        if (o == null || !o.has("serverProfile")) return ServerProfile.OFFICIAL;
        try {
            String v = o.get("serverProfile").getAsString();
            ServerProfile p = "CUSTOM".equals(v) ? ServerProfile.FRANCHISE : ServerProfile.valueOf(v); // 예전 설정(사설 = CUSTOM) 호환
            // 가맹점 설정이 남아 있어도 현재 약관에 동의하지 않았으면 본점으로 시작한다 — 게임 시작 때의
            // 버전 확인 같은 요청도 동의 전에는 가맹점 서버로 가면 안 된다.
            if (p == ServerProfile.FRANCHISE && !isTermsAccepted()) return ServerProfile.OFFICIAL;
            return p;
        } catch (IllegalArgumentException e) {
            return ServerProfile.OFFICIAL;
        }
    }

    /** JVM 프로퍼티(-Dkfcudp.signaling 등)가 있으면 그게 항상 이긴다 — 프로필 선택과 무관. */
    private static String jvmOverride(String key) {
        String v = System.getProperty(key);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /** 본점(원작자 서버)의 HTTP 주소 — 지금 매장과 상관없이. 특권 명단(Roles)을 받을 때 쓴다(원작자 서명은 본점만 할 수 있다). */
    public static String officialHttpUrl() {
        String ws = OFFICIAL_SIGNALING_URL;
        return ws.startsWith("wss://") ? "https://" + ws.substring(6)
                : ws.startsWith("ws://") ? "http://" + ws.substring(5) : ws;
    }

    /** 가맹점 서버의 HTTP 주소 — 지금 매장(본점/가맹점)과 상관없이. 자동 업데이트(AutoUpdate)가 쓴다. */
    public static String franchiseHttpUrl() {
        String ws = FRANCHISE_SIGNALING_URL;
        return ws.startsWith("wss://") ? "https://" + ws.substring(6)
                : ws.startsWith("ws://") ? "http://" + ws.substring(5) : ws;
    }

    /** 지금 접속에 쓸 시그널링 WS 주소 — FRANCHISE(가맹점)면 항상 {@link #FRANCHISE_SIGNALING_URL}(고정). */
    public static String signalingUrl() {
        String jvm = jvmOverride("kfcudp.signaling");
        if (jvm != null) return jvm;
        return getServerProfile() == ServerProfile.FRANCHISE ? FRANCHISE_SIGNALING_URL : OFFICIAL_SIGNALING_URL;
    }

    /** signalingUrl()의 HTTP 버전 — mc-signaling의 REST API(/api/v1/roles 등, WS 업그레이드가
     * 아닌 일반 GET)를 부를 때 쓴다. ws→http, wss→https만 바꾸고 호스트:포트는 그대로다(같은
     * 서버가 같은 포트에서 WS 업그레이드와 일반 HTTP 요청을 같이 받는다). */
    public static String signalingHttpUrl() {
        String ws = signalingUrl();
        return ws.startsWith("wss://") ? "https://" + ws.substring(6)
                : ws.startsWith("ws://") ? "http://" + ws.substring(5)
                : ws;
    }

    public static String stunUrl() {
        String jvm = jvmOverride("kfcudp.stun");
        if (jvm != null) return jvm;
        return getServerProfile() == ServerProfile.FRANCHISE ? FRANCHISE_STUN_URL : OFFICIAL_STUN_URL;
    }

    public static String turnUrl() {
        String jvm = jvmOverride("kfcudp.turn");
        if (jvm != null) return jvm;
        return getServerProfile() == ServerProfile.FRANCHISE ? FRANCHISE_TURN_URL : OFFICIAL_TURN_URL;
    }

    /**
     * TURN 계정 — <b>기본값이 없다</b>. 계정은 모장 인증을 거쳐 시그널링에서 받는다
     * ({@code MojangAuth.turnCredentials}). 예전엔 고정 계정이 jar 에 박혀 있어서, jar 를 열어 본
     * 누구나 이 서버의 coturn 을 UDP 프록시로 쓸 수 있었다. 이 두 값은 테스트용 덮어쓰기일 뿐이다.
     */
    public static final String TURN_USERNAME = System.getProperty("kfcudp.turn.user");
    public static final String TURN_CREDENTIAL = System.getProperty("kfcudp.turn.pass");

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR     = Path.of("config", "instant-p2p");
    private static final Path SETTINGS_FILE  = CONFIG_DIR.resolve("settings.json");

    /**
     * <b>TURN 전용(relay-only) 스위치.</b>
     * <p>
     * true 면 ICE 후보를 relay 만 수집한다(=직결/홀펀칭 없음, 100% TURN 경유,
     * 상대에게 내 실제 IP가 노출되지 않는다). 기본값 false, 설정 파일에 저장돼
     * 세션을 넘어 유지된다. Custom Room(호스팅)과 Join Room/방 목록(접속) 화면의
     * "중계 통신 강제" 체크박스가 전부 이 하나의 값을 그대로 읽고 쓴다 —
     * 호스트는 강제인데 접속자는 아니게(또는 그 반대로) 어긋나는 걸 막기 위해
     * 화면마다 따로 상태를 들고 있지 않는다.
     */
    private static volatile boolean relayOnly = loadRelayOnly();

    public static boolean isRelayOnly() {
        return relayOnly;
    }

    public static void setRelayOnly(boolean value) {
        if (relayOnly == value) return;
        relayOnly = value;
        updateSettingsFile(o -> o.addProperty("relayOnly", value));
    }

    /** 리소스팩 공유(F4.0, PackShare) — 방장이 켜 둔 가장 위 리소스팩을 접속자에게 보낸다. 기본 꺼짐. */
    private static volatile boolean sharePack = loadSharePack();

    public static boolean isSharePack() {
        return sharePack;
    }

    public static void setSharePack(boolean value) {
        if (sharePack == value) return;
        sharePack = value;
        updateSettingsFile(o -> o.addProperty("sharePack", value));
    }

    private static boolean loadSharePack() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("sharePack") && o.get("sharePack").getAsBoolean();
    }

    /** 방송인 자동 중계 강제를 이미 한 번 처리했는지(1.5.1, StreamerRelay) — 처리 뒤 사용자가 끄면 그대로 둔다. */
    private static volatile boolean streamerRelayDone = loadStreamerRelayDone();

    public static boolean isStreamerRelayDone() {
        return streamerRelayDone;
    }

    public static void setStreamerRelayDone(boolean value) {
        if (streamerRelayDone == value) return;
        streamerRelayDone = value;
        updateSettingsFile(o -> o.addProperty("streamerRelayDone", value));
    }

    private static boolean loadStreamerRelayDone() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("streamerRelayDone") && o.get("streamerRelayDone").getAsBoolean();
    }

    private static boolean loadRelayOnly() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("relayOnly") && o.get("relayOnly").getAsBoolean();
    }

    /** 커스텀 방 생성·접속 전에 뜨는 안전 경고 팝업의 "다시 보지 않기" — 기본은 매번 뜬다. */
    private static volatile boolean safetyWarningDismissed = loadSafetyWarningDismissed();
    /**
     * 안전 경고 문구 판(1.5 = 5). 문구가 크게 바뀌면 올린다 — 예전 판에서 "다시 보지 않기"를 눌렀어도
     * 새 판은 한 번 다시 보여 준다(저장된 판이 이보다 낮으면 dismissed 를 무시).
     */
    private static final int SAFETY_WARNING_REV = 5; // 1.5.1 은 6 — 가맹점은 문구를 안 바꿔서 그대로 둔다(본점은 경고 대신 통합 동의)

    /**
     * 입장 승인제(1.5~) — 켜면 새로 들어온 사람(처음 보는 사람)은 방장이 {@code /admit} 할 때까지 관전자로 묶인다
     * (AdmitGate). 한 번 승인한 사람·OP·화이트리스트는 바로 들어온다. 기본은 꺼짐.
     */
    private static volatile boolean approvalGate = loadApprovalGate();

    public static boolean isApprovalGate() {
        return approvalGate;
    }

    public static void setApprovalGate(boolean value) {
        if (approvalGate == value) return;
        approvalGate = value;
        updateSettingsFile(o -> o.addProperty("approvalGate", value));
    }

    private static boolean loadApprovalGate() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("approvalGate") && o.get("approvalGate").getAsBoolean();
    }

    // ── 가맹점 이용약관 동의 ────────────────────────────────────────────────
    //
    // 가맹점(사설 서버)으로 바꿀 때 약관(TermsScreen)을 보여 주고 동의를 받는다. 약관 내용이 바뀌면
    // TERMS_VERSION을 올린다 — 예전 버전에 동의한 사람도 다시 동의해야 가맹점을 쓸 수 있다.
    // 동의 전에는 기기 식별값을 어디에도 보내지 않는다(HardwareId.query).
    public static final String TERMS_VERSION = "2026-10-01.1";

    private static volatile String termsAcceptedVersion;

    public static boolean isTermsAccepted() {
        String v = termsAcceptedVersion;
        if (v == null) {
            JsonObject o = readSettingsFile();
            v = o != null && o.has("termsAccepted") ? o.get("termsAccepted").getAsString() : "";
            termsAcceptedVersion = v;
        }
        return TERMS_VERSION.equals(v);
    }

    public static void setTermsAccepted() {
        termsAcceptedVersion = TERMS_VERSION;
        updateSettingsFile(o -> o.addProperty("termsAccepted", TERMS_VERSION));
    }

    /** 지금 가맹점을 쓰고 있고 약관에도 동의했는지 — 기기 식별값을 보내도 되는 유일한 경우. */
    public static boolean franchiseConsented() {
        return getServerProfile() == ServerProfile.FRANCHISE && isTermsAccepted();
    }

    // ── 공지 알림창 — 같은 공지는 한 번만 띄운다 ──────────────────────────────

    // 본점·가맹점 공지를 따로 기억한다 — 하나만 기억하면 매장을 오갈 때마다 이미 본 공지가 다시 떴다.
    private static final java.util.Map<ServerProfile, String> lastNotice = new java.util.concurrent.ConcurrentHashMap<>();

    private static String noticeKey(ServerProfile p) {
        return p == ServerProfile.FRANCHISE ? "lastNotice" : "lastNoticeOfficial";
    }

    public static String lastSeenNotice() {
        return lastSeenNotice(getServerProfile());
    }

    public static String lastSeenNotice(ServerProfile p) {
        return lastNotice.computeIfAbsent(p, k -> {
            JsonObject o = readSettingsFile();
            String key = noticeKey(k);
            return o != null && o.has(key) ? o.get(key).getAsString() : "";
        });
    }

    public static void setLastSeenNotice(String text) {
        setLastSeenNotice(getServerProfile(), text);
    }

    public static void setLastSeenNotice(ServerProfile p, String text) {
        if (text.equals(lastNotice.get(p))) return;
        lastNotice.put(p, text);
        updateSettingsFile(o -> o.addProperty(noticeKey(p), text));
    }

    public static boolean isSafetyWarningDismissed() {
        return safetyWarningDismissed;
    }

    public static void setSafetyWarningDismissed(boolean value) {
        if (safetyWarningDismissed == value) return;
        safetyWarningDismissed = value;
        updateSettingsFile(o -> {
            o.addProperty("safetyWarningDismissed", value);
            o.addProperty("safetyWarningRev", SAFETY_WARNING_REV);
        });
    }

    private static boolean loadSafetyWarningDismissed() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("safetyWarningDismissed") && o.get("safetyWarningDismissed").getAsBoolean()
                && o.has("safetyWarningRev") && o.get("safetyWarningRev").getAsInt() >= SAFETY_WARNING_REV;
    }

    /** 방송 비허용 방에 접속하려 할 때 뜨는 확인 팝업의 "다시 보지 않기" — 기본은 매번 뜬다.
     * safetyWarningDismissed와는 별개(하나를 껐다고 다른 하나까지 같이 안 뜨면 안 된다). */
    private static volatile boolean broadcastJoinWarningDismissed = loadBroadcastJoinWarningDismissed();

    public static boolean isBroadcastJoinWarningDismissed() {
        return broadcastJoinWarningDismissed;
    }

    public static void setBroadcastJoinWarningDismissed(boolean value) {
        if (broadcastJoinWarningDismissed == value) return;
        broadcastJoinWarningDismissed = value;
        updateSettingsFile(o -> o.addProperty("broadcastJoinWarningDismissed", value));
    }

    private static boolean loadBroadcastJoinWarningDismissed() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("broadcastJoinWarningDismissed") && o.get("broadcastJoinWarningDismissed").getAsBoolean();
    }

    /** 방송 허용을 켠 채로 방을 열 때 뜨는 "스트리머 보호 기능 활성화" 안내 팝업의 "다시 보지 않기"
     * — 기본은 매번 뜬다. 다른 경고 dismissed 값들과 완전히 독립적이다. */
    private static volatile boolean streamerProtectionWarningDismissed = loadStreamerProtectionWarningDismissed();

    public static boolean isStreamerProtectionWarningDismissed() {
        return streamerProtectionWarningDismissed;
    }

    public static void setStreamerProtectionWarningDismissed(boolean value) {
        if (streamerProtectionWarningDismissed == value) return;
        streamerProtectionWarningDismissed = value;
        updateSettingsFile(o -> o.addProperty("streamerProtectionWarningDismissed", value));
    }

    private static boolean loadStreamerProtectionWarningDismissed() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("streamerProtectionWarningDismissed") && o.get("streamerProtectionWarningDismissed").getAsBoolean();
    }

    /** 정원이 찬 방에 개발자·서포터 특권으로 밀고 들어갈 때 뜨는 확인 팝업의 "다시 보지 않기" —
     * 기본은 매번 뜬다. 개발자·서포터도 이제 정원에 그대로 세이므로(countedPlayers), 특권은
     * "정원이 차도 들어갈 수 있다"는 뜻이 됐다 — 조용히 들어가는 대신 한 번은 스스로 확인하게 한다. */
    private static volatile boolean capacityBypassWarningDismissed = loadCapacityBypassWarningDismissed();

    public static boolean isCapacityBypassWarningDismissed() {
        return capacityBypassWarningDismissed;
    }

    public static void setCapacityBypassWarningDismissed(boolean value) {
        if (capacityBypassWarningDismissed == value) return;
        capacityBypassWarningDismissed = value;
        updateSettingsFile(o -> o.addProperty("capacityBypassWarningDismissed", value));
    }

    private static boolean loadCapacityBypassWarningDismissed() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("capacityBypassWarningDismissed") && o.get("capacityBypassWarningDismissed").getAsBoolean();
    }

    /** 치지직 연동 조회(link/status) 인증용 — 이 설치본이 한 번 만들어서 계속 재사용하는 무작위
     * 값. mc-signaling이 link/start 때 같이 받아 그 연동(ChzzkLink)에 저장해두고, 나중에
     * link/status에서 똑같은 값을 요구한다 — 아니면 남의 UUID로 아무나 "이 사람이 어떤 치지직
     * 채널과 연동됐는지" 조회할 수 있었다(mc-signaling chzzk.go의 ChzzkLink 주석 참고). 처음
     * 쓰일 때 없으면 그때 만들어서 저장한다(굳이 모드 설치 시점에 미리 만들 필요 없음). */
    private static volatile String chzzkViewToken;

    public static synchronized String getOrCreateChzzkViewToken() {
        if (chzzkViewToken != null) return chzzkViewToken;
        JsonObject o = readSettingsFile();
        String loaded = o != null && o.has("chzzkViewToken") ? o.get("chzzkViewToken").getAsString() : null;
        chzzkViewToken = loaded != null && !loaded.isEmpty() ? loaded : java.util.UUID.randomUUID().toString();
        if (!chzzkViewToken.equals(loaded)) updateSettingsFile(obj -> obj.addProperty("chzzkViewToken", chzzkViewToken));
        return chzzkViewToken;
    }

    /** 방 목록에서 내 마인크래프트 버전과 다른 방을 숨길지 — 기본은 보여준다(회색으로). */
    private static volatile boolean hideOtherVersions = loadHideOtherVersions();

    public static boolean isHideOtherVersions() {
        return hideOtherVersions;
    }

    public static void setHideOtherVersions(boolean value) {
        if (hideOtherVersions == value) return;
        hideOtherVersions = value;
        updateSettingsFile(o -> o.addProperty("hideOtherVersions", value));
    }

    private static boolean loadHideOtherVersions() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("hideOtherVersions") && o.get("hideOtherVersions").getAsBoolean();
    }

    /**
     * <b>방송(인터넷 스트리밍) 노출 허용.</b>
     * <p>
     * 새 신호 필드 없이 채널 시스템을 그대로 재사용한다 — 이 값이 켜져 있으면 공지되는 채널 문자열에
     * {@link #BROADCAST_TAG}가 하나 더 붙을 뿐이고(PublicRoomAnnouncer.sendUpdate 참고), 실제 접속 lobby
     * (getEffectiveChannels)는 안 건드린다 — 즉 이 방을 원래 볼 수 있는 사람에게만(채널이 겹치는 사람)
     * "방송 허용" 여부가 추가로 드러날 뿐, 못 보던 사람이 새로 보이게 되는 건 아니다. 기본값은 꺼짐
     * (노출에 동의하는 사람만 켠다).
     */
    private static volatile boolean allowBroadcast = loadAllowBroadcast();

    public static boolean isAllowBroadcast() {
        return allowBroadcast;
    }

    public static void setAllowBroadcast(boolean value) {
        if (allowBroadcast == value) return;
        allowBroadcast = value;
        updateSettingsFile(o -> o.addProperty("allowBroadcast", value));
    }

    private static boolean loadAllowBroadcast() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("allowBroadcast") && o.get("allowBroadcast").getAsBoolean();
    }

    /** 방 목록에서 "방송 허용" 여부로 방을 어떻게 거를지 — 전체(기본)/허용만/비허용만 세 상태.
     * 체크박스(2상태)로는 "비방송만 보기"를 표현할 수 없어서(꺼짐이 "전체"만 뜻함) 순환 버튼으로 둔다. */
    public enum BroadcastFilter { ALL, ALLOWED_ONLY, DISALLOWED_ONLY }

    private static volatile BroadcastFilter broadcastFilter = loadBroadcastFilter();

    public static BroadcastFilter getBroadcastFilter() {
        return broadcastFilter;
    }

    public static void setBroadcastFilter(BroadcastFilter value) {
        if (broadcastFilter == value) return;
        broadcastFilter = value;
        updateSettingsFile(o -> o.addProperty("broadcastFilter", value.name()));
    }

    private static BroadcastFilter loadBroadcastFilter() {
        JsonObject o = readSettingsFile();
        if (o == null || !o.has("broadcastFilter")) return BroadcastFilter.ALL;
        try {
            return BroadcastFilter.valueOf(o.get("broadcastFilter").getAsString());
        } catch (IllegalArgumentException e) {
            return BroadcastFilter.ALL;
        }
    }

    /** 채널 문자열에 붙는 예약 태그 — 사용자가 직접 만드는 채널 목록(최대 {@link #MAX_CHANNEL_LENGTH}자,
     * 쉼표 구분)과는 별개로 공지 시에만 덧붙는다(setChannels로 저장되는 목록엔 안 들어간다). */
    private static final String BROADCAST_TAG = "broadcast";

    /** 공지용 채널 문자열 — {@link #isAllowBroadcast()}가 켜져 있으면 {@link #BROADCAST_TAG}를 덧붙인다.
     * and 모드(여러 채널을 하나로 묶어 보내는 규칙)에서도 그냥 붙인다 — {@link #roomVisible}로 실제 채널
     * 매칭에 쓰기 전에 {@link #stripBroadcastTag}로 먼저 떼어내는 쪽(PublicRoomBrowser)이 책임진다.
     * 방송 허용 여부는 사용자가 정한 채널 목록·and/or 규칙과는 독립적인 별개의 태그로 취급한다. */
    public static String announcedChannel() {
        String base = getChannel();
        return allowBroadcast ? base + "," + BROADCAST_TAG : base;
    }

    /** 방 목록에서 받은 채널 문자열에 방송 허용 태그가 있는지 — {@link #isBroadcastOnly()} 필터용. */
    public static boolean isBroadcastTagged(String channelStr) {
        if (channelStr == null) return false;
        for (String part : channelStr.split(",")) {
            if (part.trim().equalsIgnoreCase(BROADCAST_TAG)) return true;
        }
        return false;
    }

    /** {@link #roomVisible}에 넘기기 전에 방송 태그를 떼어낸 채널 문자열 — and 묶음 채널 매칭이 방송
     * 태그 때문에 깨지지 않게(붙인 채로 넘기면 묶인 값 자체가 달라져 원래 겹쳐야 할 채널까지 안 보인다). */
    public static String stripBroadcastTag(String channelStr) {
        if (channelStr == null) return null;
        StringBuilder out = new StringBuilder();
        for (String part : channelStr.split(",", -1)) {
            if (part.trim().equalsIgnoreCase(BROADCAST_TAG)) continue;
            if (out.length() > 0) out.append(',');
            out.append(part);
        }
        return out.toString();
    }

    /**
     * settings.json의 다른 키(예: channel)를 안 지우고 이 키만 갈아끼운다 — 예전엔
     * 각 설정이 자기 값 하나만 담은 JsonObject를 통째로 새로 만들어 파일 전체를
     * 덮어썼다. relayOnly/roomCategory처럼 서로 다른 설정이 같은 파일을 같이
     * 쓰는 상황에서, 하나를 바꾸면 다른 하나가 (다음 로드 때) 조용히 기본값으로
     * 되돌아가는 버그가 있었다 — UI 없이 파일을 직접 편집하던 옛 카테고리
     * 설정은 거의 안 드러났지만, 이제 채널처럼 화면에서 바로 바꾸는 설정이
     * 늘면서 실제로 부딫힐 수 있어 여기서 고쳤다.
     */
    private static void updateSettingsFile(java.util.function.Consumer<JsonObject> mutate) {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject o = readSettingsFile();
            if (o == null) o = new JsonObject();
            mutate.accept(o);
            try (Writer w = new FileWriter(SETTINGS_FILE.toFile())) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings save failed: {}", e.getMessage());
        }
    }

    private static JsonObject readSettingsFile() {
        if (!Files.exists(SETTINGS_FILE)) return null;
        try (Reader r = new FileReader(SETTINGS_FILE.toFile())) {
            return GSON.fromJson(r, JsonObject.class);
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings load failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * <b>공개 방 채널.</b>
     * <p>
     * 하나의 값이 호스팅/조회 양쪽에 다 쓰인다: 방을 공개로 열면 이 값이 그 방의
     * 채널로 태그되고(PublicRoomAnnouncer), 방 목록을 볼 때도 이 값으로 필터링된다
     * (RoomListScreen이 매 tick 다시 읽어 적용 — PublicRoomBrowser 자체는 필터링
     * 없이 모든 채널의 방을 다 들고 있다가, 화면이 그중 지금 채널과 일치하는
     * 것만 추려서 보여준다. 필터를 여기로 뺀 이유는, 그렇게 안 하면 채널을
     * 입력란에서 바로 바꿔도 다음 서버 브로드캐스트가 올 때까지 목록이 안
     * 바뀌어 보였기 때문) — 서로 다른 커뮤니티가 같은 공유 시그널링 relay
     * lobby를 나눠 써도 목록이 섞이지 않게 하기 위함. 기본값 "normal", 대소문자는
     * 구분하지 않는다(오타로 채널이 갈리는 걸 막기 위한 선택 — 필요하면 나중에
     * 구분하게 바꿀 수 있다). 빈 문자열은 저장 시 기본값으로 되돌린다.
     */
    // 채널은 여러 개다 — 쉼표로 구분해 저장하고("normal,minedapple"), 방을 공개할 때 목록 전체와 or/and 규칙이
    // 같이 실려 나간다. 보는 쪽은 자기 채널 목록으로 그 규칙을 판정한다(roomVisible).
    private static volatile List<String> channels = loadChannels();
    /** 내 방을 공개할 때의 규칙 — true(and)면 접속자가 내 채널을 전부 갖고 있어야 보이고, false(or)면 하나만 겹쳐도 보인다. */
    private static volatile boolean channelAnd = loadChannelAnd();

    /** 내 채널 목록 — 비어 있지 않다(기본 "normal"). */
    public static List<String> getChannels() {
        return channels;
    }

    /** 방 공지에 실리는 채널 문자열("a,b") — 입력란 표시에도 그대로 쓴다. */
    public static String getChannel() {
        return String.join(",", channels);
    }

    /** 채널 목록 + 규칙을 한 문자열로 — 방 설정이 바뀌었는지 비교할 때 쓴다. */
    public static String getChannelKey() {
        return getChannel() + (channelAnd ? "&" : "|");
    }

    public static boolean isChannelAnd() {
        return channelAnd;
    }

    public static void setChannelAnd(boolean value) {
        if (channelAnd == value) return;
        channelAnd = value;
        updateSettingsFile(o -> o.addProperty("channelAnd", value));
    }

    /** 채널 개수 상한 — 채널마다 lobby에 접속(방장 1개, 구경꾼 샤드 수만큼)하므로 소켓 수를 묶어 둔다. */
    public static final int MAX_CHANNELS = 5;
    /** 채널 하나의 글자 수 상한 — 부하와는 무관(채널 이름은 고정 크기 해시로만 쓰인다), 칩 표시가 줄바꿈
     * 없이 들어가고 공지 payload 크기를 예측 가능하게 두기 위함. */
    public static final int MAX_CHANNEL_LENGTH = 24;

    /** 쉼표로 구분한 입력을 목록으로 — 공백은 걷어내고, 빈 항목("a,,b"의 가운데, 맨 앞)은 기본 채널로 친다.
     * 맨 끝 쉼표 하나("a,")는 아직 입력 중인 것으로 보고 무시한다. 대소문자 무시 중복 제거, MAX_CHANNELS개까지,
     * 채널 하나당 MAX_CHANNEL_LENGTH자까지(넘으면 자른다). */
    public static List<String> parseChannels(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = (text == null ? "" : text).split(",", -1);
        int n = parts.length;
        if (n > 1 && parts[n - 1].isBlank()) n--;
        for (int i = 0; i < n; i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) t = DEFAULT_CHANNEL;
            if (t.length() > MAX_CHANNEL_LENGTH) t = t.substring(0, MAX_CHANNEL_LENGTH);
            if (out.stream().anyMatch(t::equalsIgnoreCase)) continue;
            if (out.size() >= MAX_CHANNELS) break;
            out.add(t);
        }
        return List.copyOf(out);
    }

    public static void setChannels(String text) {
        List<String> parsed = parseChannels(text);
        if (parsed.equals(channels)) return;
        channels = parsed;
        updateSettingsFile(o -> o.addProperty("channels", String.join(",", parsed)));
    }

    /** 규칙을 적용한 "실제 채널" 목록 — or면 적은 채널 하나하나가 각각 채널이고, and면 전부를 묶은 채널 하나다
     * (소문자·정렬·입력 불가능한 구분 문자로 이어서, "a,b"를 and로 쓰는 사람끼리만 같은 값이 된다). lobby 이름과
     * 보임 판정이 둘 다 이 목록을 쓴다 — 채널이 하나라도 겹쳐야 서로 보인다. */
    public static List<String> effectiveChannels(List<String> channels, boolean and) {
        if (!and || channels.size() <= 1) return channels;
        return List.of(channels.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).sorted()
                .collect(java.util.stream.Collectors.joining(String.valueOf(AND_SEPARATOR))));
    }

    public static List<String> getEffectiveChannels() {
        return effectiveChannels(channels, channelAnd);
    }

    /** 이 방이 내게 보이는지 — 방장의 실제 채널과 내 실제 채널이 하나라도 겹치면. 대소문자 구분 없음. */
    public static boolean roomVisible(String hostChannels, boolean hostAnd, List<String> mine, boolean mineAnd) {
        List<String> host = effectiveChannels(parseChannels(hostChannels), hostAnd);
        List<String> me = effectiveChannels(mine, mineAnd);
        return host.stream().anyMatch(h -> me.stream().anyMatch(h::equalsIgnoreCase));
    }

    /** and 묶음 채널의 구분 문자 — 입력란으로는 칠 수 없는 제어 문자라 "a&b" 같은 채널 이름과 안 겹친다. */
    private static final char AND_SEPARATOR = '\u001F';

    private static final String DEFAULT_CHANNEL = "normal";

    private static List<String> loadChannels() {
        JsonObject o = readSettingsFile();
        return parseChannels(o == null || !o.has("channels") ? null : o.get("channels").getAsString());
    }

    private static boolean loadChannelAnd() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("channelAnd") && o.get("channelAnd").getAsBoolean();
    }

    /**
     * 공개 방 목록용 고정 lobby 경로 접두사("roomId" 자리에 들어가는 특수값).
     * 실제 초대 코드({@link kfc.udp.client.KfcudpClient} 참고, 대문자+숫자
     * 10자)와 절대 겹치지 않도록 소문자+밑줄로 구성했다. 공개 방을 연 호스트는
     * 전부 이 lobby들 중 하나에도 접속해서(자기 원래 방 lobby와는 별개) 자신을
     * peer로 announce하고, 방 목록 화면은 모든 샤드 lobby에 동시 접속해서 현재
     * peer 목록만 읽어 합친 걸 공개 방들로 나열한다 — 새 서버 인프라 없이 기존
     * VILLASframework signaling relay의 peer 목록 브로드캐스트를 그대로
     * 재사용하는 방식(PublicRoomAnnouncer/PublicRoomBrowser 참고).
     * <p>
     * <b>왜 하나가 아니라 여러 개로 샤딩하는가</b> — signaling 서버(session.go)는
     * 세션 하나에 모인 peer가 접속/해제될 때마다 그 세션에 모인 "모든" peer에게
     * 전체 목록을 다시 뿌린다({@code SendControlMessageToAllConnectedPeers}) —
     * 세션 하나에 몰리는 peer(호스트+관전자)가 많아질수록 이벤트 하나당 비용이
     * 같이 커지고, 그 세션은 자기 전용 goroutine 하나가 순차 처리하므로 코어를
     * 하나만 쓴다. lobby를 {@link #PUBLIC_ROOM_SHARD_COUNT}개로 쪼개면 각 lobby가
     * 별도 goroutine으로 독립적으로 돌아 여러 코어에 자연히 분산되고, lobby 하나당
     * peer 수도 대략 1/N로 줄어든다 — 서버 코드를 안 건드리고 클라이언트가 접속
     * 경로만 나눠 쓰는 것만으로 되는 개선이라 서버 재배포가 필요 없다.
     */
    private static final String PUBLIC_ROOMS_LOBBY_PREFIX = "__instant_p2p_public_rooms__";

    /** 공개 방 목록 lobby 샤드 개수 — 클래스 주석 참고. 서버 재배포 없이 이 상수만 바꾸면 된다. */
    public static final int PUBLIC_ROOM_SHARD_COUNT = 4;

    /** 방 코드를 이 개수의 샤드 중 하나로 결정적으로 배정한다(같은 코드는 항상 같은 샤드). */
    public static int publicRoomShardFor(String roomCode) {
        return Math.floorMod(roomCode.hashCode(), PUBLIC_ROOM_SHARD_COUNT);
    }

    /** 채널 하나의 shard(0..{@link #PUBLIC_ROOM_SHARD_COUNT}-1)번 공개 방 목록 lobby의 실제 경로.
     * 모드 버전(MOD_VERSION)까지 해시에 같이 섞는다 — 모드 버전이 다르면(프로토콜이 다를 수 있음)
     * 그 방 공지·목록 갱신은 애초에 이 lobby로 안 온다. 마인크래프트 버전은 일부러 안 섞는다 —
     * 마인크래프트 버전이 달라도 방 자체는(회색으로) 보여야 하고, 실제 차단은 RoomEntry.
     * sameVersion()이 접속 시도만 막는 걸로 충분하다(다른 마인크래프트 버전끼리도 친구 방이
     * 열려있다는 사실 자체는 알 수 있게). 반면 모드 버전이 다른 방은 애초에 안 보여야 한다는
     * 요구라 lobby 자체를 모드 버전별로 나눴다 — "다른 버전 숨기기" 체크박스·회색 표시는
     * 이제 마인크래프트 버전 불일치에만 걸린다(모드 버전 불일치는 항상 lobby 단에서 걸러짐). */
    public static String publicRoomsLobbyId(String channel, int shard) {
        // 채널 이름·모드 버전 둘 다 해시로만 나간다(서버 로그·URL에 원문이 안 남게).
        return PUBLIC_ROOMS_LOBBY_PREFIX + "_" + channelTag(channel) + "_" + versionTag() + "_" + shard;
    }

    /** 채널 이름(대소문자 무시)의 SHA-256 앞 8바이트 — lobby 이름용. */
    private static String channelTag(String channel) {
        return sha256Prefix8(channel.toLowerCase(java.util.Locale.ROOT));
    }

    /** MOD_VERSION의 SHA-256 앞 8바이트 — publicRoomsLobbyId 주석 참고. 마인크래프트 버전은
     * 일부러 안 섞는다(같은 주석). */
    private static String versionTag() {
        // F4.0.1: 앞 두 자리(예: "1.5.1" → "1.5")만 쓴다 — 1.5.x 는 서로 접속에 문제가 없어 같은 목록에 보이게.
        // "1.5" 그대로의 해시라 F3.5(1.5) 방장·접속자와도 같은 목록이 된다.
        return sha256Prefix8(lobbyVersion(MOD_VERSION));
    }

    static String lobbyVersion(String v) {
        String[] p = v.split("[.+-]");
        return p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty() ? p[0] + "." + p[1] : v;
    }

    private static String sha256Prefix8(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private P2PConfig() {}
}