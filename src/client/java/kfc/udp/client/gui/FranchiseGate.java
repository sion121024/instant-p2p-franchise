package kfc.udp.client.gui;

import kfc.udp.client.signaling.P2PConfig;
//? if >=26.1 {
/*import net.minecraft.client.gui.screens.Screen;
*///?} else {
import net.minecraft.client.gui.screen.Screen;
//?}

/**
 * 가맹점(사설 서버) 이용약관 관문 — 가맹점으로 바꾸거나 가맹점 상태로 방 화면을 열 때, 현재 버전의 약관에
 * 동의하지 않았으면 약관(PanelScreen)을 먼저 보여 준다. 동의하면 원래 하려던 일을 이어서 하고, 취소하면
 * 본점에 그대로 남는다. 동의 전에는 기기 식별값을 보내지 않는다(HardwareId.query).
 */
public final class FranchiseGate {

    private FranchiseGate() {}

    private static final int ACCENT = 0xFF55FFFF, BG = 0xFF001A1C;

    /** 약관에 동의돼 있으면 바로 afterAccept, 아니면 약관을 띄우고 동의 시 afterAccept·취소 시 afterCancel. */
    public static void requireTerms(Screen parent, Runnable afterAccept, Runnable afterCancel) {
        if (P2PConfig.isTermsAccepted()) {
            afterAccept.run();
            return;
        }
        show(new PanelScreen(parent,
                PanelScreen.tr("instant-p2p.terms.heading"),
                PanelScreen.tr("instant-p2p.terms.body"),
                PanelScreen.tr("instant-p2p.terms.agree"),
                PanelScreen.tr("instant-p2p.terms.accept"),
                PanelScreen.tr("gui.cancel"),
                ACCENT, BG,
                () -> {
                    P2PConfig.setTermsAccepted();
                    afterAccept.run();
                },
                afterCancel));
    }

    /**
     * 방 목록·방 만들기 화면을 열기 직전 — 이미 가맹점으로 설정돼 있는데(예전 수정판에서 넘어온 설정 등)
     * 약관에 동의하지 않았으면 먼저 묻는다. 취소하면 본점으로 돌리고 그대로 연다.
     */
    public static void beforeOpen(Screen parent, Runnable open) {
        if (P2PConfig.getServerProfile() != P2PConfig.ServerProfile.FRANCHISE || P2PConfig.isTermsAccepted()) {
            kfc.udp.client.signaling.BanStatus.refreshAsync();
            if (showIfBanned(parent)) return;
            open.run();
            return;
        }
        requireTerms(parent, open, () -> {
            P2PConfig.setServerProfile(P2PConfig.ServerProfile.OFFICIAL);
            open.run();
        });
    }

    /**
     * 가맹점에서 막혀 있으면(BanStatus — 마지막으로 확인한 값) 밴 화면을 띄우고 true. 매 틱 불러도 된다 —
     * 확인은 15초마다만 서버에 간다. 본점이거나 약관 동의 전이면 아무것도 안 한다.
     */
    public static boolean showIfBanned(Screen parent) {
        if (!P2PConfig.franchiseConsented()) return false;
        kfc.udp.client.signaling.BanStatus.poll();
        kfc.udp.client.signaling.BanStatus.Info info = kfc.udp.client.signaling.BanStatus.current();
        if (!info.banned()) return false;
        show(new BannedScreen(parent, info));
        return true;
    }

    /**
     * 지금 매장(가맹점) 서버에 이 코드의 방이 열려 있는지 묻고, 답을 메인 스레드에서 넘긴다. 서버가 이 기능이
     * 없거나 응답이 없으면 false(=예전처럼 본점 코드로 본다).
     */
    public static void roomExistsAsync(String code, java.util.function.Consumer<Boolean> then) {
        java.net.http.HttpRequest req;
        try {
            req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(P2PConfig.signalingHttpUrl()
                            + "/api/v1/room-exists?code=" + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
        } catch (Exception e) {
            runOnMain(() -> then.accept(false));
            return;
        }
        kfc.udp.client.signaling.Http.CLIENT.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                .handle((res, err) -> {
                    boolean exists = err == null && res.statusCode() == 200
                            && "true".equals(kfc.udp.client.signaling.VillasMsg.field(res.body(), "exists"));
                    runOnMain(() -> then.accept(exists));
                    return null;
                });
    }

    static void runOnMain(Runnable r) {
        //? if >=26.1 {
        /*net.minecraft.client.Minecraft.getInstance().execute(r);
        *///?} else {
        net.minecraft.client.MinecraftClient.getInstance().execute(r);
        //?}
    }

    /** 서버 공지 알림창 — 같은 공지는 한 번만. */
    public static void showNotice(Screen parent, String text) {
        show(new PanelScreen(parent,
                PanelScreen.tr("instant-p2p.notice.heading"),
                text,
                null,
                PanelScreen.tr("instant-p2p.notice.ok"),
                null,
                0xFFFFFF55, 0xFF1A1A00,
                null, null));
    }

    static void show(Screen s) {
        //? if >=26.1 {
        /*net.minecraft.client.Minecraft.getInstance().setScreenAndShow(s);
        *///?} else {
        net.minecraft.client.MinecraftClient.getInstance().setScreen(s);
        //?}
    }
}
