package kfc.udp.client.gui;

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

/**
 * 공식 서버 / 사설 서버 선택 화면 — 방 열기·방 목록 화면 어디서든 "서버 설정" 버튼으로 연다.
 * <p>
 * <b>사설 서버 주소는 여기서 입력받지 않는다</b> — 우리 쪽에서 운영하는 서버 하나로 고정돼 있고
 * (실제 값은 {@code P2PConfig.FRANCHISE_SIGNALING_URL} 등 상수), 이 화면은 공식/사설 중 어느 쪽에
 * 붙을지 고르는 토글 버튼 하나뿐이다. 다른 서버 주소를 직접 넣고 싶으면(테스트 등) 여전히
 * {@code -Dkfcudp.signaling} 같은 JVM 프로퍼티로 덮어쓸 수 있다(프로필과 무관하게 항상 최우선).
 * <p>
 * <b>공식 서버와 사설 서버는 완전히 다른 세계다</b> — 초대 코드·공개 방 목록이 서버마다 따로
 * 관리되므로, 한쪽 서버에서 연 방은 다른 서버를 보고 있는 사람에게 절대 보이지 않고 코드로도
 * 못 들어간다. 방을 열거나 찾기 전에 상대와 같은 서버 프로필인지 먼저 맞춰야 한다.
 */
public class ServerProfileScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int DESC_Y = 32, TOGGLE_Y = 60, INFO_Y = 88, BUTTONS_Y = 130;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT = Component.translatable("instant-p2p.server.title");
    private static final Component DESC_TEXT = Component.translatable("instant-p2p.server.desc");
    *///?} else {
    private static final Text TITLE_TEXT = Text.translatable("instant-p2p.server.title");
    private static final Text DESC_TEXT = Text.translatable("instant-p2p.server.desc");
    //?}

    private final Screen parent;
    private P2PConfig.ServerProfile profile = P2PConfig.getServerProfile();

    //? if >=26.1 {
    /*private Button toggleButton;
    *///?} else {
    private ButtonWidget toggleButton;
    //?}

    public ServerProfileScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    private int panelX() {
        return this.width / 2 - PANEL_W / 2;
    }

    private boolean isFranchise() {
        return this.profile == P2PConfig.ServerProfile.FRANCHISE;
    }

    private void toggleProfile() {
        this.profile = isFranchise() ? P2PConfig.ServerProfile.OFFICIAL : P2PConfig.ServerProfile.FRANCHISE;
        this.updateToggleLabel();
    }

    private void apply() {
        if (this.profile == P2PConfig.ServerProfile.FRANCHISE && !P2PConfig.isTermsAccepted()) {
            P2PConfig.ServerProfile want = this.profile;
            FranchiseGate.requireTerms(this, () -> {
                P2PConfig.setServerProfile(want);
                this.closeScreen();
            }, null);
            return;
        }
        P2PConfig.setServerProfile(this.profile);
        this.closeScreen();
    }

    private void closeScreen() {
        //? if >=26.1 {
        /*this.onClose();
        *///?} else {
        this.close();
        //?}
    }

    //? if >=26.1 {
    /*private void updateToggleLabel() {
        this.toggleButton.setMessage(Component.translatable(
                isFranchise() ? "instant-p2p.server.profile.franchise" : "instant-p2p.server.profile.official"));
    }

    @Override
    protected void init() {
        int x = this.panelX();
        this.toggleButton = Button.builder(Component.empty(), b -> this.toggleProfile())
                .bounds(x, TOGGLE_Y, PANEL_W, 20).build();
        this.updateToggleLabel();
        this.addRenderableWidget(this.toggleButton);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.apply())
                .bounds(x, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(x + PANEL_W / 2 + 4, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int x = this.panelX();
        context.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        this.drawWrapped(context, DESC_TEXT, x, DESC_Y, 0xFFA0A0A0);
        if (isFranchise()) {
            this.drawWrapped(context, Component.translatable("instant-p2p.server.franchise.fixed"), x, INFO_Y, 0xFFA0A0A0);
        }
    }

    private void drawWrapped(GuiGraphicsExtractor context, Component text, int x, int y, int color) {
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(text, PANEL_W)) {
            context.text(this.font, line, x, y, color);
            y += 10;
        }
    }
    *///?} else {
    private void updateToggleLabel() {
        this.toggleButton.setMessage(Text.translatable(
                isFranchise() ? "instant-p2p.server.profile.franchise" : "instant-p2p.server.profile.official"));
    }

    @Override
    protected void init() {
        int x = this.panelX();
        this.toggleButton = ButtonWidget.builder(Text.empty(), b -> this.toggleProfile())
                .dimensions(x, TOGGLE_Y, PANEL_W, 20).build();
        this.updateToggleLabel();
        this.addDrawableChild(this.toggleButton);

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> this.apply())
                .dimensions(x, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> this.close())
                .dimensions(x + PANEL_W / 2 + 4, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int x = this.panelX();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFFFF);
        this.drawWrapped(context, DESC_TEXT, x, DESC_Y, 0xFFA0A0A0);
        if (isFranchise()) {
            this.drawWrapped(context, Text.translatable("instant-p2p.server.franchise.fixed"), x, INFO_Y, 0xFFA0A0A0);
        }
    }

    private void drawWrapped(DrawContext context, Text text, int x, int y, int color) {
        for (net.minecraft.text.OrderedText line : this.textRenderer.wrapLines(text, PANEL_W)) {
            context.drawTextWithShadow(this.textRenderer, line, x, y, color);
            y += 10;
        }
    }
    //?}
}
