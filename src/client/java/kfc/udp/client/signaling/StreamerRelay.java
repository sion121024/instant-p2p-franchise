package kfc.udp.client.signaling;

import java.util.UUID;

/**
 * 방송인 자동 중계(1.5.1) — 치지직 방송인으로 확인된 계정은 <b>처음 한 번</b> "중계 통신 강제"를 켜 준다(IP 노출 방지).
 * 한 번 처리하면 {@link P2PConfig#isStreamerRelayDone()} 로 남겨서, 사용자가 다시 끄면 그 선택을 존중한다.
 */
public final class StreamerRelay {
    private StreamerRelay() {}

    /** 역할 목록을 새로 받아 본 뒤 방송인이면 중계 강제를 켠다. 시작할 때와 치지직 연동 직후에 부른다. */
    public static void refreshAndCheck() {
        if (P2PConfig.isStreamerRelayDone()) return;
        Roles.refreshThen(() -> runOnClient(StreamerRelay::checkNow));
    }

    private static void checkNow() {
        if (P2PConfig.isStreamerRelayDone()) return;
        String me = MojangAuth.currentUuidOrNull();
        if (me == null) return;
        UUID id;
        try { id = UUID.fromString(me); } catch (IllegalArgumentException e) { return; }
        if (!Roles.isStreamer(id)) return;
        P2PConfig.setStreamerRelayDone(true);
        if (!P2PConfig.isRelayOnly()) {
            P2PConfig.setRelayOnly(true);
            toast("instant-p2p.toast.streamer_relay.title", "instant-p2p.toast.streamer_relay.body");
        }
    }

    //? if >=26.1 {
    /*public static void runOnClient(Runnable r) {
        net.minecraft.client.Minecraft.getInstance().execute(r);
    }

    public static void toast(String titleKey, String bodyKey) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.gui.components.toasts.ToastManager tm = toastManager(mc);
        net.minecraft.client.gui.components.toasts.SystemToast.add(tm,
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                net.minecraft.network.chat.Component.translatable(titleKey),
                net.minecraft.network.chat.Component.translatable(bodyKey));
    }
    *///?}
    //? if >=26.2 {
    /*private static net.minecraft.client.gui.components.toasts.ToastManager toastManager(net.minecraft.client.Minecraft mc) {
        return mc.gui.toastManager();
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static net.minecraft.client.gui.components.toasts.ToastManager toastManager(net.minecraft.client.Minecraft mc) {
        return mc.getToastManager();
    }
    *///?}
    //? if <26.1 {
    public static void runOnClient(Runnable r) {
        net.minecraft.client.MinecraftClient.getInstance().execute(r);
    }

    public static void toast(String titleKey, String bodyKey) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.client.toast.SystemToast.add(mc.getToastManager(),
                net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION,
                net.minecraft.text.Text.translatable(titleKey),
                net.minecraft.text.Text.translatable(bodyKey));
    }
    //?}
}
