package kfc.udp.client.quic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kfc.udp.client.KfcudpClient;
import kfc.udp.client.signaling.BookSnapshot;
import kfc.udp.client.signaling.BuildProof;
import kfc.udp.client.signaling.ChatContext;
import kfc.udp.client.signaling.MojangAuth;
import kfc.udp.client.signaling.P2PBanManager;
import kfc.udp.client.signaling.P2PConfig;
import kfc.udp.client.signaling.PublicRoomAnnouncer;
import kfc.udp.client.signaling.Roles;
import kfc.udp.client.signaling.SignSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * P2P 전송 파사드 — 주소 프리픽스({@code quic.})를 보고 갈라지는 지점.
 * (예전에 있던 {@code kcp.} 직접 접속 경로는 F3.4에서 뺐다 — P2P 방과 무관하고, 안 써도 게임 시작 때 Netty 스레드 묶음을 만들었다.)
 * <p>
 * 예전 {@code WebRtcBridge}를 대체한다. WebRTC(libwebrtc)는 QUIC이 검증된 뒤 통째로 지웠고,
 * 그때 이 클래스가 겸하고 있던 <b>공개 방 목록</b>과 <b>kcp 주소 파싱</b>도 같이 넘어왔다 —
 * 둘 다 전송 방식과 무관한 기능이라 남아야 했다.
 * <p>
 * 이름이 {@code Quic}으로 시작하는데 공개 방 목록까지 들고 있는 건 어색하지만, 호출부 30여 곳을
 * 다시 고치는 값어치는 없다고 보고 그대로 뒀다.
 */
public final class QuicBridge {

    public static final Logger LOG = LoggerFactory.getLogger("quic-bridge");

    /** 마크가 붙을 로컬 루프백 포트. 이미 쓰이고 있으면 findFreePort 가 임의 포트로 넘어간다. */
    private static final int LOCAL_PORT = 25566;

    private static volatile QuicClient client;
    private static volatile QuicHost host;

    /** 공개 방 목록 announcer — 방 열 때 "공개 허용"이면 같이 시작하고 닫을 때 같이 멈춘다. */
    private static final PublicRoomAnnouncer publicRoomAnnouncer = new PublicRoomAnnouncer();

    /** QuicHost 가 게임에 기대는 부분 — QuicHost 를 마인크래프트에서 떼어 두려고 여기서 잇는다. */
    private static final HostHooks GAME_HOOKS = new HostHooks() {
        @Override public boolean relayOnlyNow() { return P2PConfig.isRelayOnly(); }
        @Override public String authToken() { return MojangAuth.ensureToken(); }
        @Override public void registerTunnelPort(int localPort, String realIp) {
            P2PBanManager.registerTunnelPort(localPort, realIp);
        }
        @Override public void unregisterTunnelPort(int localPort) {
            P2PBanManager.unregisterTunnelPort(localPort);
        }
        @Override public void registerConnectionType(String realIp, boolean usesRelay) {
            P2PBanManager.registerConnectionType(realIp, usesRelay);
        }
        @Override public String onlinePlayerHashes(String roomId) {
            return P2PBanManager.encodeOnlinePlayerHashes(roomId);
        }
        // ── 1.5 ──
        @Override public void registerTunnelType(int localPort, java.util.function.Supplier<Boolean> relayed) {
            P2PBanManager.registerTunnelType(localPort, relayed);
        }
        @Override public String clientInfo() { return QuicBridge.clientInfo(); }
        @Override public String roomInfo() { return KfcudpClient.roomInfoJson(); }
        @Override public String roomAcl() { return P2PBanManager.aclJson(); }
        @Override public void onSignaling(boolean up, String reason) { KfcudpClient.onSignalingState(up, reason); }
        @Override public void onFloodDefense(boolean on) { KfcudpClient.onFloodDefense(on); }
        @Override public void onServerNotice(String notice) { KfcudpClient.onServerNotice(notice); }
        @Override public String chatWindow(long fromMs, long toMs, String room) {
            return QuicBridge.chatWindowJson(fromMs, toMs, room);
        }
        @Override public JsonArray signTexts(JsonArray items) { return SignSnapshot.hostAnswer(items); }
        @Override public JsonArray bookHashes(JsonArray items) { return BookSnapshot.hostAnswer(items); }
    };

    private QuicBridge() {}

    /**
     * 초대 코드로 쓸 수 있는 글자 — {@code KfcudpClient.CODE_CHARS} 가 대문자·숫자만 쓰므로
     * 그 범위로만 받는다. 길이는 넉넉히 둔다(코드 규칙이 바뀌어도 여기서 막히지 않게).
     */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Z0-9]{4,32}");

    /**
     * {@code quic.<코드>} 에서 방 코드를 뽑는다. 코드가 아니면 <b>null</b> — 그러면 바닐라가
     * 평소처럼 주소로 취급해 자기 오류를 띄운다.
     * <p>
     * 검사를 여기서 하는 이유 — 코드는 시그널링 WS 경로에 그대로 들어간다. 예전엔 걸러지지 않아
     * 공백이 섞인 값("I go")이 들어오면 {@code Illegal character in path} 예외가 접속 실패 사유로
     * 그대로 노출됐다. 진입점이 여럿이라(초대 코드 화면·방 목록·직접 연결) 소비하는 이 한 곳에서
     * 막는 게 확실하다.
     * <p>
     * 소문자는 대문자로 올려 준다 — 코드는 대문자로만 만들어지므로 붙여넣기 실수로 실패할 이유가 없다.
     */
    public static String parseRoomId(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        if (!trimmed.startsWith("quic.")) return null;
        String code = trimmed.substring("quic.".length()).trim()
                .toUpperCase(java.util.Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            LOG.warn("[QUIC] 초대 코드 형식이 아니다 — 일반 주소로 넘긴다 (길이 {})", code.length());
            return null;
        }
        return code;
    }

    /** @return 마크 클라이언트가 접속할 로컬 포트 */
    /** 접속자로 들어가 있는 방 코드(접속 상태 신호용) — 없으면 null. */
    private static volatile String guestRoomId;

    public static String activeGuestRoom() {
        return client != null ? guestRoomId : null;
    }

    public static int start(String roomId) throws Exception {
        stop();
        // 취약한 자바면 P2P 를 시작하지 않는다(가맹점 F3.5~, JreGuard — 화면 쪽 확인을 건너뛴 경로의 마지막 방어선).
        if (kfc.udp.client.JreGuard.blocksP2p()) throw new IllegalStateException("java-old " + kfc.udp.client.JreGuard.currentText());
        Roles.refreshAsync(); // 들어간 방의 배지·특권 판단이 최신 명단으로 되게(1.5~)
        guestRoomId = roomId;
        int port = findFreePort();
        LOG.debug("[QUIC] starting client port={}", port);
        String[] turn = MojangAuth.turnCredentials(); // 보통 미리 받아 둔 값이라 바로 온다
        QuicClient c = new QuicClient(roomId, port, P2PConfig.signalingUrl(), P2PConfig.stunUrl(),
                turn != null ? P2PConfig.turnUrl() : null, turn != null ? turn[0] : null, turn != null ? turn[1] : null,
                P2PConfig.isRelayOnly());   // 「중계 통신 강제」 체크박스 값
        c.info = clientInfo();
        // 1.5~: 서버가 정품 확인을 요구할 수 있어 토큰을 확실히 받아 둔다(남은 시간이 넉넉하면 네트워크 없이 바로 준다).
        c.authToken = MojangAuth.ensureToken();
        client = c;
        c.start();
        return port;
    }

    /**
     * 랑데부 URL 에 붙이는 클라이언트 정보(1.5~) — v=모드 버전, mc=마크 버전, j=자바 버전, b=jar SHA-256.
     * 서버가 낡은 모드(too-old)·취약한 자바(java-old)·비공식 빌드(unofficial)를 랑데부 단계에서 거른다.
     * 가맹점 빌드는 fb=가맹점 수정판 번호도 붙인다 — 가맹점 서버가 F 판 별로 거르거나 안내할 수 있게.
     */
    static String clientInfo() {
        String s = "v=" + URLEncoder.encode(P2PConfig.MOD_VERSION, StandardCharsets.UTF_8)
                + "&mc=" + URLEncoder.encode(P2PConfig.MC_VERSION, StandardCharsets.UTF_8)
                + "&j=" + URLEncoder.encode(Runtime.version().toString(), StandardCharsets.UTF_8)
                + "&b=" + BuildProof.sha256();
        if (P2PConfig.getServerProfile() == P2PConfig.ServerProfile.FRANCHISE) {
            s += "&fb=" + URLEncoder.encode(P2PConfig.FRANCHISE_BUILD, StandardCharsets.UTF_8);
        }
        return s;
    }

    /** 접속자로 들어가 있는 방 코드 — 없으면 null(1.5~, 신고·채팅 기록용). */
    public static String joinedRoomCode() {
        QuicClient c = client;
        return c != null ? c.roomId() : null;
    }

    public static void stop() {
        QuicClient c = client;
        if (c != null) {
            c.close();
            client = null;
        }
    }

    /** 방장 이벤트 하나를 랑데부로 보낸다(HostEventLog → QuicHost.sendHostLog). 방장이 아니면 버린다. */
    public static void hostLog(String eventJson) {
        QuicHost h = host;
        if (h != null) h.sendHostLog(eventJson);
    }

    public static void startHost(String roomId, String target) throws Exception {
        stopHost();
        // 취약한 자바면 P2P 를 시작하지 않는다(가맹점 F3.5~, JreGuard — 화면 쪽 확인을 건너뛴 경로의 마지막 방어선).
        if (kfc.udp.client.JreGuard.blocksP2p()) throw new IllegalStateException("java-old " + kfc.udp.client.JreGuard.currentText());
        Roles.refreshAsync();
        LOG.debug("[QUIC] starting host target={}", target);
        String[] turn = MojangAuth.turnCredentials();
        // TURN 주소는 계정이 없어도 넘긴다 — 들어온 연결이 중계를 거쳤는지 주소로 판정하는 데 쓴다
        // (접속자 쪽 allocation 으로 오면 방장에게 계정이 없어도 출처는 coturn 이다). 계정이 없으면 enableTurn 이 건너뛴다.
        QuicHost h = new QuicHost(roomId, target, P2PConfig.signalingUrl(), P2PConfig.stunUrl(),
                P2PConfig.turnUrl(), turn != null ? turn[0] : null, turn != null ? turn[1] : null,
                GAME_HOOKS);   // 「중계 통신 강제」는 접속자마다 GAME_HOOKS.relayOnlyNow() 로 읽는다
        host = h;
        try {
            h.start();
        } catch (Exception e) {
            // 시작이 도중에 실패하면(시그널링 거절·차단 등) 이미 잡은 중계 자리를 놓는다 — 안 그러면 다음에 방을 열거나
            // 게임을 끌 때까지 갱신되며 남는다.
            h.close();
            throw e;
        }
    }

    public static void stopHost() {
        QuicHost h = host;
        if (h != null) {
            h.close();
            host = null;
        }
        unpublishPublicRoom();
    }

    public static boolean isHostActive() {
        return host != null;
    }


    /** 지금 진행 중인 quic 조인 세션의 연결 방식. null = quic 세션이 없거나 아직 안 붙음.
     * 호출부는 {@code != null}을 "우리 방의 접속자 세션인지" 판별에도 쓴다(KfcudpClient). */
    public static Boolean getActiveConnectionUsesRelay() {
        QuicClient c = client;
        return c != null ? c.usesRelay() : null;
    }

    /** 지금 활성화된 호스트 인스턴스를 식별하는 토큰(단순 참조). */
    public static Object currentHostToken() {
        return host;
    }

    /**
     * token이 여전히 현재 활성 호스트일 때만 중지한다. 방을 연달아 열면 이전 방을 닫으려던
     * 지연 종료 스레드가 그 사이 새로 열린 방을 대신 죽이는 걸 막기 위한 것이다.
     */
    public static void stopHostIfCurrent(Object token) {
        if (token != null && token == host) stopHost();
    }

    // ── 공개 방 목록 (전송 방식과 무관) ───────────────────────────────────────

    /** 방을 공개 목록에 올리거나 이미 올라와 있으면 정보를 갱신한다 — 방 코드·채널이 그대로면
     * 재접속 없이 메시지만 보낸다(PublicRoomAnnouncer 클래스 주석). */
    public static void publishPublicRoom(String roomCode, String title, String hostNickname, String hostUuid,
                                          int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.publish(roomCode, title, hostNickname, hostUuid, currentPlayers, maxPlayers);
    }

    public static void unpublishPublicRoom() {
        publicRoomAnnouncer.stop();
    }

    /** 밴(=차단) 목록이 바뀌면 P2PBanManager가 부른다 — 공개된 방이 있으면 즉시 재공지한다. */
    public static void republishPublicRoomIfActive() {
        publicRoomAnnouncer.republishNow();
    }

    /** 공개 방 인원(현재/최대)이 바뀔 때마다 — 재접속 없이 메시지만 보낸다. */
    public static void updatePublicRoomPlayerCount(int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.updatePlayerCount(currentPlayers, maxPlayers);
    }

    /** 신고 처리용 채팅 조회 응답 — 최근 150줄까지, 한 줄 200자까지(QuicHost.answerChatQuery). */
    static String chatWindowJson(long fromMs, long toMs, String room) {
        List<ChatContext.Line> lines = ChatContext.window(fromMs, toMs, room);
        if (lines.size() > 150) lines = lines.subList(lines.size() - 150, lines.size());
        JsonArray arr = new JsonArray();
        for (ChatContext.Line l : lines) {
            JsonObject o = new JsonObject();
            o.addProperty("t", l.t());
            o.addProperty("from", l.from());
            o.addProperty("line", l.text().length() > 200 ? l.text().substring(0, 200) : l.text());
            arr.add(o);
        }
        return arr.toString();
    }

    /** 방 설정(공개 여부·제목·정원·옵션)이 바뀌었다 — 랑데부에 다시 올린다. */
    public static void roomInfoChanged() {
        QuicHost h = host;
        if (h != null) h.pushRoomInfo();
    }

    /** 차단/허용 목록이 바뀌었다 — 랑데부에 다시 올린다. */
    public static void aclChanged() {
        QuicHost h = host;
        if (h != null) h.pushAcl();
    }

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(LOCAL_PORT)) { return LOCAL_PORT; }
        catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
            catch (IOException ex) { return LOCAL_PORT; }
        }
    }
}
