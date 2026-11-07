package kfc.udp.client.gui;

import kfc.udp.client.signaling.ReportClient;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?}

import java.util.Objects;

/**
 * 신고 화면 — 가맹점 F4.0 부터 <b>원본 1.5.1 과 같은 화면·같은 분류</b>다.
 * <ul>
 *   <li>분류 버튼(누를 때마다 다음 분류: 성희롱·괴롭힘 / 행위 괴롭힘 / 금전·후원 유도 / 불법·위험 / 기타), 하고 싶은 말(선택, 200자),
 *       신고·취소. 방 신고({@link #openRoom})는 분류가 "방 제목·내용" 하나뿐이라 분류 버튼이 그것만 보여 준다.</li>
 *   <li>보내면 신고 대상(방 신고면 방장)을 자동으로 차단한다 — 원본과 같다.</li>
 * </ul>
 * 실제 전송(앞뒤 채팅·표지판·책 첨부, 1분 모으기, 10분 중복 막기)은 {@link ReportClient} 가 하고 결과는 채팅으로 알린다.
 */
public class ReportScreen extends Screen {

    private static final String[] CATEGORIES = ReportClient.CATEGORIES;

    private final Screen parent;
    private final String targetUuid;
    private final String targetName;
    /** 방 신고면 방 코드·그때 본 제목, 플레이어 신고면 null. */
    private final String roomCode;
    private final String roomTitle;
    private int category = 0;
    private String text = "";

    /** 플레이어 신고 화면을 연다. */
    public static void open(Screen parent, String uuid, String name) {
        show(new ReportScreen(parent, uuid, name, null, null));
    }

    /** 방(제목·내용) 신고 화면을 연다 — 대상은 방장. */
    public static void openRoom(Screen parent, String hostUuid, String hostName, String code, String title) {
        show(new ReportScreen(parent, hostUuid, hostName, code, title));
    }

    /** 신고와 함께 차단한다 — {@code /report} 명령도 같은 동작을 하도록 공개해 둔다. */
    public static void autoBlock(String uuid, String name) {
        try {
            java.util.UUID.fromString(uuid); // 남이 보낸 방장 UUID 가 비었거나 이상하면 차단은 건너뛴다(크래시 방지)
        } catch (RuntimeException e) {
            return;
        }
        BlockedPlayersScreen.blockNow(uuid, name);
    }

    private ReportScreen(Screen parent, String uuid, String name, String code, String title) {
        //? if >=26.1 {
        /*super(Component.translatable("instant-p2p.report.title"));
        *///?} else {
        super(Text.translatable("instant-p2p.report.title"));
        //?}
        this.parent = parent;
        this.roomCode = code;
        this.roomTitle = title;
        this.targetUuid = uuid;
        this.targetName = name != null && !name.isEmpty() ? name : uuid.substring(0, Math.min(8, uuid.length()));
    }

    private int baseY() {
        return this.height / 2 - 40;
    }

    private String categoryKey() {
        return "instant-p2p.report.category." + (this.roomCode != null ? "title" : CATEGORIES[this.category]);
    }

    private void send() {
        if (this.roomCode != null) ReportClient.submitRoom(this.targetUuid, this.roomCode, this.roomTitle, this.text);
        else ReportClient.submit(this.targetUuid, CATEGORIES[this.category], this.text);
        autoBlock(this.targetUuid, this.targetName);
        this.close0();
    }

    //? if >=26.1 {
    /*private static void show(Screen s) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(s);
    }

    private Component categoryLabel() {
        return Component.translatable("instant-p2p.report.category", Component.translatable(this.categoryKey()));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.baseY();
        this.addRenderableWidget(Button.builder(this.categoryLabel(), b -> {
            if (this.roomCode == null) this.category = (this.category + 1) % CATEGORIES.length;
            b.setMessage(this.categoryLabel());
        }).bounds(cx - 100, y, 200, 20).build());
        EditBox box = new EditBox(this.font, cx - 100, y + 26, 200, 20, Component.translatable("instant-p2p.report.text_hint"));
        box.setMaxLength(200);
        box.setHint(Component.translatable("instant-p2p.report.text_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(this.text);
        box.setResponder(t -> this.text = t);
        this.addRenderableWidget(box);
        this.addRenderableWidget(Button.builder(Component.translatable("instant-p2p.report.send"), b -> this.send())
                .bounds(cx - 100, y + 84, 98, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("instant-p2p.report.cancel"), b -> this.onClose())
                .bounds(cx + 2, y + 84, 98, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;
        int y = this.baseY();
        context.centeredText(this.font, this.title, cx, y - 34, 0xFFFFFFFF);
        context.centeredText(this.font, Component.translatable("instant-p2p.report.target", this.targetName), cx, y - 20, 0xFFFFFF55);
        context.centeredText(this.font, Component.translatable(this.roomCode != null ? "instant-p2p.report.room_note" : "instant-p2p.report.context_note"),
                cx, y + 56, 0xFFA0A0A0);
        context.centeredText(this.font, Component.translatable("instant-p2p.report.context_note2"), cx, y + 68, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        this.close0();
    }

    private void close0() {
        Objects.requireNonNull(this.minecraft).setScreenAndShow(this.parent);
    }
    *///?} else {
    private static void show(Screen s) {
        net.minecraft.client.MinecraftClient.getInstance().setScreen(s);
    }

    private Text categoryLabel() {
        return Text.translatable("instant-p2p.report.category", Text.translatable(this.categoryKey()));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.baseY();
        this.addDrawableChild(ButtonWidget.builder(this.categoryLabel(), b -> {
            if (this.roomCode == null) this.category = (this.category + 1) % CATEGORIES.length;
            b.setMessage(this.categoryLabel());
        }).dimensions(cx - 100, y, 200, 20).build());
        TextFieldWidget box = new TextFieldWidget(this.textRenderer, cx - 100, y + 26, 200, 20, Text.translatable("instant-p2p.report.text_hint"));
        box.setMaxLength(200);
        box.setPlaceholder(Text.translatable("instant-p2p.report.text_hint").formatted(Formatting.DARK_GRAY));
        box.setText(this.text);
        box.setChangedListener(t -> this.text = t);
        this.addDrawableChild(box);
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("instant-p2p.report.send"), b -> this.send())
                .dimensions(cx - 100, y + 84, 98, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("instant-p2p.report.cancel"), b -> this.close())
                .dimensions(cx + 2, y + 84, 98, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;
        int y = this.baseY();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, y - 34, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("instant-p2p.report.target", this.targetName), cx, y - 20, 0xFFFFFF55);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(this.roomCode != null ? "instant-p2p.report.room_note" : "instant-p2p.report.context_note"),
                cx, y + 56, 0xFFA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("instant-p2p.report.context_note2"), cx, y + 68, 0xFFA0A0A0);
    }

    @Override
    public void close() {
        this.close0();
    }

    private void close0() {
        Objects.requireNonNull(this.client).setScreen(this.parent);
    }
    //?}
}
