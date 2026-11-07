package kfc.udp.client.signaling;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 접속 상태 신호 — 가맹점 약관에 동의한 사람만, 게임을 켜 두는 동안 1분마다 가맹점 서버에 "켜져 있음"을
 * 알린다(서버 presence.go). 운영자 화면의 플레이어 목록·방 인원에 쓰인다. 약관 3조(접속 상태)·5조에 적혀 있다(그래서 약관 버전을 올렸다 — 다시 동의받는다).
 * <p>
 * 보내는 것: 게시 토큰(있으면)·UUID·닉네임, 상태(메뉴/방장/접속자)와 방 코드, 방장이면 지금 방에 있는
 * 사람들의 닉네임·UUID, 기기 확인값(HardwareId — 다른 요청과 같은 것), 모드·마크 버전.
 * 본점(공식 서버)이거나 동의 전이면 아무것도 보내지 않는다.
 */
public final class Presence {

    private static final long INTERVAL_MS = 60_000;
    private static long nextMs;

    private Presence() {}

    /** 메인 스레드에서 매 틱 불러도 된다(1분마다만 보낸다). */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now < nextMs) return;
        nextMs = now + INTERVAL_MS;
        if (!P2PConfig.franchiseConsented()) return;

        String state = "idle", room = null, members = null;
        if (kfc.udp.client.KfcudpClient.isRoomActive()) {
            state = "host";
            room = kfc.udp.client.KfcudpClient.activeRoomCode();
            members = hostMembers(); // 서버 플레이어 목록은 메인 스레드에서 읽는다
        } else if (inGuestSession()) {
            state = "guest";
            room = kfc.udp.client.quic.QuicBridge.activeGuestRoom();
        }
        // URL 만들기·전송은 백그라운드에서 — 첫 전송 때 HardwareId가 기기 값을 모으면서(윈도우: cmd /c vol,
        // 네트워크 어댑터 나열) 최대 수 초 걸린다. 예전엔 이게 메인 스레드라 게임을 켜고 1분쯤 뒤 한 번
        // 화면이 멈췄다(F3.4). 방 인원 목록처럼 게임 상태를 읽는 건 위에서 메인 스레드로 끝냈다.
        final String s = state, r = room, m = members;
        SENDER.execute(() -> send(s, r, m));
    }

    /** 1분에 한 번 도는 전송용 스레드 하나 — 요청마다 스레드를 새로 만들지 않는다. */
    private static final java.util.concurrent.ExecutorService SENDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "instant-p2p-presence");
                t.setDaemon(true);
                return t;
            });

    private static void send(String state, String room, String members) {
        try {
            sendNow(state, room, members);
        } catch (Throwable t) {
            // 백그라운드라 예외가 조용히 사라지면 다음 신호도 영영 안 나갈 수 있다 — 여기서 삼킨다.
        }
    }

    private static void sendNow(String state, String room, String members) {
        StringBuilder url = new StringBuilder(P2PConfig.signalingHttpUrl()).append("/api/v1/presence?state=").append(state);
        if (room != null) url.append("&room=").append(enc(room));
        if (members != null && !members.isEmpty()) url.append("&members=").append(enc(members));
        String token = MojangAuth.tokenOrNull();
        if (token != null) url.append("&token=").append(enc(token));
        String uuid = MojangAuth.sessionUuidOrNull();
        if (uuid != null) url.append("&uuid=").append(enc(uuid));
        String name = MojangAuth.sessionNameOrNull();
        if (name != null) url.append("&name=").append(enc(name));
        url.append("&v=").append(enc(P2PConfig.MOD_VERSION + "+" + P2PConfig.FRANCHISE_BUILD));
        url.append("&mc=").append(enc(mcVersion()));
        url.append(HardwareId.query());
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(8)).GET().build();
        } catch (Exception e) {
            return;
        }
        Http.CLIENT.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String mcVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    /** 가맹점 방에 접속자로 들어가 있는지 — QUIC 클라이언트는 나간 뒤에도 남아 있을 수 있어서 월드도 본다. */
    public static boolean inGuestSession() {
        return kfc.udp.client.quic.QuicBridge.activeGuestRoom() != null && inRemoteWorld();
    }

    //? if >=26.1 {
    /*private static boolean inRemoteWorld() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level != null && !mc.isLocalServer();
    }
    *///?} else {
    private static boolean inRemoteWorld() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        return mc.world != null && !mc.isIntegratedServerRunning();
    }
    //?}

    /** 방장 통합 서버에 지금 있는 사람들 — "닉네임:uuid,닉네임:uuid". */
    //? if >=26.1 {
    /*private static String hostMembers() {
        net.minecraft.server.MinecraftServer server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (n++ >= 100) break;
            if (b.length() > 0) b.append(',');
            b.append(P2PBanManager.profileName(p.getGameProfile())).append(':').append(p.getUUID());
        }
        return b.toString();
    }
    *///?} else {
    private static String hostMembers() {
        net.minecraft.server.MinecraftServer server = net.minecraft.client.MinecraftClient.getInstance().getServer();
        if (server == null) return null;
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (net.minecraft.server.network.ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (n++ >= 100) break;
            if (b.length() > 0) b.append(',');
            b.append(P2PBanManager.profileName(p.getGameProfile())).append(':').append(p.getUuid());
        }
        return b.toString();
    }
    //?}
}
