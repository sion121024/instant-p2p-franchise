package kfc.udp.client.gui;

import kfc.udp.client.signaling.StreamLink;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?}

/**
 * 가맹점 방송인 연동 화면(F3.3~) — 가맹점 서버를 쓰는 동안 방 목록의 "방송인 역할 받기" 버튼이 이걸 연다
 * (본점에서는 원작자의 ChzzkLinkScreen 그대로).
 * <p>
 * 치지직·트위치·씨미·SOOP 중 하나를 고르고 채널 주소를 넣으면 서버가 인증 코드를 준다. 그 코드를 채널 소개에
 * 잠깐 넣고 [확인]을 누르면 서버가 채널 공개 정보를 읽어 연동한다(StreamLink, 서버 streamlink.go).
 * OAuth가 아니라서 플랫폼별 개발자 앱 등록이 필요 없다.
 */
public class StreamLinkScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int CORNER_BTN_W = 70, CORNER_BTN_H = 20, CORNER_MARGIN = 8;
    private static final int TITLE_Y = 34, DESC_Y = 50, LINE_H = 10;
    private static final int GET_CODE_W = 80, COPY_W = 50, VERIFY_W = 120, UNLINK_W = 120;
    private static final String[] PLATFORMS = {"chzzk", "twitch", "cime", "soop"};

    private final Screen parent;
    private String platform = "chzzk";
    private String channelInput = "";
    private String code = "";
    private String statusKey = "instant-p2p.stream_link.checking";
    private Object[] statusArgs = new Object[0];
    private int statusColor = 0xFFA0A0A0;
    private boolean linked;
    private boolean busy;
    private int statusY, codeY;

    //? if >=26.1 {
    /*private final Button[] platformButtons = new Button[4];
    private EditBox channelField;
    private Button copyButton, verifyButton, unlinkButton, getCodeButton;
    *///?} else {
    private final ButtonWidget[] platformButtons = new ButtonWidget[4];
    private TextFieldWidget channelField;
    private ButtonWidget copyButton, verifyButton, unlinkButton, getCodeButton;
    //?}

    /** 원본식 연동 화면(ChzzkLinkScreen)의 플랫폼 버튼에서 — 그 플랫폼을 골라 둔 채로 연다(F4.0). */
    public StreamLinkScreen(Screen parent, String platform) {
        this(parent);
        for (String p : PLATFORMS) {
            if (p.equals(platform)) this.platform = p;
        }
    }

    public StreamLinkScreen(Screen parent) {
        //? if >=26.1 {
        /*super(Component.translatable("instant-p2p.stream_link.title"));
        *///?} else {
        super(Text.translatable("instant-p2p.stream_link.title"));
        //?}
        this.parent = parent;
    }

    private int panelX() {
        return this.width / 2 - PANEL_W / 2;
    }

    private static String platformLabelKey(String p) {
        return "instant-p2p.stream_link.platform." + p;
    }

    // ── 위젯 ────────────────────────────────────────────────────────────

    //? if >=26.1 {
    /*@Override
    protected void init() {
        int x = this.panelX();
        int descLines = this.font.split(Component.translatable("instant-p2p.stream_link.desc"), PANEL_W).size();
        this.statusY = DESC_Y + descLines * LINE_H + 6;
        int platY = this.statusY + 14;
        int fieldY = platY + 24;
        this.codeY = fieldY + 28;
        int verifyY = this.codeY + 44;

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .bounds(CORNER_MARGIN, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());
        this.addRenderableWidget(Button.builder(Component.translatable("instant-p2p.stream_link.refresh"), b -> this.refreshStatus())
                .bounds(this.width - CORNER_MARGIN - CORNER_BTN_W, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());
        this.unlinkButton = Button.builder(Component.translatable("instant-p2p.stream_link.unlink").withStyle(ChatFormatting.RED), b -> this.onUnlink())
                .bounds(this.width / 2 - UNLINK_W / 2, CORNER_MARGIN, UNLINK_W, CORNER_BTN_H).build();
        this.addRenderableWidget(this.unlinkButton);

        int bw = (PANEL_W - 12) / 4;
        for (int i = 0; i < 4; i++) {
            final String p = PLATFORMS[i];
            this.platformButtons[i] = Button.builder(Component.translatable(platformLabelKey(p)), b -> this.selectPlatform(p))
                    .bounds(x + i * (bw + 4), platY, bw, 20).build();
            this.addRenderableWidget(this.platformButtons[i]);
        }
        this.channelField = new EditBox(this.font, x, fieldY, PANEL_W - GET_CODE_W - 4, 20, Component.empty());
        this.channelField.setMaxLength(120);
        this.channelField.setValue(this.channelInput);
        this.channelField.setResponder(t -> this.channelInput = t);
        this.addRenderableWidget(this.channelField);
        this.getCodeButton = Button.builder(Component.translatable("instant-p2p.stream_link.get_code"), b -> this.onGetCode())
                .bounds(x + PANEL_W - GET_CODE_W, fieldY, GET_CODE_W, 20).build();
        this.addRenderableWidget(this.getCodeButton);
        this.copyButton = Button.builder(Component.translatable("instant-p2p.stream_link.copy"), b -> {
                    if (this.minecraft != null && !this.code.isEmpty()) this.minecraft.keyboardHandler.setClipboard(this.code);
                })
                .bounds(x + PANEL_W - COPY_W, this.codeY - 6, COPY_W, 20).build();
        this.addRenderableWidget(this.copyButton);
        this.verifyButton = Button.builder(Component.translatable("instant-p2p.stream_link.verify"), b -> this.onVerify())
                .bounds(this.width / 2 - VERIFY_W / 2, verifyY, VERIFY_W, 20).build();
        this.addRenderableWidget(this.verifyButton);
        this.syncWidgets();
        if (!this.busy && !this.linked && this.code.isEmpty()) this.refreshStatus();
    }

    private void syncWidgets() {
        for (int i = 0; i < 4; i++) {
            Component label = Component.translatable(platformLabelKey(PLATFORMS[i]));
            this.platformButtons[i].setMessage(PLATFORMS[i].equals(this.platform)
                    ? label.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE) : label);
        }
        this.channelField.setHint(Component.translatable("instant-p2p.stream_link.hint." + this.platform).withStyle(ChatFormatting.DARK_GRAY));
        this.unlinkButton.visible = this.linked;
        this.copyButton.visible = !this.code.isEmpty();
        this.verifyButton.visible = !this.code.isEmpty();
        this.verifyButton.active = !this.busy;
        this.getCodeButton.active = !this.busy;
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
        context.centeredText(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        int y = DESC_Y;
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(Component.translatable("instant-p2p.stream_link.desc"), PANEL_W)) {
            context.text(this.font, line, x, y, 0xFFA0A0A0);
            y += LINE_H;
        }
        context.text(this.font, Component.translatable(this.statusKey, this.statusArgs).getVisualOrderText(), x, this.statusY, this.statusColor);
        if (!this.code.isEmpty()) {
            context.text(this.font, Component.translatable("instant-p2p.stream_link.code", this.code).getVisualOrderText(), x, this.codeY, 0xFFFFFF55);
            int gy = this.codeY + 14;
            for (net.minecraft.util.FormattedCharSequence line : this.font.split(
                    Component.translatable("instant-p2p.stream_link.where." + this.platform), PANEL_W)) {
                context.text(this.font, line, x, gy, 0xFFA0A0A0);
                gy += LINE_H;
            }
        }
    }

    private void runOnClientThread(Runnable r) {
        net.minecraft.client.Minecraft.getInstance().execute(r);
    }
    *///?} else {
    @Override
    protected void init() {
        int x = this.panelX();
        int descLines = this.textRenderer.wrapLines(Text.translatable("instant-p2p.stream_link.desc"), PANEL_W).size();
        this.statusY = DESC_Y + descLines * LINE_H + 6;
        int platY = this.statusY + 14;
        int fieldY = platY + 24;
        this.codeY = fieldY + 28;
        int verifyY = this.codeY + 44;

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> this.close())
                .dimensions(CORNER_MARGIN, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("instant-p2p.stream_link.refresh"), b -> this.refreshStatus())
                .dimensions(this.width - CORNER_MARGIN - CORNER_BTN_W, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());
        this.unlinkButton = ButtonWidget.builder(Text.translatable("instant-p2p.stream_link.unlink").formatted(Formatting.RED), b -> this.onUnlink())
                .dimensions(this.width / 2 - UNLINK_W / 2, CORNER_MARGIN, UNLINK_W, CORNER_BTN_H).build();
        this.addDrawableChild(this.unlinkButton);

        int bw = (PANEL_W - 12) / 4;
        for (int i = 0; i < 4; i++) {
            final String p = PLATFORMS[i];
            this.platformButtons[i] = ButtonWidget.builder(Text.translatable(platformLabelKey(p)), b -> this.selectPlatform(p))
                    .dimensions(x + i * (bw + 4), platY, bw, 20).build();
            this.addDrawableChild(this.platformButtons[i]);
        }
        this.channelField = new TextFieldWidget(this.textRenderer, x, fieldY, PANEL_W - GET_CODE_W - 4, 20, Text.empty());
        this.channelField.setMaxLength(120);
        this.channelField.setText(this.channelInput);
        this.channelField.setChangedListener(t -> this.channelInput = t);
        this.addDrawableChild(this.channelField);
        this.getCodeButton = ButtonWidget.builder(Text.translatable("instant-p2p.stream_link.get_code"), b -> this.onGetCode())
                .dimensions(x + PANEL_W - GET_CODE_W, fieldY, GET_CODE_W, 20).build();
        this.addDrawableChild(this.getCodeButton);
        this.copyButton = ButtonWidget.builder(Text.translatable("instant-p2p.stream_link.copy"), b -> {
                    if (this.client != null && !this.code.isEmpty()) this.client.keyboard.setClipboard(this.code);
                })
                .dimensions(x + PANEL_W - COPY_W, this.codeY - 6, COPY_W, 20).build();
        this.addDrawableChild(this.copyButton);
        this.verifyButton = ButtonWidget.builder(Text.translatable("instant-p2p.stream_link.verify"), b -> this.onVerify())
                .dimensions(this.width / 2 - VERIFY_W / 2, verifyY, VERIFY_W, 20).build();
        this.addDrawableChild(this.verifyButton);
        this.syncWidgets();
        if (!this.busy && !this.linked && this.code.isEmpty()) this.refreshStatus();
    }

    private void syncWidgets() {
        for (int i = 0; i < 4; i++) {
            Text label = Text.translatable(platformLabelKey(PLATFORMS[i]));
            this.platformButtons[i].setMessage(PLATFORMS[i].equals(this.platform)
                    ? label.copy().formatted(Formatting.YELLOW, Formatting.UNDERLINE) : label);
        }
        this.channelField.setPlaceholder(Text.translatable("instant-p2p.stream_link.hint." + this.platform).formatted(Formatting.DARK_GRAY));
        this.unlinkButton.visible = this.linked;
        this.copyButton.visible = !this.code.isEmpty();
        this.verifyButton.visible = !this.code.isEmpty();
        this.verifyButton.active = !this.busy;
        this.getCodeButton.active = !this.busy;
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        int y = DESC_Y;
        for (net.minecraft.text.OrderedText line : this.textRenderer.wrapLines(Text.translatable("instant-p2p.stream_link.desc"), PANEL_W)) {
            context.drawTextWithShadow(this.textRenderer, line, x, y, 0xFFA0A0A0);
            y += LINE_H;
        }
        context.drawTextWithShadow(this.textRenderer, Text.translatable(this.statusKey, this.statusArgs), x, this.statusY, this.statusColor);
        if (!this.code.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("instant-p2p.stream_link.code", this.code), x, this.codeY, 0xFFFFFF55);
            int gy = this.codeY + 14;
            for (net.minecraft.text.OrderedText line : this.textRenderer.wrapLines(
                    Text.translatable("instant-p2p.stream_link.where." + this.platform), PANEL_W)) {
                context.drawTextWithShadow(this.textRenderer, line, x, gy, 0xFFA0A0A0);
                gy += LINE_H;
            }
        }
    }

    private void runOnClientThread(Runnable r) {
        net.minecraft.client.MinecraftClient.getInstance().execute(r);
    }
    //?}

    // ── 동작(버전 무관) ─────────────────────────────────────────────────

    private void setStatus(String key, int color, Object... args) {
        this.statusKey = key;
        this.statusColor = color;
        this.statusArgs = args;
    }

    private void selectPlatform(String p) {
        if (!p.equals(this.platform)) {
            this.platform = p;
            this.code = "";
        }
        this.syncWidgets();
    }

    private void refreshStatus() {
        this.busy = true;
        this.setStatus("instant-p2p.stream_link.checking", 0xFFA0A0A0);
        this.syncWidgets();
        StreamLink.status().thenAccept(r -> this.runOnClientThread(() -> {
            this.busy = false;
            this.showResult(r);
            this.syncWidgets();
        }));
    }

    private void onGetCode() {
        String in = this.channelInput.trim();
        if (in.isEmpty()) {
            this.setStatus("instant-p2p.stream_link.err.bad-channel", 0xFFFF5555);
            return;
        }
        this.busy = true;
        this.setStatus("instant-p2p.stream_link.working", 0xFFA0A0A0);
        this.syncWidgets();
        StreamLink.start(this.platform, in).thenAccept(r -> this.runOnClientThread(() -> {
            this.busy = false;
            if (!r.code().isEmpty()) {
                this.code = r.code();
                this.setStatus("instant-p2p.stream_link.code_ready", 0xFFFFFFFF);
            } else {
                this.showError(r.error());
            }
            this.syncWidgets();
        }));
    }

    private void onVerify() {
        this.busy = true;
        this.setStatus("instant-p2p.stream_link.working", 0xFFA0A0A0);
        this.syncWidgets();
        StreamLink.verify().thenAccept(r -> this.runOnClientThread(() -> {
            this.busy = false;
            if (r.linked()) {
                this.code = "";
                kfc.udp.client.signaling.Roles.refreshAsync(); // 배지 바로 반영
            }
            this.showResult(r);
            this.syncWidgets();
        }));
    }

    private void onUnlink() {
        this.busy = true;
        this.syncWidgets();
        StreamLink.unlink().thenAccept(r -> this.runOnClientThread(() -> {
            this.busy = false;
            this.linked = false;
            kfc.udp.client.signaling.Roles.refreshAsync();
            this.showResult(r);
            this.syncWidgets();
        }));
    }

    private void showResult(StreamLink.Result r) {
        if (r.linked()) {
            this.linked = true;
            this.setStatus(r.qualifies() ? "instant-p2p.stream_link.linked" : "instant-p2p.stream_link.linked_below",
                    r.qualifies() ? 0xFF55FF55 : 0xFFFFAA00,
                    platformName(r.platform()), r.channel(), r.followers(), r.minFollowers());
            return;
        }
        this.linked = false;
        if (r.error().isEmpty()) {
            this.setStatus("instant-p2p.stream_link.unlinked", 0xFFA0A0A0);
        } else {
            this.showError(r.error());
        }
    }

    private void showError(String err) {
        String key = switch (err) {
            case "auth-required", "bad-channel", "code-not-found", "channel-not-found", "no-pending", "too-fast", "fetch-failed" -> err;
            default -> "network";
        };
        this.setStatus("instant-p2p.stream_link.err." + key, 0xFFFF5555);
    }

    private static String platformName(String p) {
        return switch (p) {
            case "chzzk" -> "치지직";
            case "twitch" -> "Twitch";
            case "cime" -> "씨미";
            case "soop" -> "SOOP";
            default -> p;
        };
    }
}
