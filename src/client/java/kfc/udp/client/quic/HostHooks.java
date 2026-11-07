package kfc.udp.client.quic;

/**
 * {@link QuicHost}가 게임에 기대는 것들 — 전부 여기로 모아 주입받는다.
 * <p>
 * <b>왜 인터페이스인가</b> — {@code QuicHost}가 {@code P2PBanManager}를 직접 부르면 마인크래프트
 * 클래스({@code Text} 등)를 딸려 끌고 와서, 게임 밖에서는 {@code NoClassDefFoundError}로 죽는다.
 * 그러면 {@link QuicProbe}(혼자서 NAT 통과를 검증하는 하니스)가 호스트 쪽을 시험할 수 없다.
 * 시그널링·STUN 주소를 생성자로 받는 것과 같은 이유다.
 * <p>
 * 게임 안에서는 {@link QuicBridge}가 {@code P2PBanManager}에 연결해 주고, 하니스는 {@link #NONE}을 쓴다.
 */
public interface HostHooks {

    /**
     * 터널 로컬 포트 → 접속자의 실제 IP를 기록한다. 터널을 지나면 모든 접속자가 127.0.0.1로
     * 보이므로 이게 없으면 IP 밴이 먹지 않는다.
     */
    void registerTunnelPort(int localPort, String realIp);

    /** 스트림이 닫히면 해제한다 — OS가 로컬 포트를 재사용하므로 남겨두면 엉뚱한 IP를 가리킨다. */
    void unregisterTunnelPort(int localPort);

    /**
     * 터널 로컬 포트 → 그 연결이 지금 중계인지 묻는 함수(1.5~). IP 로 묶던 직결/중계 표시는 같은 IP 에서
     * 둘이 들어오면 섞였다 — 연결 단위로 물어 본다. 값이 바뀔 수 있어(2초 뒤 정정) 함수로 넘긴다.
     */
    default void registerTunnelType(int localPort, java.util.function.Supplier<Boolean> relayed) {}

    /**
     * 「중계 통신 강제」가 <b>지금</b> 켜져 있는지. 값이 아니라 함수인 이유는, 방장이 방을 연 뒤에
     * 체크박스를 켰을 때도 <b>다음 접속자부터</b> 적용돼야 하기 때문이다 — 예전 WebRTC 호스트는
     * 접속자마다 PeerConnection 을 새로 만들며 ICE 설정을 그때 읽어서 자연히 그렇게 동작했다.
     */
    boolean relayOnlyNow();

    /**
     * 접속자의 실제 IP → 직결/중계 여부. 방장 화면의 "참여했습니다" 메시지 접미사에 쓰인다
     * ({@code PlayerJoinMessageMixin}) — 이게 없으면 방장은 접속자의 연결 방식을 볼 수 없다.
     */
    void registerConnectionType(String realIp, boolean usesRelay);

    /**
     * 입장 전 확인(RoomMembersProbe)에 돌려줄 접속자 해시 — 방 코드로 솔팅돼 있어 받는 쪽은
     * 자기 차단 목록과 대조만 할 수 있다.
     */
    String onlinePlayerHashes(String roomId);

    /**
     * 방장 인증 토큰(정품 확인 또는 서버의 예외 목록) — 랑데부 서버가 이게 없으면 방장 연결을 받지 않는다.
     * 없으면 null. <b>블로킹</b>일 수 있다(처음 한 번은 네트워크를 탄다).
     */
    default String authToken() { return null; }

    // ── 1.5: 서버와 주고받는 방 정보·안전 기능 ─────────────────────────────────

    /** 랑데부 URL 에 붙일 클라이언트 정보(v/mc/j/b 쿼리) — 서버가 낡은 버전·취약 자바를 거른다. 없으면 "". */
    default String clientInfo() { return ""; }

    /** 방 정보 JSON(공개 여부·제목·정원·옵션) — 랑데부에 {@code {"room":...}} 로 올린다. 방이 없으면 "". */
    default String roomInfo() { return ""; }

    /** 방 차단/허용 목록 JSON — 서버가 차단된 사람을 랑데부 단계에서 먼저 거른다. 없으면 "". */
    default String roomAcl() { return ""; }

    /** 랑데부가 끊기거나(up=false, 사유 코드) 다시 붙었을 때(up=true). */
    default void onSignaling(boolean up, String reason) {}

    /** 모르는 주소에서 패킷이 몰려 방어 모드를 켜고/끈 순간. */
    default void onFloodDefense(boolean on) {}

    /** 서버 알림(notice) — unsigned/title/quarantine/nolog. */
    default void onServerNotice(String notice) {}

    /** 신고 처리용 채팅 조회 응답 — 이 방의 [from, to] 구간 채팅(JSON 배열). */
    default String chatWindow(long fromMs, long toMs, String room) { return "[]"; }

    /** 신고 처리용 표지판 조회 응답 — 요청한 좌표의 표지판 글. */
    default com.google.gson.JsonArray signTexts(com.google.gson.JsonArray items) { return new com.google.gson.JsonArray(); }

    /** 신고 처리용 책 조회 응답 — 요청한 책의 지금 해시. */
    default com.google.gson.JsonArray bookHashes(com.google.gson.JsonArray items) { return new com.google.gson.JsonArray(); }

    /** 하니스·테스트용 no-op. */
    HostHooks NONE = new HostHooks() {
        @Override public boolean relayOnlyNow() { return false; }
        @Override public void registerTunnelPort(int localPort, String realIp) {}
        @Override public void unregisterTunnelPort(int localPort) {}
        @Override public void registerConnectionType(String realIp, boolean usesRelay) {}
        @Override public String onlinePlayerHashes(String roomId) { return ""; }
    };
}
