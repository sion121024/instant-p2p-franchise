package kfc.udp.client.gui;

import kfc.udp.client.JreGuard;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * 취약한 자바 경고창(가맹점 F3.5~, JreGuard 클래스 주석 참고).
 * <ul>
 *   <li>메인 화면이 처음 열릴 때 한 번 띄운다(실행마다 한 번).</li>
 *   <li>취약(VULNERABLE)이면 빨간 창 — 「위험을 알고 계속」 체크(기본 꺼짐)를 켜야 「계속」이 눌리고, 누르면 이번
 *       실행 동안만 P2P 거절을 푼다. 「확인」·ESC 는 거절을 그대로 둔다.</li>
 *   <li>경고(WARN, 26 줄)면 노란 창에 확인 버튼 하나 — 막지 않는다.</li>
 * </ul>
 * 방 열기·접속을 누르는 순간 거절 중이면 {@link #open} 으로 같은 창을 다시 띄운다.
 */
public final class JreWarning {

    private static boolean shownThisRun;

    private JreWarning() {}

    public static void register() {
        if (JreGuard.level() == JreGuard.Level.OK) return;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (shownThisRun || !isTitle(screen)) return;
            shownThisRun = true;
            // 화면 init 도중에 다른 화면으로 바꾸지 않는다 — 다음 틱에 한 번만 띄운다.
            boolean[] done = {false};
            ScreenEvents.afterTick(screen).register(sc -> {
                if (done[0]) return;
                done[0] = true;
                open(asScreen(sc));
            });
        });
    }

    //? if >=26.1 {
    /*private static boolean isTitle(Object s) {
        return s instanceof net.minecraft.client.gui.screens.TitleScreen;
    }

    private static net.minecraft.client.gui.screens.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screens.Screen) s;
    }

    public static void open(net.minecraft.client.gui.screens.Screen parent) {
    *///?} else {
    private static boolean isTitle(Object s) {
        return s instanceof net.minecraft.client.gui.screen.TitleScreen;
    }

    private static net.minecraft.client.gui.screen.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screen.Screen) s;
    }

    public static void open(net.minecraft.client.gui.screen.Screen parent) {
    //?}
        JreGuard.Level level = JreGuard.level();
        if (level == JreGuard.Level.OK) return;
        String now = JreGuard.currentText();
        if (level == JreGuard.Level.WARN) {
            FranchiseGate.show(new PanelScreen(parent,
                    PanelScreen.tr("instant-p2p.jre.warn_heading"),
                    PanelScreen.tr("instant-p2p.jre.warn_body", now, JreGuard.DOWNLOAD_URL),
                    null, PanelScreen.tr("instant-p2p.notice.ok"), null,
                    0xFFFFFF55, 0xFF1A1A00, null, null));
            return;
        }
        // 패치 안 됨 — 묻지 않고 막지 않는다. 고르면 그대로 처리한다(F4.0).
        FranchiseGate.show(new PanelScreen(parent,
                PanelScreen.tr("instant-p2p.jre.heading"),
                PanelScreen.tr("instant-p2p.jre.ask_body", now, JreGuard.DOWNLOAD_URL),
                null,
                PanelScreen.tr("instant-p2p.jre.fix"), PanelScreen.tr("instant-p2p.jre.skip"),
                0xFFFF5555, 0xFF1A0000,
                () -> {
                    boolean ok = JreGuard.fixNow();
                    FranchiseGate.show(new PanelScreen(parent,
                            PanelScreen.tr(ok ? "instant-p2p.jre.fixed_heading" : "instant-p2p.jre.fix_failed_heading"),
                            ok ? PanelScreen.tr("instant-p2p.jre.fixed_body")
                                    : PanelScreen.tr("instant-p2p.jre.fix_failed_body", now, JreGuard.DOWNLOAD_URL),
                            null, PanelScreen.tr("instant-p2p.notice.ok"), null,
                            ok ? 0xFF55FF55 : 0xFFFFFF55, ok ? 0xFF001A00 : 0xFF1A1A00, null, null));
                },
                JreGuard::continueWithoutFix));
    }
}
