package kfc.udp.client.gui;

import kfc.udp.client.signaling.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
//?}

import java.util.Objects;

/**
 * 방 목록에서 방 칸의 차단 버튼을 눌렀을 때 뜨는 작은 메뉴(1.5~) — 이 방장 차단(또는 해제)과 이 방 신고(제목·내용이
 * 부적절할 때) 둘 중에 고른다. 예전엔 차단 버튼이 곧바로 차단이었다.
 */
final class RoomActionScreen extends Screen {

    private final Screen parent;
    private final String hostUuid;
    private final String hostName;
    private final String code;
    private final String roomTitle;
    private final boolean blocked;

    static void open(Screen parent, String hostUuid, String hostName, String code, String title) {
        SafetyWarningScreen.open(new RoomActionScreen(parent, hostUuid, hostName, code, title));
    }

    private RoomActionScreen(Screen parent, String hostUuid, String hostName, String code, String title) {
        //? if >=26.1 {
        /*super(Component.translatable("instant-p2p.room_action.title"));
        *///?} else {
        super(Text.translatable("instant-p2p.room_action.title"));
        //?}
        this.parent = parent;
        this.hostUuid = hostUuid;
        this.hostName = hostName != null && !hostName.isEmpty() ? hostName : hostUuid.substring(0, Math.min(8, hostUuid.length()));
        this.code = code;
        this.roomTitle = title == null ? "" : title;
        this.blocked = P2PBanManager.isPlayerBanned(hostUuid);
    }

    private void toggleBlock() {
        if (this.blocked) P2PBanManager.pardonPlayerByUuid(this.hostUuid);
        else P2PBanManager.banPlayer(this.hostUuid, this.hostName, "Blocked from room list.");
        this.back();
    }

    private int baseY() {
        return this.height / 2 - 30;
    }

    //? if >=26.1 {
    /*private void back() {
        this.onClose();
    }

    @Override
    protected void init() {
        int cx = this.width / 2, y = this.baseY();
        this.addRenderableWidget(Button.builder(Component.translatable(this.blocked ? "instant-p2p.room_action.unblock" : "instant-p2p.room_action.block"),
                b -> this.toggleBlock()).bounds(cx - 100, y, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("instant-p2p.room_action.report"),
                b -> ReportScreen.openRoom(this.parent, this.hostUuid, this.hostName, this.code, this.roomTitle)).bounds(cx - 100, y + 26, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("instant-p2p.report.cancel"), b -> this.onClose())
                .bounds(cx - 100, y + 60, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2, y = this.baseY();
        context.centeredText(this.font, this.title, cx, y - 40, 0xFFFFFFFF);
        context.centeredText(this.font, Component.literal(this.hostName), cx, y - 26, 0xFFFFFF55);
        context.centeredText(this.font, Component.translatable("instant-p2p.room_action.note"), cx, y + 88, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        Objects.requireNonNull(this.minecraft).setScreenAndShow(this.parent);
    }
    *///?} else {
    private void back() {
        this.close();
    }

    @Override
    protected void init() {
        int cx = this.width / 2, y = this.baseY();
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(this.blocked ? "instant-p2p.room_action.unblock" : "instant-p2p.room_action.block"),
                b -> this.toggleBlock()).dimensions(cx - 100, y, 200, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("instant-p2p.room_action.report"),
                b -> ReportScreen.openRoom(this.parent, this.hostUuid, this.hostName, this.code, this.roomTitle)).dimensions(cx - 100, y + 26, 200, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("instant-p2p.report.cancel"), b -> this.close())
                .dimensions(cx - 100, y + 60, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2, y = this.baseY();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, y - 40, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.hostName), cx, y - 26, 0xFFFFFF55);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("instant-p2p.room_action.note"), cx, y + 88, 0xFFA0A0A0);
    }

    @Override
    public void close() {
        Objects.requireNonNull(this.client).setScreen(this.parent);
    }
    //?}
}
