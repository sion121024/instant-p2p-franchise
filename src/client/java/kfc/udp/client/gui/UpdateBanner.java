package kfc.udp.client.gui;

import kfc.udp.client.signaling.AutoUpdate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

/**
 * 메인 화면(싱글플레이·멀티플레이 버튼이 있는 화면) 오른쪽의 업데이트 안내 — 공지와는 따로다.
 * <ul>
 *   <li>새 판이 있으면: 초록 글씨 "새 버전 F3.5이 있습니다" + [업데이트] + [패치노트]</li>
 *   <li>받는 중: [받는 중 37%] (누를 수 없음)</li>
 *   <li>다 받으면: "다시 시작하면 F3.5이 적용됩니다" + [지금 다시 시작]</li>
 *   <li>실패: 빨간 글씨 + [다시 시도] (이유는 마우스를 올리면)</li>
 * </ul>
 * 위젯은 메인 화면이 열릴 때 늘 만들어 두고, 상태에 따라 매 틱 보이기/글자만 바꾼다 — 확인 결과가 화면이 열린 뒤에
 * 와도 바로 나타나게.
 */
public final class UpdateBanner {

    private static final int W = 150, BTN_H = 20, GAP = 4;

    private UpdateBanner() {}

    public static void register() {
        AutoUpdate.resumePendingSwap();
        //? if >=26.1 {
        /*ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
            AutoUpdate.checkIfDue();
            int x = Math.max(w / 2 + 112, 0);
            int width = Math.min(W, w - x - 6);
            if (width < 90) return; // 창이 너무 좁으면 안 띄운다
            int y = h / 4 + 48;
            var font = client.font;
            var label = new net.minecraft.client.gui.components.StringWidget(x, y, width, 10,
                    net.minecraft.network.chat.Component.empty(), font);
            var update = net.minecraft.client.gui.components.Button.builder(
                    net.minecraft.network.chat.Component.translatable("instant-p2p.update.button"), b -> onUpdate(screen))
                    .bounds(x, y + 12, width, BTN_H).build();
            var notes = net.minecraft.client.gui.components.Button.builder(
                    net.minecraft.network.chat.Component.translatable("instant-p2p.update.notes"), b -> openNotes(screen))
                    .bounds(x, y + 12 + BTN_H + GAP, width, BTN_H).build();
            Screens.getWidgets(screen).add(label);
            Screens.getWidgets(screen).add(update);
            Screens.getWidgets(screen).add(notes);
            Runnable refresh = () -> {
                AutoUpdate.State s = AutoUpdate.state();
                AutoUpdate.Info i = AutoUpdate.info();
                boolean show = s != AutoUpdate.State.NONE && i != null;
                label.visible = update.visible = show;
                notes.visible = show && !i.notes().isBlank();
                if (!show) return;
                switch (s) {
                    case AVAILABLE -> {
                        label.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.available", i.latest())
                                .withStyle(net.minecraft.ChatFormatting.GREEN));
                        update.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.button"));
                        update.active = true;
                        update.setTooltip(null);
                    }
                    case DOWNLOADING -> {
                        update.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.downloading", AutoUpdate.percent()));
                        update.active = false;
                    }
                    case READY -> {
                        label.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.ready", i.latest())
                                .withStyle(net.minecraft.ChatFormatting.GREEN));
                        update.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.restart"));
                        update.active = true;
                    }
                    case FAILED -> {
                        label.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.failed")
                                .withStyle(net.minecraft.ChatFormatting.RED));
                        update.setMessage(net.minecraft.network.chat.Component.translatable("instant-p2p.update.retry"));
                        update.active = true;
                        update.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                                net.minecraft.network.chat.Component.literal(AutoUpdate.error())));
                    }
                    default -> { }
                }
            };
            refresh.run();
            ScreenEvents.afterTick(screen).register(sc -> refresh.run());
        });
        *///?} else {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screen.TitleScreen)) return;
            AutoUpdate.checkIfDue();
            int x = Math.max(w / 2 + 112, 0);
            int width = Math.min(W, w - x - 6);
            if (width < 90) return;
            int y = h / 4 + 48;
            var tr = client.textRenderer;
            var label = new net.minecraft.client.gui.widget.TextWidget(x, y, width, 10, net.minecraft.text.Text.empty(), tr);
            var update = net.minecraft.client.gui.widget.ButtonWidget.builder(
                    net.minecraft.text.Text.translatable("instant-p2p.update.button"), b -> onUpdate(screen))
                    .dimensions(x, y + 12, width, BTN_H).build();
            var notes = net.minecraft.client.gui.widget.ButtonWidget.builder(
                    net.minecraft.text.Text.translatable("instant-p2p.update.notes"), b -> openNotes(screen))
                    .dimensions(x, y + 12 + BTN_H + GAP, width, BTN_H).build();
            Screens.getButtons(screen).add(label);
            Screens.getButtons(screen).add(update);
            Screens.getButtons(screen).add(notes);
            Runnable refresh = () -> {
                AutoUpdate.State s = AutoUpdate.state();
                AutoUpdate.Info i = AutoUpdate.info();
                boolean show = s != AutoUpdate.State.NONE && i != null;
                label.visible = update.visible = show;
                notes.visible = show && !i.notes().isBlank();
                if (!show) return;
                switch (s) {
                    case AVAILABLE -> {
                        label.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.available", i.latest())
                                .formatted(net.minecraft.util.Formatting.GREEN));
                        update.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.button"));
                        update.active = true;
                        update.setTooltip(null);
                    }
                    case DOWNLOADING -> {
                        update.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.downloading", AutoUpdate.percent()));
                        update.active = false;
                    }
                    case READY -> {
                        label.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.ready", i.latest())
                                .formatted(net.minecraft.util.Formatting.GREEN));
                        update.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.restart"));
                        update.active = true;
                    }
                    case FAILED -> {
                        label.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.failed")
                                .formatted(net.minecraft.util.Formatting.RED));
                        update.setMessage(net.minecraft.text.Text.translatable("instant-p2p.update.retry"));
                        update.active = true;
                        update.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(net.minecraft.text.Text.literal(AutoUpdate.error())));
                    }
                    default -> { }
                }
            };
            refresh.run();
            ScreenEvents.afterTick(screen).register(sc -> refresh.run());
        });
        //?}
    }

    private static void onUpdate(Object screen) {
        switch (AutoUpdate.state()) {
            case AVAILABLE, FAILED -> AutoUpdate.startDownload();
            case READY -> FranchiseGate.show(new PanelScreen(asScreen(screen),
                    PanelScreen.tr("instant-p2p.update.restart_heading"),
                    PanelScreen.tr("instant-p2p.update.restart_body", AutoUpdate.info().latest()),
                    null, PanelScreen.tr("instant-p2p.update.restart_now"), PanelScreen.tr("instant-p2p.update.later"),
                    0xFF55FF55, 0xFF001A00, UpdateBanner::quitGame, null));
            default -> { }
        }
    }

    private static void openNotes(Object screen) {
        AutoUpdate.Info i = AutoUpdate.info();
        if (i == null) return;
        FranchiseGate.show(new PanelScreen(asScreen(screen),
                PanelScreen.tr("instant-p2p.update.notes_heading", i.latest()),
                i.notes().isBlank() ? PanelScreen.tr("instant-p2p.update.no_notes") : i.notes(),
                null, PanelScreen.tr("instant-p2p.notice.ok"), null,
                0xFF55FF55, 0xFF001A00, null, null));
    }

    //? if >=26.1 {
    /*private static net.minecraft.client.gui.screens.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screens.Screen) s;
    }

    private static void quitGame() {
        net.minecraft.client.Minecraft.getInstance().stop();
    }
    *///?} else {
    private static net.minecraft.client.gui.screen.Screen asScreen(Object s) {
        return (net.minecraft.client.gui.screen.Screen) s;
    }

    private static void quitGame() {
        net.minecraft.client.MinecraftClient.getInstance().scheduleStop();
    }
    //?}
}
