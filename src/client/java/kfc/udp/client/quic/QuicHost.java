package kfc.udp.client.quic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kfc.udp.client.signaling.Reject;
import kfc.udp.client.signaling.VillasMsg;
import kfc.udp.client.signaling.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnection;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QUIC 방장 — 접속자의 QUIC 스트림을 열린 LAN 포트로 잇는다.
 * <p>
 * <b>시그널링은 랑데부 서버가 짝을 맺어 준다</b>({@code /rv/{roomId}/host}, 서버 rendezvous.go).
 * 접속자가 오면 서버가 sid 를 정해 {@code join} 으로 알려 주고, 그 접속자와의 메시지는 sid 를 붙여
 * 오간다. 우리는 {@code description("quic-answer", 지문)} 과 후보({@code "ip port typ"} 한 줄)를
 * 보내고, 접속자 후보는 트리클로 받는다. 다른 접속자는 이 대화를 볼 수도 끼어들 수도 없다.
 * <b>소켓은 하나다.</b> 접속자마다 따로 열지 않는다 — UDP 소켓 하나로 여러 상대를 받을 수 있고
 * QUIC이 connection ID로 알아서 구분한다. 그 소켓을 {@link QuicIce} 가 STUN용으로 같이 쓰므로
 * 홀펀칭으로 뚫은 5-tuple 위에서 QUIC이 그대로 돈다.
 * <p>
 * <b>방장은 {@code punch()} 결과를 쓰지 않는다</b> — 접속자의 QUIC Initial이 어느 주소로 오든
 * ServerConnector가 받으므로, 방장 쪽 할 일은 자기 NAT에 구멍을 내는 것(= 접속자 후보로 체크를
 * 쏘는 것)과 접속자의 체크에 응답하는 것뿐이다.
 */
public final class QuicHost {

    private static final Logger LOG = LoggerFactory.getLogger("quic-host");

    /** ALPN — QUIC은 응용 프로토콜 이름이 필수다. 양쪽이 같아야 한다. */
    static final String ALPN = "instant-p2p";
    static final String MSG_ANSWER = "quic-answer";
    static final String MSG_OFFER = "quic-offer";
    /** 방장이 「중계 통신 강제」인데 중계 계정이 없다 — 알릴 후보가 없으니 접속자가 30초 기다리지 않게 바로 알린다. */
    static final String MSG_NO_RELAY = "quic-no-relay";
    /** 동시 협상 자리가 {@link #NEGOTIATE_WAIT_MS} 안에 안 났다 — 접속자가 조용히 30초를 기다리지 않고 곧장 다시 시도하게(1.5~). */
    static final String MSG_BUSY = "quic-busy";
    /** 협상 자리가 날 때까지 기다리는 한도. 예전엔 자리가 없으면 그 접속자를 그냥 건너뛰었다. */
    private static final long NEGOTIATE_WAIT_MS = 4_000;

    private static final int DIAL_TIMEOUT_MS = 5_000;
    /** 접속자의 2단계 시도(직결 2초 + 중계 30초)보다 길어야 방장이 먼저 포기하지 않는다. */
    private static final long PUNCH_MS = 35_000;
    /** 랑데부가 끊겼을 때 재접속 간격 — 끊긴 채로 두면 방이 조용히 접속 불가가 된다. */
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final HostHooks hooks;

    private final String turnUrl;
    private final String turnUser;
    private final String turnPass;
    private final String signalingUrl;
    private final String stunUrl;
    private final String roomId;
    private final String targetHost;
    private final int targetPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerConnector server;
    private volatile QuicIce ice;
    private volatile WebSocketClient lobby;
    private volatile String fingerprint;
    private volatile List<QuicIce.Candidate> candidates = List.of();
    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    /** 랑데부가 이번에 붙은 시각(끊기면 0). 붙자마자 끊기길 반복하면 재접속 간격을 다시 늘린다(onDisconnected). */
    private volatile long lobbyUpSince;

    /**
     * 지금 붙어 있는 접속자 연결. 방을 닫을 때 <b>우리가 먼저 닫아</b> kwik 의 종료 대기를 비운다
     * ({@link #close()} 주석). 끊기면 리스너가 빼 주므로 오래 살아도 쌓이지 않는다.
     */
    private final java.util.Set<QuicConnection> live = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 협상 중인 접속자(서버가 붙여 준 sid) → 그 접속자가 보낸 후보. 후보는 sid 로 여기 모인다. */
    private final Map<String, List<QuicIce.Candidate>> joiners = new ConcurrentHashMap<>();

    /** sid → 진행 중인 협상. 접속자가 떠나면(leave) 끊는다 — 안 끊으면 떠난 접속자에게 35초 동안 체크를
     *  보내며 협상 자리(negotiating)를 붙잡아, 그 사이 다시 들어온 사람이 "동시 협상이 너무 많다"로 막혔다. */
    private final Map<String, java.util.concurrent.Future<?>> negotiations = new ConcurrentHashMap<>();

    /**
     * 랑데부 서버에서 이 방 코드를 잡아 두는 비밀. 방장이 잠깐 끊겼다 다시 붙어도 같은 key 여야 방을
     * 되찾을 수 있다 — 코드만 아는 남이 그 사이 방장 자리를 가로채지 못하게(서버 rendezvous.go).
     */
    private final String hostKey = java.util.UUID.randomUUID().toString().replace("-", "");

    /** 동시에 진행하는 접속 협상 수 상한(onJoin 주석). */
    private final java.util.concurrent.Semaphore negotiating = new java.util.concurrent.Semaphore(4);

    /**
     * 접속 표 — 랑데부로 협상한 접속자에게만 주는 일회용 무작위 값(값 = 만료 시각). 접속자는 <b>모든 스트림의
     * 맨 앞 {@link #TICKET_BYTES} 바이트</b>에 이걸 싣고, 방장은 표가 맞는 연결만 받는다.
     * <p>
     * 왜 필요한가 — 방장의 UDP 주소는 접속자 전원(공개 방이면 누구나)에게 후보로 나가는데, QUIC 서버는 그
     * 포트로 오는 연결을 <b>누구에게서나</b> 받는다. 그러면 랑데부의 접속 제한(방당 8명·IP당 분당 20회·동시
     * 협상 4개)을 통째로 건너뛰고 직접 들어와, 연결마다 스트림 100개 × (스레드 + 마크 서버로의 TCP)를 만들어
     * 방장 PC 를 마비시킬 수 있었다(WebRTC 는 협상한 상대만 연결됐다).
     * <p>
     * 표는 스트림 데이터 안에 실어 <b>암호화된 뒤</b>에 나간다 — ALPN 에 싣는 방법도 있지만 그건 핸드셰이크에
     * 평문으로 나가 같은 망의 누구나 볼 수 있고, kwik 의 ALPN 등록부는 도중에 바꿀 수 없다. 표 없는 연결은
     * 첫 스트림에서 {@link #TICKET_WAIT_MS} 안에 연결째 끊긴다.
     */
    private final Map<String, Long> tickets = new ConcurrentHashMap<>();
    static final int TICKET_BYTES = 16;
    /** 표 유효 시간 — 접속자가 핸드셰이크를 몇 번 시도해도 넉넉하되 오래 두지는 않는다. */
    private static final long TICKET_TTL_MS = 120_000;
    /** 첫 스트림에서 표를 읽을 때까지 기다리는 한도. kwik 스트림 읽기엔 시간 제한이 없어 연결을 닫아 깨운다. */
    private static final long TICKET_WAIT_MS = 3_000;
    /** 표를 아직 못 낸(확인 중인) 연결의 동시 상한 — 표 없는 연결이 몰려도 스레드가 무한히 늘지 않게. */
    private final java.util.concurrent.Semaphore unauth = new java.util.concurrent.Semaphore(32);
    private final java.util.concurrent.ScheduledExecutorService watchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "quic-host-watchdog");
                t.setDaemon(true);
                return t;
            });
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private volatile long lastRogueLog;

    // ── 1.5: 연결 단위 제한 ──────────────────────────────────────────────────
    // 방장 UDP 주소는 공개 방이면 누구에게나 나가므로, 표가 없어도 연결은 만들 수 있다 — 연결 수·인증 전 연결 수·
    // 터널 트래픽을 주소(capKey) 단위로 묶어 막는다. 중계(TURN)로 온 연결은 출처 IP 가 전부 TURN 서버라
    // IP:포트 로 묶는다(capKey 주석).
    /** 퍼붓다 끊긴 주소 → 다시 받기 시작하는 시각. */
    private final Map<String, Long> denied = new ConcurrentHashMap<>();
    private static final long DENY_MS = 600_000;
    /** 한 주소의 동시 연결 상한. */
    private static final int MAX_CONN_PER_IP = 6;
    private final Map<String, Integer> connsPerIp = new ConcurrentHashMap<>();
    /** 한 주소의 인증 전(표 확인 중) 연결 상한. 전체 상한({@link #unauth})과 별도로 둔다 — 한 사람이 32자리를 다 먹지 못하게. */
    private static final int MAX_UNAUTH_PER_IP = 3;
    private final Map<String, Integer> unauthPerIp = new ConcurrentHashMap<>();
    /** 접속자 → 방장 터널 트래픽 상한: 초당 1MB, 순간 2MB, 1분 안에 20초 넘게 막히면 퍼붓는 것으로 보고 끊는다. */
    private static final double UP_RATE = 1_000_000;
    private static final double UP_BURST = 2_000_000;
    private static final long UP_MAX_BLOCKED_MS = 20_000;
    private final Map<QuicConnection, QuicPump.Throttle> throttles = new ConcurrentHashMap<>();
    /** 표 → 그 표를 받은 접속자 sid. 연결이 표를 내면 어느 sid 인지 알게 되어 서버에 peer 상태를 알린다. */
    private final Map<String, String> ticketSid = new ConcurrentHashMap<>();
    private final Map<QuicConnection, String> connSid = new ConcurrentHashMap<>();
    private final Map<QuicConnection, Boolean> connRelay = new ConcurrentHashMap<>();

    // ── 1.5: 신고 처리용 조회 응답(채팅·표지판·책) — 분당 6회까지만 ────────────
    private final java.util.concurrent.atomic.AtomicInteger chatAnswers = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long chatAnswerWindow;
    private final java.util.concurrent.atomic.AtomicInteger signAnswers = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long signAnswerWindow;

    // ── 1.5: 랑데부 상태 알림·방장 이벤트 기록(hostlog) ─────────────────────────
    /** 랑데부가 끊긴 걸 이미 알렸는지 — 재시도마다 채팅을 도배하지 않게. */
    private volatile boolean signalingDown;
    /**
     * 방장 이벤트 기록은 <b>해시 사슬</b>로 보낸다: 매 항목에 순번(n)과 앞 항목 해시(p)를 싣고, 자기 해시(h)는
     * {@code sha256(p|n|e)}. 서버는 사슬이 끊기거나 순번이 빠지면 그 구간을 의심 구간으로 본다 — 방장이 자기에게
     * 불리한 기록만 골라 빼는 걸 막는다. 세션 값(s)은 방을 열 때마다 새로 만든다.
     */
    private final Object hostLogLock = new Object();
    private final String hostLogSession = Long.toHexString(RNG.nextLong() & 0xFFFF_FFFF_FFFFL);
    private long hostLogSeq = 0;
    private String hostLogPrev = "";
    /** 랑데부가 끊긴 동안 쌓아 둔 기록(최대 200개) — 다시 붙으면 순서대로 먼저 보낸다. */
    private final ArrayDeque<String> hostLogPending = new ArrayDeque<>();

    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quic-host-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param signalingUrl 시그널링 WS 루트, @param stunUrl {@code stun:HOST:PORT}.
     *        {@code P2PConfig} 를 직접 읽지 않는 건 이 클래스가 게임 밖(독립 하니스)에서도
     *        돌아야 하기 때문이다 — P2PConfig 는 정적 초기화에서 FabricLoader 를 건드린다.
     *        게임 안에서는 {@link QuicBridge} 가 P2PConfig 값을 넣어 준다.
     * @param hooks 게임 연동점(IP 밴 매핑·접속자 해시) — 같은 이유로 주입받는다({@link HostHooks}).
     */
    public QuicHost(String roomId, String target, String signalingUrl, String stunUrl,
                    String turnUrl, String turnUser, String turnPass, HostHooks hooks) {
        this.turnUrl = turnUrl;
        this.turnUser = turnUser;
        this.turnPass = turnPass;
        this.hooks = hooks != null ? hooks : HostHooks.NONE;
        this.signalingUrl = signalingUrl;
        this.stunUrl = stunUrl;
        this.roomId = roomId;
        int colon = target.lastIndexOf(':');
        this.targetHost = colon > 0 ? target.substring(0, colon) : target;
        this.targetPort = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 25565;
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;

        // kwik 의 ServerConnector.close() 는 모든 연결의 종료 핸드셰이크를 기다리는데, 그 기본
        // 상한이 30초다(DEFAULT_CLOSE_TIMEOUT_IN_SECONDS). 접속자 중 이미 끊긴 사람이나 중계로
        // 붙은 사람이 있으면 각각 타임아웃을 써서, 월드를 닫을 때 31초가 걸렸다(실측).
        // 그건 오래 사는 서버에는 맞는 기본값이지만 "플레이어가 월드를 닫았다" 에는 안 맞는다.
        // public static 이고 final 이 아니라 조정하도록 열어 둔 값이다.
        tech.kwik.core.server.impl.ServerConnectorImpl.DEFAULT_CLOSE_TIMEOUT_IN_SECONDS = 2;

        QuicCert.Identity id = QuicCert.generate();
        fingerprint = id.fingerprint();

        // 순서가 중요하다 — 후보 수집은 소켓을 직접 읽으므로 ServerConnector 를 띄우기 전에 끝내야
        // 한다. 띄운 뒤엔 kwik 이 수신 루프를 가져가고, 그 다음부터 STUN 은 IceSocket 이 가로챈다.
        // 소켓 전체를 막는 relayOnly 는 끈다(false). 방장의 중계 여부는 <b>접속자마다</b> 그 순간
        // 설정(relayNow)으로 정하고, 중계로 들어온 상대에겐 중계로만 답한다(IceSocket.viaRelayPeers).
        // 방을 연 시점의 값을 박으면 방을 연 채 설정을 바꿨을 때 직결이 통째로 막힌다.
        QuicIce agent = new QuicIce(stunUrl, false);
        ice = agent;
        agent.onFlood = hooks::onFloodDefense;
        // TURN 은 후보 수집보다 먼저 — allocation 이 relay 후보의 재료다. 실패하면 홀펀칭만 쓴다.
        agent.enableTurn(turnUrl, turnUser, turnPass);
        // 계정은 받았는데 allocation 이 안 잡혔으면 잠깐 쉬었다 두 번 더 해 본다(1.5~) — 중계 서버가 막 재시작했을 때
        // 첫 요청이 빠지면 그 방은 대칭 NAT 접속자를 끝까지 못 받았다.
        for (int i = 0; i < 2 && turnUser != null && turnUrl != null && !agent.hasTurn(); i++) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            agent.enableTurn(turnUrl, turnUser, turnPass);
        }
        candidates = agent.gather();

        server = ServerConnector.builder()
                .withSocket(agent.socket())
                .withKeyStore(id.keyStore(), QuicCert.ALIAS, QuicCert.PASSWORD)
                .withSupportedVersion(QuicConnection.QuicVersion.V1)
                // 1.5~: retryRequired — 주소를 위조한 Initial 홍수에 핸드셰이크 상태를 만들지 않는다(주소 확인 후에만).
                // 스트림 상한·버퍼는 접속자 한 명이 메모리를 무한정 잡지 못하게 명시한다.
                .withConfiguration(ServerConnectionConfig.builder()
                        .maxIdleTimeoutInSeconds(30)
                        .maxUnidirectionalStreamBufferSize(1_000_000)
                        .maxBidirectionalStreamBufferSize(1_000_000)
                        .maxConnectionBufferSize(10_000_000)
                        .maxOpenPeerInitiatedUnidirectionalStreams(10)
                        .maxOpenPeerInitiatedBidirectionalStreams(100)
                        .retryRequired(true)
                        .connectionIdLength(QuicIce.IceSocket.CID_LENGTH)
                        .build())
                .withLogger(KwikLog.quiet())   // 필수(기본값 없음). NullLogger 로 두면 kwik 경고를 다 버린다
                .build();
        server.registerApplicationProtocol(ALPN, new TunnelFactory());
        server.start();

        LOG.info("[quic-host] listening port={} target={}:{}", agent.localPort(), targetHost, targetPort);
        // 방을 연 채 공인 주소가 바뀌면 다음 접속자부터 새 주소를 알린다(1.5~).
        agent.startReflexRefresh(30_000, this::onReflexive);
        agent.startRelayWatch(60_000L, () -> this.candidates = agent.withCurrentRelay(this.candidates));

        try {
            openLobby();
        } catch (Exception e) {
            // 계정·IP 밴이면 서버가 웹소켓 핸드셰이크를 403 으로 거절한다 — 밴 화면을 띄울 수 있게 남긴다.
            if (String.valueOf(e.getMessage()).contains(" 403")) {
                kfc.udp.client.signaling.ConnectFailure.record("이 계정 또는 네트워크는 차단되어 방을 열 수 없습니다", true, true);
            }
            throw e;
        }
    }

    private void onReflexive(InetSocketAddress mapped) {
        QuicIce agent = ice;
        if (agent == null || !running.get()) return;
        List<QuicIce.Candidate> fresh = agent.regather(mapped);
        List<QuicIce.Candidate> cur = agent.withCurrentRelay(candidates);
        if (!new HashSet<>(fresh).equals(new HashSet<>(cur))) {
            candidates = fresh;
            LOG.info("[quic-host] 공인/로컬 주소가 바뀌어 후보를 새로 만들었다 — 이후 접속자부터 새 주소를 쓴다");
        }
    }

    /**
     * 방을 닫는다. <b>정리는 별도 스레드에서 한다 — 부르는 쪽(월드 종료 경로의 렌더 스레드)을
     * 막으면 게임이 그대로 멈춘다.</b>
     * <p>
     * 실제로 그랬다: 접속자 5명(여러 명 중계)인 방을 닫을 때 렌더 스레드가 31초 블로킹돼
     * "월드 저장 중에 팅긴다"로 보였다. 시그널링 WS 닫기와 kwik 의 연결별 종료 핸드셰이크가
     * 각각 네트워크 대기를 하기 때문이고, 그건 게임 종료를 기다릴 이유가 없는 일이다.
     * <p>
     * 어느 단계가 느린지 알 수 있게 단계별 소요를 남긴다 — 추측으로 고치지 않으려면 필요하다.
     */
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        WebSocketClient l = lobby;
        ServerConnector s = server;
        QuicIce agent = ice;

        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            if (l != null) {
                l.send("{\"bye\":true}"); // 서버가 끊김(재접속 대기)과 닫음을 구분해 방을 바로 내린다(1.5~)
                l.close();
            }
            long tLobby = System.currentTimeMillis();
            // 연결을 먼저 우리가 닫는다(기다리지 않는 close). 이러면 아래 server.close() 의
            // waitForAllConnectionsToClose 가 기다릴 대상이 없어 바로 지나간다.
            for (QuicConnection c : live) {
                try { c.close(); } catch (Exception ignored) {}
            }
            live.clear();
            if (s != null) s.close();
            long tServer = System.currentTimeMillis();
            if (agent != null) agent.close();
            long tIce = System.currentTimeMillis();
            worker.shutdownNow();
            watchdog.shutdownNow();
            LOG.debug("[quic-host] stopped — 랑데부 {}ms, QUIC 서버 {}ms, ICE {}ms, 합계 {}ms",
                    tLobby - t0, tServer - tLobby, tIce - tServer,
                    System.currentTimeMillis() - t0);
        }, "quic-host-close");
        t.setDaemon(true); // 게임이 먼저 끝나면 같이 사라져도 된다 — 소켓은 OS 가 정리한다
        t.start();
    }

    // ── 랑데부: 서버가 맺어 주는 접속자 ────────────────────────────────────────

    /**
     * 랑데부 서버에 방장으로 붙는다. 접속자가 오면 서버가 {@code join}(서버가 정한 sid)을 보내고,
     * 그 접속자가 보낸 후보는 sid 를 붙여 여기로만 온다. 우리가 sid 를 붙여 보낸 것은 그 접속자에게만
     * 간다(서버 rendezvous.go). 예전 VILLAS 로비·페어 세션은 아무나 들어와 가짜 지문·후보를 넣거나
     * 남의 후보(IP)를 엿볼 수 있었다.
     */
    /** 중계 서버가 방 열기를 거부했으면 true(기기·계정 밴 등) — 재접속 루프를 멈춘다(hwidban.go, antibot.go). */
    private volatile boolean hwidBanned;

    /** 재시도해도 소용없는 서버 거부 사유 → 로그 문구. */
    private static final java.util.Map<String, String> FATAL_HOST_ERRORS = java.util.Map.of(
            "hwid-banned", "이 기기는 중계 서버 운영자에 의해 차단되었습니다",
            "hwid-required", "기기 확인값을 보내지 못했습니다(수정된 클라이언트?)",
            "account-banned", "이 계정은 중계 서버 운영자에 의해 차단되었습니다",
            "auth-required", "이 서버는 정품 계정만 방을 열 수 있습니다",
            "too-many-rooms", "한 번에 열 수 있는 방 개수를 넘었습니다");

    private void openLobby() throws Exception {
        // 토큰은 붙을 때마다 다시 받는다 — 방이 토큰 수명(12시간)보다 오래 열려 있어도 재접속이 막히지 않게
        // (ensureToken 은 남은 시간이 넉넉하면 네트워크 없이 그대로 준다).
        String token = hooks.authToken();
        String info = hooks.clientInfo(); // v/mc/j/b — 서버가 낡은 버전·취약 자바·변조 빌드를 거른다(1.5~)
        WebSocketClient ws = new WebSocketClient(signalingUrl + "/rv/" + roomId + "/host?key=" + hostKey
                + (token != null ? "&token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8) : "")
                + (info.isEmpty() ? "" : "&" + info)
                + kfc.udp.client.signaling.HardwareId.query()) { // HWID 밴은 중계 서버가 판정(hwidban.go)
            @Override public void onConnected() {
                // 재접속 간격은 여기서 바로 줄이지 않는다 — 붙자마자 끊기길 반복하면 1초 간격으로 두드리게 된다
                // (onDisconnected 가 20초 이상 붙어 있었을 때만 줄인다, 1.5~).
                lobbyUpSince = System.currentTimeMillis();
                if (signalingDown) {
                    signalingDown = false;
                    hooks.onSignaling(true, null);
                }
                // 서버는 재접속한 방장의 상태를 모른다 — 방 정보·차단 목록·붙어 있는 접속자를 다시 올린다.
                pushRoomInfo();
                pushPeers();
                LOG.debug("[quic-host] 랑데부 연결됨");
            }
            @Override public void onMessage(String type, String json) {
                String err = VillasMsg.field(json, "error");
                if (err != null && FATAL_HOST_ERRORS.containsKey(err)) {
                    // 재접속해도 똑같이 거부되는 사유(기기·계정 밴, 방 개수 상한 등) — 재시도 루프를 멈춘다.
                    hwidBanned = true;
                    LOG.warn("[quic-host] 중계 서버가 방 열기를 거부했습니다 — {}", FATAL_HOST_ERRORS.get(err));
                    kfc.udp.client.signaling.ConnectFailure.record(FATAL_HOST_ERRORS.get(err),
                            kfc.udp.client.signaling.ConnectFailure.isBanError(err), true);
                    return;
                }
                handleRendezvous(json);
            }
            @Override public void onDisconnected() {
                if (lobbyUpSince != 0 && System.currentTimeMillis() - lobbyUpSince >= 20_000) {
                    backoffMs = INITIAL_BACKOFF_MS; // 한동안 잘 붙어 있었으니 다음 끊김은 다시 짧게 재시도
                }
                lobbyUpSince = 0;
                if (hwidBanned) return;
                scheduleLobbyReconnect();
            }
            // 서버가 주기적으로 ping 을 보낸다 — 30초 동안 아무 프레임도 없으면 죽은 연결로 보고 다시 붙는다(1.5~).
            @Override protected int readIdleTimeoutMs() { return 30_000; }
        };
        lobby = ws;
        ws.connect();
    }

    /**
     * 랑데부가 끊기면 다시 붙는다(같은 key 라 방 코드를 되찾는다). <b>이게 없으면 시그널링이 한 번 끊긴 뒤로 조인 감지가 영구히
     * 멈춰</b> 방장은 방이 열려 있다고 믿는데 아무도 못 들어오는 상태가 된다(예전 WebRTC 호스트도
     * 같은 이유로 백오프 재접속을 갖고 있었다).
     */
    /** 랑데부 재접속이 실패했을 때 한 번만 알린다 — 서버 거절 사유(reject/426/403/401)를 실어서. */
    private void notifySignalingDown(String message) {
        if (signalingDown) return;
        signalingDown = true;
        String code = Reject.code(message);
        String reason = code != null ? "reject:" + code + ":" + Reject.untilSec(message)
                : message.contains(" 426") ? "426"
                : message.contains(" 403") ? "403"
                : message.contains(" 401") ? "401" : "net";
        hooks.onSignaling(false, reason);
    }

    private void scheduleLobbyReconnect() {
        if (!running.get() || hwidBanned) return;
        long delay = backoffMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(backoffMs / 2 + 1); // 지터
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        worker.execute(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
            if (!running.get()) return;
            try {
                LOG.info("[quic-host] 랑데부 재접속 시도");
                openLobby();
            } catch (Exception e) {
                LOG.warn("[quic-host] 랑데부 재접속 실패: {}", e.getMessage());
                notifySignalingDown(String.valueOf(e.getMessage()));
                scheduleLobbyReconnect();
            }
        });
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 은 모든 JVM 에 있다
        }
    }

    /**
     * 방장 이벤트 하나(HostEventLog 가 만든 JSON)를 해시 사슬에 이어 랑데부로 보낸다({@link #hostLogLock} 주석).
     * 랑데부가 끊겨 있으면 쌓아 뒀다가 다시 붙을 때 먼저 보낸다.
     */
    public void sendHostLog(String evJson) {
        if (evJson == null || evJson.length() > 4096) return;
        synchronized (hostLogLock) {
            long n = ++hostLogSeq;
            String h = sha256Hex(hostLogPrev + "|" + n + "|" + evJson);
            JsonObject m = new JsonObject();
            m.addProperty("s", hostLogSession);
            m.addProperty("n", n);
            m.addProperty("p", hostLogPrev);
            m.addProperty("e", evJson);
            m.addProperty("h", h);
            hostLogPrev = h;
            JsonObject wrap = new JsonObject();
            wrap.add("hostlog", m);
            String msg = wrap.toString();
            WebSocketClient ws = lobby;
            if (ws == null) {
                if (hostLogPending.size() >= 200) hostLogPending.pollFirst();
                hostLogPending.addLast(msg);
            } else {
                flushHostLogLocked(ws);
                ws.send(msg);
            }
        }
    }

    private void flushHostLogLocked(WebSocketClient ws) {
        String q;
        while ((q = hostLogPending.pollFirst()) != null) ws.send(q);
    }

    /** 방 정보(공개 여부·제목·정원·옵션)를 서버에 올린다 — 방 설정이 바뀔 때마다·재접속할 때. 차단 목록도 같이. */
    public void pushRoomInfo() {
        String info = hooks.roomInfo();
        WebSocketClient ws = lobby;
        synchronized (hostLogLock) {
            if (ws != null) flushHostLogLocked(ws);
        }
        if (ws != null && !info.isEmpty()) ws.send("{\"room\":" + info + "}");
        pushAcl();
    }

    /** 방 차단/허용 목록을 서버에 올린다 — 서버가 차단된 사람을 랑데부 단계에서 먼저 거른다. */
    public void pushAcl() {
        String acl = hooks.roomAcl();
        WebSocketClient ws = lobby;
        if (ws != null && !acl.isEmpty()) ws.send("{\"acl\":" + acl + "}");
    }

    /** 접속자(sid) 하나의 터널 상태 — 붙었는지·중계인지. 서버의 방 인원·통계용. */
    private void sendPeer(String sid, boolean up, boolean relay) {
        WebSocketClient ws = lobby;
        if (ws != null) ws.send("{\"peer\":{\"sid\":\"" + sid + "\",\"up\":" + up + ",\"relay\":" + relay + "}}");
    }

    /** 재접속했을 때 지금 붙어 있는 접속자 전부를 한 번에 다시 알린다. */
    private void pushPeers() {
        WebSocketClient ws = lobby;
        if (ws == null || connSid.isEmpty()) return;
        StringBuilder sb = new StringBuilder("{\"peers\":[");
        for (Map.Entry<QuicConnection, String> e : connSid.entrySet()) {
            if (sb.charAt(sb.length() - 1) != '[') sb.append(',');
            sb.append("{\"sid\":\"").append(e.getValue()).append("\",\"relay\":")
                    .append(Boolean.TRUE.equals(connRelay.get(e.getKey()))).append('}');
        }
        ws.send(sb.append("]}").toString());
    }

    /**
     * 서버가 보낸 것: notice(서버 알림) / chatquery·signquery·bookquery(신고 처리용 조회, 1.5~) /
     * join(새 접속자) / leave(접속자가 떠남) / sid 가 붙은 후보.
     */
    private void handleRendezvous(String json) {
        if (json.startsWith("{\"notice\":")) {
            hooks.onServerNotice(VillasMsg.field(json, "notice"));
            return;
        }
        String cq = VillasMsg.object(json, "chatquery");
        if (cq != null) {
            answerChatQuery(cq);
            return;
        }
        String sq = VillasMsg.object(json, "signquery");
        if (sq != null) {
            answerSignQuery(sq, false);
            return;
        }
        String bq = VillasMsg.object(json, "bookquery");
        if (bq != null) {
            answerSignQuery(bq, true);
            return;
        }
        String join = VillasMsg.object(json, "join");
        if (join != null) {
            onJoin(join);
            return;
        }
        String leave = VillasMsg.object(json, "leave");
        if (leave != null) {
            String sid = VillasMsg.field(leave, "sid");
            if (sid != null) {
                joiners.remove(sid);
                java.util.concurrent.Future<?> f = negotiations.remove(sid);
                if (f != null) f.cancel(true); // punch 는 인터럽트되면 멈춘다
            }
            return;
        }
        String cand = VillasMsg.object(json, "candidate");
        String sid = VillasMsg.field(json, "sid");
        if (cand == null || sid == null) return;
        List<QuicIce.Candidate> theirs = joiners.get(sid);
        QuicIce agent = ice;
        if (theirs == null || agent == null) return;
        QuicIce.Candidate c = QuicIce.Candidate.parse(VillasMsg.field(cand, "spd"));
        // 접속자 한 명당 상한 — 넘치는 건 버린다(Candidate.parse 주석: 반사 공격 방지).
        if (c != null && !theirs.contains(c) && theirs.size() < QuicIce.Candidate.MAX_PER_PEER) {
            agent.addRemote(c);
            theirs.add(c);
        }
    }

    /**
     * 신고를 처리하는 서버가 이 방의 채팅 구간을 묻는다 — 신고자가 낸 채팅이 방장 기록과 맞는지 대조한다.
     * 구간은 10분 이하만, 분당 6회까지만 답한다(남의 방 채팅을 긁어 가는 통로가 되지 않게).
     */
    private void answerChatQuery(String cq) {
        String id = VillasMsg.field(cq, "id");
        if (id == null || !id.matches("[0-9a-z]{1,32}")) return;
        long from, to;
        try {
            from = Long.parseLong(VillasMsg.field(cq, "from"));
            to = Long.parseLong(VillasMsg.field(cq, "to"));
        } catch (RuntimeException e) {
            return;
        }
        if (to < from || to - from > 600_000) return;
        long now = System.currentTimeMillis();
        if (now - chatAnswerWindow > 60_000) {
            chatAnswerWindow = now;
            chatAnswers.set(0);
        }
        if (chatAnswers.incrementAndGet() > 6) return;
        worker.execute(() -> {
            String lines = hooks.chatWindow(from, to, roomId);
            WebSocketClient ws = lobby;
            if (ws != null) ws.send("{\"chatreply\":{\"id\":\"" + id + "\",\"lines\":" + lines + "}}");
        });
    }

    /** 표지판(books=false)·책(books=true) 조회 — 신고에 실린 내용이 방장 월드에 실제로 있는지 대조한다. 분당 6회. */
    private void answerSignQuery(String sq, boolean books) {
        String id;
        JsonArray items;
        try {
            JsonObject o = JsonParser.parseString(sq).getAsJsonObject();
            id = o.get("id").getAsString();
            items = o.getAsJsonArray("items");
        } catch (RuntimeException e) {
            return;
        }
        if (id == null || !id.matches("[0-9a-z]{1,32}") || items == null || items.isEmpty() || items.size() > 200) return;
        long now = System.currentTimeMillis();
        if (now - signAnswerWindow > 60_000) {
            signAnswerWindow = now;
            signAnswers.set(0);
        }
        if (signAnswers.incrementAndGet() > 6) return;
        worker.execute(() -> {
            JsonArray ans = books ? hooks.bookHashes(items) : hooks.signTexts(items);
            WebSocketClient ws = lobby;
            if (ws == null) return;
            JsonObject reply = new JsonObject();
            reply.addProperty("id", id);
            reply.add("items", ans);
            JsonObject msg = new JsonObject();
            msg.add(books ? "bookreply" : "signreply", reply);
            ws.send(msg.toString());
        });
    }

    private void onJoin(String join) {
        String sid = VillasMsg.field(join, "sid");
        // sid 는 서버가 만들지만 우리 쪽 맵 키로 쓰므로 형식은 확인한다(16 hex).
        if (sid == null || !sid.matches("[0-9a-f]{16}")) return;
        // 입장 전 확인(RoomMembersProbe) — 연결하지 않고 지금 접속자 해시만 알려주고 끝.
        if ("true".equals(VillasMsg.field(join, "probe"))) {
            send(sid, VillasMsg.description("members", hooks.onlinePlayerHashes(roomId)));
            return;
        }
        // relay=true: 접속자가 이미 「중계 통신 강제」를 켠 상태다. 그러면 방장도 곧장 중계로 간다 —
        // 접속자는 어차피 자기 allocation 밖으로만 보내므로, 방장이 host/srflx 후보를 내놓고
        // 직결부터 시도해 봐도 그 경로는 성립하지 않는다. 그 헛된 왕복이 접속 지연으로 남는다.
        // (예전 WebRTC 호스트도 같은 신호를 보고 clientRelayForced 로 판단했다.)
        final boolean clientRelayForced = "true".equals(VillasMsg.field(join, "relay"));
        // IP는 로그에 남기지 않는다 — 방장이 로그를 공유하면 접속자 IP가 박제된다. sid로 충분.
        LOG.debug("[quic-host] join detected sid={} clientRelayForced={}", sid, clientRelayForced);
        // 후보 목록은 <b>지금</b> 만든다 — 이 접속자의 후보가 바로 뒤이어 같은 연결로 들어온다.
        List<QuicIce.Candidate> theirs = new java.util.concurrent.CopyOnWriteArrayList<>();
        joiners.put(sid, theirs);
        negotiations.put(sid, worker.submit(() -> {
            // 동시 협상 상한 — 협상 하나가 최대 PUNCH_MS 동안 체크를 보내므로, 가짜 접속을 잔뜩 만들면
            // 그 수만큼 증폭된다(서버도 방·IP 단위로 막지만 한 겹 더). 성공한 협상은 1초 안에 끝나
            // 자리를 돌려준다. 자리가 안 나면(1.5~) 조용히 건너뛰지 않고 바쁘다고 알려 접속자가 곧장 다시 시도하게 한다.
            boolean got = false;
            try {
                got = negotiating.tryAcquire(NEGOTIATE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (got) {
                    negotiate(sid, clientRelayForced, theirs);
                    return;
                }
                LOG.warn("[quic-host] 동시 협상이 너무 많다 — sid={} 에게 바쁘다고 알린다", sid);
                send(sid, VillasMsg.description(MSG_BUSY, ""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // 이 접속자의 후보는 공유 목록에서 뺀다 — 안 빼면 접속자가 올 때마다 목록이 늘었다.
                QuicIce agent = ice;
                if (agent != null) agent.removeRemote(theirs);
                joiners.remove(sid);
                negotiations.remove(sid);
                if (got) negotiating.release();
            }
        }));
    }

    /** 이 접속자에게만 간다 — 서버가 sid 로 라우팅한다. */
    private void send(String sid, String msg) {
        WebSocketClient ws = lobby;
        if (ws != null) ws.send("{\"sid\":\"" + sid + "\"," + msg.substring(1));
    }

    /**
     * 지문·후보를 보내고 홀을 뚫는다. {@code theirs} 는 이 접속자의 후보만 모인 목록이다 — 소켓과
     * ICE 는 접속자 전원이 공유하므로, 뚫을 때는 반드시 자기 후보로만 판정해야 한다(QuicIce 의
     * responsive 주석). 후보는 트리클로 채워진다(handleRendezvous).
     */
    private void negotiate(String sid, boolean clientRelayForced, List<QuicIce.Candidate> theirs) {
        QuicIce agent = ice;
        if (agent == null) return;
        // 「중계 통신 강제」를 이 접속자를 맞이하는 지금 읽는다 — 방을 연 뒤 체크박스를 켜도
        // 다음 접속자부터 먹어야 한다(HostHooks.relayOnlyNow 주석).
        //
        // 접속자가 켜 놓은 것과 내가 켠 것은 <b>역할이 다르다</b>:
        //  - 내가 켰으면 내 트래픽이 내 allocation 을 거쳐야 한다(= relayNow, 내 IP 를 숨긴다).
        //  - 접속자가 켰으면 내 트래픽은 그대로 보내도 되고, 대신 <b>내 host 후보를 알리지 않는다</b>
        //    (사설 IP 는 상대의 allocation 에서 절대 닿을 수 없어 헛된 왕복만 된다).
        // 둘을 OR 로 묶어 방장까지 중계로 밀었더니 relay→relay 2홉이 되어 오히려 느려졌다
        // (실측 622ms → 7878ms). 그래서 분리한다.
        final boolean relayNow = hooks.relayOnlyNow();
        List<QuicIce.Candidate> mine = QuicIce.advertised(agent.withCurrentRelay(candidates), relayNow, clientRelayForced);
        if (mine.isEmpty()) {
            // 중계 강제인데 relay 후보가 없다(중계 계정을 못 받음 — 정품 인증 안 된 계정 등). 실주소를 대신
            // 내놓을 수는 없으니 접속자에게 바로 알린다. 예전엔 후보 0개로 접속자가 30초를 기다리다 실패했다.
            LOG.warn("[quic-host] 중계 통신 강제인데 중계 서버를 못 쓴다(중계 계정 없음) — sid={} 를 받을 수 없다", sid);
            send(sid, VillasMsg.description(MSG_NO_RELAY, ""));
            return;
        }
        try {
            // 지문 뒤에 표를 붙여 보낸다 — 이 접속자에게만 간다(서버가 sid 로 라우팅). 접속자는 지문으로 방장
            // 인증서를, 표로 자기가 협상한 사람임을 보인다.
            send(sid, VillasMsg.description(MSG_ANSWER, fingerprint + " " + newTicket(sid)));
            // 중계 강제면 relay 후보만 알려줘 접속자가 우리 실주소를 아예 모르게 한다.
            for (QuicIce.Candidate c : mine) {
                send(sid, VillasMsg.candidate(c.line(), "0"));
            }
            // ownLoop=false — ServerConnector 가 이미 수신 루프를 돌리고 있다(클래스 주석).
            QuicIce.Candidate picked = agent.punch(theirs, PUNCH_MS, false, relayNow);
            // 통신방식 기록은 여기서 하지 않는다 — QUIC 연결이 실제로 성립한 주소로 판정한다
            // (createConnection 주석). punch 가 실패해도 접속자는 자기 쪽 경로로 붙을 수 있다.
            LOG.debug("[quic-host] punch 종료 sid={} 결과={}", sid,
                    picked != null ? picked.type() : "없음(접속자 쪽 경로로 붙을 수 있다)");
        } catch (Exception e) {
            LOG.warn("[quic-host] 협상 실패 sid={}: {}", sid, e.getMessage());
        }
    }

    /**
     * 이 주소가 우리 TURN 서버인지 — 그러면 중계를 거쳐 온 것이다.
     * 주소로 판정하므로 punch 결과와 무관하게 항상 맞는다.
     */
    private boolean isRelayAddress(String ip) {
        if (turnUrl == null) return false;
        java.net.InetSocketAddress turn = TurnAllocation.parseUrl(turnUrl);
        if (turn == null || turn.getAddress() == null) return false;
        return turn.getAddress().getHostAddress().equals(ip);
    }

    // ── 들어온 QUIC 스트림 → LAN 포트 ────────────────────────────────────────

    /**
     * kwik 은 이 값들을 안 정해 주면 경고를 남기고 기본 설정으로 넘어간다
     * ({@code ServerConnectionImpl.configure}). 값 자체는 기본 설정과 같게 두되 명시해서
     * <b>의도한 값</b>임을 남긴다 — merge() 가 여기 최소치로 버퍼를 끌어올리므로, 잘못 적으면
     * 흐름 제어가 조용히 좁아진다.
     */
    private final class TunnelFactory implements ApplicationProtocolConnectionFactory {
        /** 마크는 접속자당 TCP 연결 하나 = 스트림 하나지만, 재접속 중 겹칠 수 있어 여유를 둔다(16). 접속자 한 명이
         *  스트림으로 스레드·마크 서버 연결을 무한정 만들지 못하게 상한을 둔다. */
        @Override public int maxConcurrentPeerInitiatedBidirectionalStreams() { return 16; }
        /** 단방향 스트림은 쓰지 않는다. */
        @Override public int maxConcurrentPeerInitiatedUnidirectionalStreams() { return 0; }
        /** 접속자→방장 방향 흐름 제어 하한. 기본 설정(1MB)과 같게 둔다. */
        @Override public int minBidirectionalStreamReceiverBufferSize() { return 1_000_000; }

        /** 받지 않을 연결에 돌려주는 빈 응용 계층 — 연결은 이미 닫았다. */
        private ApplicationProtocolConnection refused(QuicConnection conn) {
            conn.close();
            return new ApplicationProtocolConnection() {};
        }

        @Override public ApplicationProtocolConnection createConnection(String protocol, QuicConnection conn) {
            // 상대 주소는 여기서만 얻을 수 있다. QuicConnection 자체에는 접근자가 없고
            // ServerConnection 이 그걸 가지고 있다 — kwik 이 넘겨주는 객체가 그 구현이다.
            final java.net.InetSocketAddress peerAddr = (conn instanceof ServerConnection sc)
                    ? sc.getInitialRemoteAddress() : null;
            final String peerIp = peerAddr != null ? peerAddr.getAddress().getHostAddress() : null;
            // 중계(TURN)로 온 연결은 출처가 TURN 서버 주소다 — 그 IP 는 중계 접속자 전원이 같이 쓰므로
            // IP 밴·IP 단위 상한을 걸면 안 된다(capKey 가 IP:포트 로 묶는다).
            final boolean relaySource = peerIp != null && isRelayAddress(peerIp);
            final String capKey = peerAddr == null ? null : capKey(peerAddr, relaySource);

            // 1) 퍼붓다 끊긴 주소는 한동안 받지 않는다. 2) 한 주소의 동시 연결은 MAX_CONN_PER_IP 까지(1.5~).
            if (capKey != null) {
                long nowMs = System.currentTimeMillis();
                denied.values().removeIf(exp -> exp < nowMs);
                if (denied.containsKey(capKey)) {
                    LOG.debug("[quic-host] 거절: 퍼붓다 끊긴 주소");
                    return refused(conn);
                }
                if (connsPerIp.merge(capKey, 1, Integer::sum) > MAX_CONN_PER_IP) {
                    decrement(connsPerIp, capKey);
                    LOG.debug("[quic-host] 거절: 한 주소의 동시 연결 상한");
                    return refused(conn);
                }
            }
            // 이 연결이 connsPerIp 를 하나 잡고 있는지 — 닫힐 때 정확히 한 번만 돌려준다.
            final java.util.concurrent.atomic.AtomicBoolean counted = new java.util.concurrent.atomic.AtomicBoolean(capKey != null);

            // 3) 표를 아직 못 낸 연결 자리 — 전체 상한(unauth)과 주소당 상한(MAX_UNAUTH_PER_IP). 예전엔 첫 스트림이
            //    와야 자리를 잡아서, 스트림을 안 여는 연결은 자리도 시간 제한도 없이 남았다.
            boolean slot = unauth.tryAcquire();
            if (slot && capKey != null && unauthPerIp.merge(capKey, 1, Integer::sum) > MAX_UNAUTH_PER_IP) {
                decrement(unauthPerIp, capKey);
                unauth.release();
                slot = false;
            }
            if (!slot) {
                if (counted.compareAndSet(true, false)) decrement(connsPerIp, capKey);
                LOG.debug("[quic-host] 거절: 인증 전 연결 슬롯이 없다");
                return refused(conn);
            }
            // 이 연결이 확인받은 표(hex) — 첫 스트림이 정한다. 이후 스트림은 같은 표로 시작해야 한다.
            final java.util.concurrent.atomic.AtomicReference<String> authed = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicBoolean unauthHeld = new java.util.concurrent.atomic.AtomicBoolean(true);
            final Runnable releaseUnauth = () -> {
                if (unauthHeld.compareAndSet(true, false)) {
                    unauth.release();
                    if (capKey != null) decrement(unauthPerIp, capKey);
                }
            };
            // 연결이 생긴 순간부터 TICKET_WAIT_MS 안에 표를 못 내면 연결째 닫는다(스트림을 안 열어도).
            final java.util.concurrent.ScheduledFuture<?> killTimer;
            try {
                killTimer = watchdog.schedule(() -> {
                    if (authed.get() == null) conn.close();
                    releaseUnauth.run();
                }, TICKET_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 방을 닫는 중(watchdog 종료) — 이 연결은 받지 않는다
                releaseUnauth.run();
                if (counted.compareAndSet(true, false)) decrement(connsPerIp, capKey);
                return refused(conn);
            }
            final Runnable onAuthed = () -> {
                killTimer.cancel(false);
                releaseUnauth.run();
            };

            live.add(conn);
            conn.setConnectionListener(event -> {
                live.remove(conn);
                connRelay.remove(conn);
                throttles.remove(conn);
                killTimer.cancel(false);
                releaseUnauth.run();
                if (counted.compareAndSet(true, false)) decrement(connsPerIp, capKey);
                String sid = connSid.remove(conn);
                if (sid != null) sendPeer(sid, false, false);
            });

            if (peerIp == null) {
                LOG.warn("[quic-host] 상대 주소를 못 잡았다 — 이 세션엔 IP 밴이 적용되지 않는다");
            } else {
                // 직결/중계는 <b>QUIC 이 실제로 어디서 왔는지</b>로 판정한다.
                // 예전에는 방장 쪽 punch 결과를 썼는데, punch 는 "방장→접속자" 방향 검증이라
                // VPN 뒤 접속자처럼 방장이 그쪽 후보에 못 닿으면 null 이 됐다. 그러면 접속자는
                // 자기 쪽에서 경로를 찾아 멀쩡히 붙었는데도 참여 메시지에 통신방식이 안 붙었다.
                // 출처 주소는 연결이 성립한 이상 항상 있고, 그게 곧 사실이다.
                // 예전 {@code WebRtcStats} 규칙 그대로: <b>양쪽 중 하나라도 relay 면 중계</b>다.
                // 출처 주소만 보면 "방장이 자기 allocation 으로 내보내는" 방향을 놓쳐서, 중계
                // 강제인 방인데도 직결로 표시된다.
                QuicIce agent = ice;
                boolean relayed = relaySource || (agent != null && agent.sendsViaRelayTo(peerAddr));
                hooks.registerConnectionType(peerIp, relayed);
                connRelay.put(conn, relayed);
                dropIfGone(conn);

                // 2초 뒤 한 번 다시 본다. 우리 쪽 채널 바인딩은 punch 와 나란히 돌아서 연결이
                // 먼저 성립할 수 있고, 그러면 위에서 직결로 읽힌다. 예전 WebRTC 구현도 같은
                // 이유로 2초 뒤 재확인해서 값을 고쳤다.
                if (!relayed) {
                    worker.execute(() -> {
                        try { Thread.sleep(2_000); } catch (InterruptedException e) { return; }
                        QuicIce a2 = ice;
                        if (a2 != null && a2.sendsViaRelayTo(peerAddr)) {
                            LOG.debug("[quic-host] 통신방식 정정: 직결 → 중계");
                            hooks.registerConnectionType(peerIp, true);
                            connRelay.put(conn, true);
                            dropIfGone(conn);
                            String sid = connSid.get(conn);
                            if (sid != null) sendPeer(sid, true, true);
                        }
                    });
                }
            }
            // acceptPeerInitiatedStream 이 default 메서드라 함수형 인터페이스가 아니다 — 익명 클래스로.
            return new ApplicationProtocolConnection() {
                @Override public void acceptPeerInitiatedStream(QuicStream stream) {
                    worker.execute(() -> bridge(stream, peerIp, capKey, relaySource, conn, authed, onAuthed));
                }
            };
        }
    }

    /**
     * 연결 수 상한을 셀 때 쓰는 주소 열쇠. IPv6 는 /64 로 묶는다(한 사람이 주소를 무한히 바꿔 쓸 수 있다).
     * 중계로 온 연결은 출처가 TURN 서버라 IP:포트 로 묶는다 — 포트가 중계 접속자마다 다르다.
     */
    private static String capKey(InetSocketAddress a, boolean relaySource) {
        byte[] b = a.getAddress().getAddress();
        String ip = b.length == 16 ? java.util.HexFormat.of().formatHex(b, 0, 8) : a.getAddress().getHostAddress();
        return relaySource ? ip + ":" + a.getPort() : ip;
    }

    private static void decrement(Map<String, Integer> m, String key) {
        if (key == null) return;
        m.compute(key, (k, v) -> v != null && v > 1 ? v - 1 : null);
    }

    /** 이미 닫힌 연결이면 연결별 표를 치운다 — 닫힘 리스너보다 늦게 넣은 값이 남지 않게. */
    private void dropIfGone(QuicConnection conn) {
        if (live.contains(conn)) return;
        connSid.remove(conn);
        connRelay.remove(conn);
        throttles.remove(conn);
    }

    private String newTicket(String sid) {
        long now = System.currentTimeMillis();
        tickets.values().removeIf(exp -> exp < now); // 만료된 표를 치운다
        ticketSid.keySet().retainAll(tickets.keySet());
        byte[] b = new byte[TICKET_BYTES];
        RNG.nextBytes(b);
        String hex = java.util.HexFormat.of().formatHex(b);
        tickets.put(hex, now + TICKET_TTL_MS);
        ticketSid.put(hex, sid);
        return hex;
    }

    /** 표를 <b>한 번만</b> 쓰게 꺼낸다 — 있고 만료 전이면 그 표를 받은 sid, 아니면 null. */
    private String takeTicket(String hex) {
        Long exp = tickets.remove(hex);
        String sid = ticketSid.remove(hex);
        return exp != null && exp >= System.currentTimeMillis() ? sid : null;
    }

    /**
     * 스트림 맨 앞 접속 표를 읽어 확인한다({@link #tickets} 주석). 이 연결의 <b>첫</b> 스트림이면 표를 소진해 연결을
     * 확인 상태로 만들고, 이후 스트림은 같은 표로 시작하는지만 본다. 틀리면 연결째 닫는다. 시간 제한은 연결을 만들 때
     * 건 타이머(createConnection)가 맡는다 — 통과하면 {@code onAuthed} 로 그 타이머와 인증 전 자리를 푼다.
     *
     * @return 통과하면 true — 이때 스트림 입력은 표 16바이트를 소비한 상태다
     */
    private boolean admit(QuicStream stream, QuicConnection conn, java.util.concurrent.atomic.AtomicReference<String> authed,
                          Runnable onAuthed) {
        try {
            byte[] t = stream.getInputStream().readNBytes(TICKET_BYTES);
            boolean ok = false;
            String sid = null;
            if (t.length == TICKET_BYTES) {
                String hex = java.util.HexFormat.of().formatHex(t);
                synchronized (authed) { // 첫 스트림 둘이 동시에 와도 한 번만 소진한다
                    String cur = authed.get();
                    ok = cur != null ? cur.equals(hex) : (sid = takeTicket(hex)) != null && authed.compareAndSet(null, hex);
                }
            }
            if (ok && sid != null) { // 이 연결이 처음 확인됐다
                onAuthed.run();
                connSid.put(conn, sid);
                dropIfGone(conn);
                sendPeer(sid, true, Boolean.TRUE.equals(connRelay.get(conn)));
            }
            if (!ok) {
                // 불청객이 로그를 도배하지 못하게 10초에 한 번만. 상대 주소는 남기지 않는다.
                long now = System.currentTimeMillis();
                if (now - lastRogueLog > 10_000) {
                    lastRogueLog = now;
                    LOG.warn("[quic-host] 접속 표가 없거나 틀린 연결을 끊는다");
                }
                conn.close();
            }
            return ok;
        } catch (IOException e) {
            LOG.debug("[quic-host] 표를 읽다 연결이 닫혔다: {}", e.getMessage()); // 시간 안에 표가 안 와서 닫혔다
            return false;
        }
    }

    private void bridge(QuicStream stream, String peerIp, String capKey, boolean relaySource, QuicConnection conn,
                        java.util.concurrent.atomic.AtomicReference<String> authed, Runnable onAuthed) {
        LOG.debug("[quic-host] 스트림이 왔다 — 표를 확인한다");
        if (!admit(stream, conn, authed, onAuthed)) {
            LOG.debug("[quic-host] 표 확인 실패");
            return;
        }
        // 표를 낸 직결 상대는 아는 주소다 — 홍수 방어 제한에서 뺀다(중계로 온 건 TURN 서버라 이미 안다).
        if (peerIp != null && !relaySource && ice != null) ice.markKnown(peerIp);
        try {
            Socket tcp = new Socket();
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(targetHost, targetPort), DIAL_TIMEOUT_MS);
            // 터널을 지나면 모든 접속자가 127.0.0.1 로 보이므로, IP 밴이 먹으려면 이 매핑이 필요하다
            // (P2PBanManager 클래스 주석). 포트는 통합 서버 쪽에서 보이는 그 포트다.
            // 중계로 온 연결은 출처가 TURN 서버라 실제 IP 를 모른다 — 엉뚱한 IP 로 밴되지 않게 매핑하지 않는다.
            int tunnelPort = tcp.getLocalPort();
            if (peerIp != null && !relaySource) hooks.registerTunnelPort(tunnelPort, peerIp);
            hooks.registerTunnelType(tunnelPort, () -> connRelay.get(conn));
            // 접속자 → 방장 방향 속도 제한 — 연결 하나에 한 버킷(스트림이 여럿이어도 같이 쓴다).
            QuicPump.Throttle up = throttles.computeIfAbsent(conn, c -> new QuicPump.Throttle(UP_RATE, UP_BURST, UP_MAX_BLOCKED_MS, () -> {
                if (capKey != null) denied.put(capKey, System.currentTimeMillis() + DENY_MS);
                LOG.warn("[quic-host] 접속자가 터널로 트래픽을 퍼붓는다 — 연결을 끊고 {}분간 그 주소를 받지 않는다", DENY_MS / 60_000);
                conn.close();
            }));
            dropIfGone(conn);
            QuicPump.wire(tcp, stream.getInputStream(), stream.getOutputStream(), "host",
                    () -> hooks.unregisterTunnelPort(tunnelPort), up);
        } catch (IOException e) {
            LOG.warn("[quic-host] target dial 실패 {}:{} — {}", targetHost, targetPort, e.getMessage());
            try { stream.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }
}
