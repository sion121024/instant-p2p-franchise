package kfc.udp.client.gui;

import kfc.udp.client.signaling.AntiCheatHooks;
import kfc.udp.client.signaling.AntiCheatSettings;
import kfc.udp.client.signaling.P2PConfig;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 안티치트 설정 — 방 설정 화면(CustomRoomScreen)의 "안티치트 설정" 버튼으로 연다.
 * <p>
 * 전체 켜기/끄기, 검사 항목별 켜기/끄기, 민감도(느슨/보통/엄격), 적발 시 처리(강퇴/경고만),
 * 방장에게 채팅 알림. "완료"를 눌러야 저장된다(취소하면 그대로). 저장하면 지금 열려 있는 방에도
 * 바로 적용된다 — 안티치트는 매 틱 설정값을 읽는다.
 */
public class AntiCheatScreen extends Screen {

    private static final int PANEL_W = 310;
    private static final int COL_W = 152;
    private static final int MASTER_Y = 36, GRID_Y = 64, ROW = 24;

    private final Screen parent;
    private final AntiCheatSettings s = P2PConfig.getAntiCheat().copy();

    /** 전체 스위치가 꺼지면 비활성화할 세부 버튼들 */
    //? if >=26.1 {
    /*private final List<Button> details = new ArrayList<>();
    private Button masterButton;
    *///?} else {
    private final List<ButtonWidget> details = new ArrayList<>();
    private ButtonWidget masterButton;
    //?}

    public AntiCheatScreen(Screen parent) {
        //? if >=26.1 {
        /*super(Component.translatable("instant-p2p.anticheat.title"));
        *///?} else {
        super(Text.translatable("instant-p2p.anticheat.title"));
        //?}
        this.parent = parent;
    }

    private int panelX() {
        return this.width / 2 - PANEL_W / 2;
    }

    private int gridY(int row) {
        return GRID_Y + row * ROW;
    }

    private void save() {
        AntiCheatHooks.applySettings(this.s);
        this.closeScreen();
    }

    private void closeScreen() {
        //? if >=26.1 {
        /*this.onClose();
        *///?} else {
        this.close();
        //?}
    }

    private static String onOffKey(boolean v) {
        return v ? "instant-p2p.anticheat.on" : "instant-p2p.anticheat.off";
    }

    //? if >=26.1 {
    /*private Component label(String key, boolean v) {
        return Component.translatable(key, Component.translatable(onOffKey(v)));
    }

    private Button toggle(int x, int y, int w, String key, BooleanSupplier get, Consumer<Boolean> set) {
        Button[] self = new Button[1];
        self[0] = Button.builder(label(key, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            self[0].setMessage(label(key, get.getAsBoolean()));
        }).bounds(x, y, w, 20).build();
        return self[0];
    }

    private void refreshActive() {
        this.masterButton.setMessage(label("instant-p2p.anticheat.opt.enabled", s.enabled));
        for (Button b : this.details) b.active = s.enabled;
    }

    private Component sensLabel() {
        return Component.translatable("instant-p2p.anticheat.opt.sensitivity",
                Component.translatable("instant-p2p.anticheat.sens." + s.sensitivity.name().toLowerCase()));
    }

    private Component actionLabel() {
        return Component.translatable("instant-p2p.anticheat.opt.action",
                Component.translatable("instant-p2p.anticheat.act." + s.action.name().toLowerCase()));
    }

    @Override
    protected void init() {
        this.details.clear();
        int x = this.panelX();
        int x2 = x + PANEL_W - COL_W;

        this.masterButton = Button.builder(Component.empty(), b -> {
            s.enabled = !s.enabled;
            this.refreshActive();
        }).bounds(x, MASTER_Y, PANEL_W, 20).build();
        this.addRenderableWidget(this.masterButton);

        this.details.add(toggle(x, gridY(0), COL_W, "instant-p2p.anticheat.opt.movement", () -> s.movement, v -> s.movement = v));
        this.details.add(toggle(x2, gridY(0), COL_W, "instant-p2p.anticheat.opt.combat", () -> s.combat, v -> s.combat = v));
        this.details.add(toggle(x, gridY(1), COL_W, "instant-p2p.anticheat.opt.world", () -> s.world, v -> s.world = v));
        this.details.add(toggle(x2, gridY(1), COL_W, "instant-p2p.anticheat.opt.packets", () -> s.packets, v -> s.packets = v));
        this.details.add(toggle(x, gridY(2), COL_W, "instant-p2p.anticheat.opt.integrity", () -> s.integrity, v -> s.integrity = v));
        this.details.add(toggle(x2, gridY(2), COL_W, "instant-p2p.anticheat.opt.chatspam", () -> s.chatSpam, v -> s.chatSpam = v));

        Button[] sens = new Button[1];
        sens[0] = Button.builder(sensLabel(), b -> {
            s.sensitivity = s.sensitivity.next();
            sens[0].setMessage(sensLabel());
        }).bounds(x, gridY(3), COL_W, 20).build();
        this.details.add(sens[0]);

        Button[] act = new Button[1];
        act[0] = Button.builder(actionLabel(), b -> {
            s.action = s.action.next();
            act[0].setMessage(actionLabel());
        }).bounds(x2, gridY(3), COL_W, 20).build();
        this.details.add(act[0]);

        this.details.add(toggle(x, gridY(4), COL_W, "instant-p2p.anticheat.opt.notify", () -> s.notifyHost, v -> s.notifyHost = v));
        this.details.add(toggle(x2, gridY(4), COL_W, "instant-p2p.anticheat.opt.aim", () -> s.aimStats, v -> s.aimStats = v));

        for (Button b : this.details) this.addRenderableWidget(b);
        this.refreshActive();

        int by = gridY(5) + 28;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.save())
                .bounds(x, by, PANEL_W / 2 - 4, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(x + PANEL_W / 2 + 4, by, PANEL_W / 2 - 4, 20).build());
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        context.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        Component hint = Component.translatable("instant-p2p.anticheat.hint." + s.sensitivity.name().toLowerCase());
        int y = gridY(5) + 4;
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(hint, PANEL_W)) {
            context.centeredText(this.font, line, this.width / 2, y, 0xFFA0A0A0);
            y += 10;
        }
    }
    *///?} else {
    private Text label(String key, boolean v) {
        return Text.translatable(key, Text.translatable(onOffKey(v)));
    }

    private ButtonWidget toggle(int x, int y, int w, String key, BooleanSupplier get, Consumer<Boolean> set) {
        ButtonWidget[] self = new ButtonWidget[1];
        self[0] = ButtonWidget.builder(label(key, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            self[0].setMessage(label(key, get.getAsBoolean()));
        }).dimensions(x, y, w, 20).build();
        return self[0];
    }

    private void refreshActive() {
        this.masterButton.setMessage(label("instant-p2p.anticheat.opt.enabled", s.enabled));
        for (ButtonWidget b : this.details) b.active = s.enabled;
    }

    private Text sensLabel() {
        return Text.translatable("instant-p2p.anticheat.opt.sensitivity",
                Text.translatable("instant-p2p.anticheat.sens." + s.sensitivity.name().toLowerCase()));
    }

    private Text actionLabel() {
        return Text.translatable("instant-p2p.anticheat.opt.action",
                Text.translatable("instant-p2p.anticheat.act." + s.action.name().toLowerCase()));
    }

    @Override
    protected void init() {
        this.details.clear();
        int x = this.panelX();
        int x2 = x + PANEL_W - COL_W;

        this.masterButton = ButtonWidget.builder(Text.empty(), b -> {
            s.enabled = !s.enabled;
            this.refreshActive();
        }).dimensions(x, MASTER_Y, PANEL_W, 20).build();
        this.addDrawableChild(this.masterButton);

        this.details.add(toggle(x, gridY(0), COL_W, "instant-p2p.anticheat.opt.movement", () -> s.movement, v -> s.movement = v));
        this.details.add(toggle(x2, gridY(0), COL_W, "instant-p2p.anticheat.opt.combat", () -> s.combat, v -> s.combat = v));
        this.details.add(toggle(x, gridY(1), COL_W, "instant-p2p.anticheat.opt.world", () -> s.world, v -> s.world = v));
        this.details.add(toggle(x2, gridY(1), COL_W, "instant-p2p.anticheat.opt.packets", () -> s.packets, v -> s.packets = v));
        this.details.add(toggle(x, gridY(2), COL_W, "instant-p2p.anticheat.opt.integrity", () -> s.integrity, v -> s.integrity = v));
        this.details.add(toggle(x2, gridY(2), COL_W, "instant-p2p.anticheat.opt.chatspam", () -> s.chatSpam, v -> s.chatSpam = v));

        ButtonWidget[] sens = new ButtonWidget[1];
        sens[0] = ButtonWidget.builder(sensLabel(), b -> {
            s.sensitivity = s.sensitivity.next();
            sens[0].setMessage(sensLabel());
        }).dimensions(x, gridY(3), COL_W, 20).build();
        this.details.add(sens[0]);

        ButtonWidget[] act = new ButtonWidget[1];
        act[0] = ButtonWidget.builder(actionLabel(), b -> {
            s.action = s.action.next();
            act[0].setMessage(actionLabel());
        }).dimensions(x2, gridY(3), COL_W, 20).build();
        this.details.add(act[0]);

        this.details.add(toggle(x, gridY(4), COL_W, "instant-p2p.anticheat.opt.notify", () -> s.notifyHost, v -> s.notifyHost = v));
        this.details.add(toggle(x2, gridY(4), COL_W, "instant-p2p.anticheat.opt.aim", () -> s.aimStats, v -> s.aimStats = v));

        for (ButtonWidget b : this.details) this.addDrawableChild(b);
        this.refreshActive();

        int by = gridY(5) + 28;
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> this.save())
                .dimensions(x, by, PANEL_W / 2 - 4, 20).build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> this.close())
                .dimensions(x + PANEL_W / 2 + 4, by, PANEL_W / 2 - 4, 20).build());
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFFFF);
        Text hint = Text.translatable("instant-p2p.anticheat.hint." + s.sensitivity.name().toLowerCase());
        int y = gridY(5) + 4;
        for (net.minecraft.text.OrderedText line : this.textRenderer.wrapLines(hint, PANEL_W)) {
            context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, y, 0xFFA0A0A0);
            y += 10;
        }
    }
    //?}
}
