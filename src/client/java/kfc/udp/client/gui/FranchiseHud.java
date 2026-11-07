package kfc.udp.client.gui;

import kfc.udp.client.signaling.BanStatus;
import kfc.udp.client.signaling.ConnectFailure;
import kfc.udp.client.signaling.P2PConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 가맹점 쪽 화면 알림 두 가지를 틱마다 챙긴다.
 * <ul>
 *   <li><b>플레이 중 공지</b> — 가맹점 방에 들어가 있거나(접속자) 방을 열어 둔 동안(방장), 1분마다 공지를 확인해서
 *       새 공지면 오른쪽 위에 작은 알림(NoticeToast)으로 보여 준다. 방 목록의 공지창과 같은 색·모양이다.
 *       한 번 보여 준 공지는 기억해서(P2PConfig.lastSeenNotice) 방 목록에서 또 띄우지 않는다.</li>
 *   <li><b>접속 실패 이유</b> — 마인크래프트는 로컬 연결이 끊긴 것만 보고 "연결 끊김"만 띄운다. 서버가 거절한
 *       이유(ConnectFailure)가 남아 있으면 그 화면 위에 이유를 띄우고, 밴이면 밴 화면(BannedScreen)을 띄운다.</li>
 * </ul>
 */
public final class FranchiseHud {

    // 예전 60초 → 15초(공지가 너무 늦게 떴다) → F3.4에서 30초. 플레이 중인 사람 전원이 보내는 요청이라
    // 사람이 몰리면 서버(1 OCPU)에 그대로 쌓인다 — 30초면 공지가 늦어도 반 박자다.
    private static final long NOTICE_INTERVAL_MS = 30_000;
    private static final long BAN_WAIT_MS = 4_000;

    private static long nextNoticeCheckMs;
    private static volatile String fetchedNotice; // 백그라운드 확인 결과(아직 안 보여 준 것)
    private static ConnectFailure.Pending waiting; // 밴 상태 확인을 기다리는 실패
    private static long waitingSinceMs;

    private FranchiseHud() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static boolean inFranchiseSession() {
        if (!P2PConfig.franchiseConsented()) return false;
        return kfc.udp.client.KfcudpClient.isRoomActive() || kfc.udp.client.signaling.Presence.inGuestSession();
    }

    private static void tick() {
        kfc.udp.client.signaling.Presence.tick(); // 1분마다 접속 상태(가맹점 동의한 사람만)
        Object screen = currentScreen();

        // ── 접속 실패 이유 ──
        if (waiting == null && ConnectFailure.hasPending()) {
            boolean host = peekHost();
            // 접속자는 "연결 끊김" 화면이 뜬 뒤에, 방장은 바로 띄운다.
            if (host || isDisconnectedScreen(screen)) {
                waiting = ConnectFailure.take();
                waitingSinceMs = System.currentTimeMillis();
            }
        }
        if (waiting != null) {
            long waited = System.currentTimeMillis() - waitingSinceMs;
            if (waiting.banned() && !BanStatus.isBanned() && waited < BAN_WAIT_MS) return; // 밴 상태 답을 잠깐 기다린다
            ConnectFailure.Pending p = waiting;
            waiting = null;
            if (p.banned() && BanStatus.isBanned()) {
                show(new BannedScreen(asScreen(screen), BanStatus.current()));
            } else {
                show(new PanelScreen(asScreen(screen),
                        PanelScreen.tr(p.banned() ? "instant-p2p.ban.heading_simple" : "instant-p2p.fail.heading"),
                        p.reason(), null, PanelScreen.tr("instant-p2p.notice.ok"), null,
                        p.banned() ? 0xFFFF5555 : 0xFFFFAA00, p.banned() ? 0xFF1A0000 : 0xFF1A1000,
                        null, null));
            }
            return;
        }

        // ── 플레이 중 공지 ──
        String n = fetchedNotice;
        if (n != null) {
            fetchedNotice = null;
            if (!n.isBlank() && !n.equals(P2PConfig.lastSeenNotice(P2PConfig.ServerProfile.FRANCHISE)) && inFranchiseSession()) {
                P2PConfig.setLastSeenNotice(P2PConfig.ServerProfile.FRANCHISE, n);
                showToast(n);
            }
        }
        long now = System.currentTimeMillis();
        // 창을 내려 둔(포커스 없는) 동안은 묻지 않는다 — 어차피 토스트를 볼 사람이 없다.
        if (now >= nextNoticeCheckMs && inFranchiseSession() && windowActive()) {
            nextNoticeCheckMs = now + NOTICE_INTERVAL_MS;
            fetchNoticeAsync();
        }
    }

    private static boolean peekHost() {
        ConnectFailure.Pending p = ConnectFailure.peek();
        return p != null && p.host();
    }

    /** 가맹점 서버의 공지(/api/v1/version 의 notice)를 백그라운드로 받아 온다. 실패하면 조용히 넘어간다. */
    private static void fetchNoticeAsync() {
        java.net.http.HttpRequest req = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(P2PConfig.signalingHttpUrl() + "/api/v1/version"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        kfc.udp.client.signaling.Http.CLIENT
                .sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() != 200) return;
                    String n = kfc.udp.client.signaling.VillasMsg.field(res.body(), "notice");
                    fetchedNotice = n == null ? "" : n;
                })
                .exceptionally(e -> null);
    }

    // ── 버전별 ──────────────────────────────────────────────────────────────

    //? if >=26.1 {
    /*private static boolean windowActive() {
        return net.minecraft.client.Minecraft.getInstance().isWindowActive();
    }
    *///?} else {
    private static boolean windowActive() {
        return net.minecraft.client.MinecraftClient.getInstance().isWindowFocused();
    }
    //?}

    //? if >=26.2 {
    /*private static Object currentScreen() {
        return net.minecraft.client.Minecraft.getInstance().gui.screen();
    }

    private static boolean isDisconnectedScreen(Object s) {
        return s instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
    }

    private static net.minecraft.client.gui.screens.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screens.Screen) s;
    }

    private static void show(net.minecraft.client.gui.screens.Screen s) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(s);
    }

    private static void showToast(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.gui.toastManager().addToast(new NoticeToast(text));
    }

    // 오른쪽 위 작은 공지 — 방 목록 공지창과 같은 노란 테두리·어두운 바탕.
    static final class NoticeToast implements net.minecraft.client.gui.components.toasts.Toast {
        // 공지가 길어졌다(제목 + 여러 줄 본문 + 서명) — 넓히고 줄 수를 늘린다. 다 못 보이면 마지막 줄에 "…"
        // (전체는 방 목록의 공지창에서 볼 수 있다). 보이는 시간은 줄 수에 따라 8~20초.
        private static final int W = 260, PAD = 6, LINE_H = 10, MAX_LINES = 10;
        private static final long SHOW_MS = 8_000, PER_LINE_MS = 1_200, MAX_SHOW_MS = 20_000;
        private final String text;
        private java.util.List<String> lines;
        private Visibility visibility = Visibility.SHOW;

        NoticeToast(String text) {
            this.text = text;
            this.lines(net.minecraft.client.Minecraft.getInstance().font); // 높이(차지할 칸 수)를 미리 정한다
        }

        private java.util.List<String> lines(net.minecraft.client.gui.Font font) {
            if (this.lines == null) {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (String para : this.text.split("\n")) {
                    StringBuilder cur = new StringBuilder();
                    for (int i = 0; i < para.length(); i++) {
                        cur.append(para.charAt(i));
                        if (font.width(cur.toString()) > W - PAD * 2 && cur.length() > 1) {
                            out.add(cur.substring(0, cur.length() - 1));
                            cur.setLength(0);
                            cur.append(para.charAt(i));
                        }
                    }
                    out.add(cur.toString());
                }
                if (out.size() > MAX_LINES) {
                    out = new java.util.ArrayList<>(out.subList(0, MAX_LINES));
                    out.set(MAX_LINES - 1, out.get(MAX_LINES - 1) + "…");
                }
                this.lines = out;
            }
            return this.lines;
        }

        @Override public Visibility getWantedVisibility() { return this.visibility; }

        @Override public void update(net.minecraft.client.gui.components.toasts.ToastManager manager, long timeMs) {
            int n = this.lines == null ? 1 : this.lines.size();
            long show = Math.min(MAX_SHOW_MS, SHOW_MS + Math.max(0, n - 2) * PER_LINE_MS);
            this.visibility = timeMs >= show * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override public int width() { return W; }

        @Override public int height() {
            int n = this.lines == null ? 2 : this.lines.size();
            return PAD * 2 + LINE_H + 2 + n * LINE_H;
        }

        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font, long timeMs) {
            java.util.List<String> ls = this.lines(font);
            int h = this.height();
            ctx.fill(0, 0, W, h, 0xFFFFFF55);
            ctx.fill(1, 1, W - 1, h - 1, 0xF01A1A00);
            ctx.text(font, "§l" + PanelScreen.tr("instant-p2p.notice.heading"), PAD, PAD, 0xFFFFFF55);
            for (int i = 0; i < ls.size(); i++) {
                ctx.text(font, ls.get(i), PAD, PAD + LINE_H + 2 + i * LINE_H, 0xFFE0E0E0);
            }
        }
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static Object currentScreen() {
        return net.minecraft.client.Minecraft.getInstance().screen;
    }

    private static boolean isDisconnectedScreen(Object s) {
        return s instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
    }

    private static net.minecraft.client.gui.screens.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screens.Screen) s;
    }

    private static void show(net.minecraft.client.gui.screens.Screen s) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(s);
    }

    private static void showToast(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[공지] §f" + text), false);
    }
    *///?}
    //? if <26.1 {
    private static Object currentScreen() {
        return net.minecraft.client.MinecraftClient.getInstance().currentScreen;
    }

    private static boolean isDisconnectedScreen(Object s) {
        return s instanceof net.minecraft.client.gui.screen.DisconnectedScreen;
    }

    private static net.minecraft.client.gui.screen.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screen.Screen) s;
    }

    private static void show(net.minecraft.client.gui.screen.Screen s) {
        net.minecraft.client.MinecraftClient.getInstance().setScreen(s);
    }

    private static void showToast(String text) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§e[공지] §f" + text), false);
    }
    //?}
}
