package kfc.udp.client.gui;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 알림창 스타일(SafetyWarningScreen·ConfirmPopup과 같은 모양 — 어둡게 깐 화면 위 테두리 박스 하나)의
 * 범용 패널. 본문이 길면 마우스 휠·방향키로 스크롤한다. 두 곳에서 쓴다:
 * <ul>
 *   <li>공지 — 서버 운영자가 올린 공지를 방 목록을 열 때 한 번 띄운다(확인 버튼 하나).</li>
 *   <li>가맹점 이용약관 — 맨 아래 동의 체크를 해야 "동의하고 계속"이 눌린다.</li>
 * </ul>
 * 위젯을 안 쓰고 손으로 그린다(SafetyWarningScreen 클래스 주석과 같은 이유).
 */
public class PanelScreen extends Screen {

    private static final int PAD = 10, LINE_H = 11, BTN_W = 110, BTN_H = 20, BTN_GAP = 8;
    private static final int BOX_SIZE = 17, BOX_LABEL_GAP = 4;
    private static final int DIM_COLOR = 0xC0000000;
    //? if >=26.1 {
    /*private static final int KEY_ENTER = com.mojang.blaze3d.platform.InputConstants.KEY_RETURN,
            KEY_KP_ENTER = com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER,
            KEY_ESCAPE = com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE,
            KEY_UP = com.mojang.blaze3d.platform.InputConstants.KEY_UP,
            KEY_DOWN = com.mojang.blaze3d.platform.InputConstants.KEY_DOWN,
            KEY_PAGE_UP = com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP,
            KEY_PAGE_DOWN = com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN;
    *///?} else {
    private static final int KEY_ENTER = 257, KEY_KP_ENTER = 335, KEY_ESCAPE = 256,
            KEY_UP = 265, KEY_DOWN = 264, KEY_PAGE_UP = 266, KEY_PAGE_DOWN = 267;
    //?}

    private final Screen parent;
    protected final String heading;
    private final String body;
    private final String checkboxLabel; // null이면 체크박스 없음
    private final String acceptLabel;
    private final String cancelLabel;   // null이면 버튼 하나(확인)
    private final int accentColor, bgColor;
    private final Runnable onAccept;
    private final Runnable onCancel;

    private boolean checked;
    private int scroll;

    // 매 프레임 계산해 두는 배치 — 클릭 판정에 쓴다.
    private List<String> lines = List.of();
    private int wrappedForWidth = -1;
    private String wrappedText = null;
    private int visibleLines = 1;
    private int acceptX = -1, cancelX = -1, btnY = -1, boxX = -1, boxY = -1, boxW = 0;

    /**
     * @param onAccept 확인/동의 — 부모 화면으로 돌아간 <b>다음에</b> 실행된다(null 가능)
     * @param onCancel 취소·ESC — 부모 화면으로 돌아간 다음에 실행된다(null 가능)
     */
    public PanelScreen(Screen parent, String heading, String body, String checkboxLabel,
                       String acceptLabel, String cancelLabel, int accentColor, int bgColor,
                       Runnable onAccept, Runnable onCancel) {
        //? if >=26.1 {
        /*super(Component.literal(heading));
        *///?} else {
        super(Text.literal(heading));
        //?}
        this.parent = parent;
        this.heading = heading;
        this.body = body == null ? "" : body;
        this.checkboxLabel = checkboxLabel;
        this.acceptLabel = acceptLabel;
        this.cancelLabel = cancelLabel;
        this.accentColor = accentColor;
        this.bgColor = bgColor;
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    /** 번역 키로 만들기 — 본문의 "\n"은 줄바꿈. */
    public static String tr(String key, Object... args) {
        //? if >=26.1 {
        /*return Component.translatable(key, args).getString();
        *///?} else {
        return Text.translatable(key, args).getString();
        //?}
    }

    /** 본문 — 매 프레임 불린다. 바뀌면 다시 줄바꿈한다(밴 화면의 남은 시간처럼 계속 바뀌는 글). */
    protected String bodyText() {
        return this.body;
    }

    private boolean canAccept() {
        return this.checkboxLabel == null || this.checked;
    }

    private void back() {
        //? if >=26.1 {
        /*Objects.requireNonNull(this.minecraft).setScreenAndShow(this.parent);
        *///?} else {
        Objects.requireNonNull(this.client).setScreen(this.parent);
        //?}
    }

    private void accept() {
        if (!this.canAccept()) return;
        this.back();
        if (this.onAccept != null) this.onAccept.run();
    }

    private void cancel() {
        this.back();
        if (this.onCancel != null) this.onCancel.run();
    }

    // ---- 배치 (버전 무관) ----

    private int textW(String s) {
        //? if >=26.1 {
        /*return this.font.width(s);
        *///?} else {
        return this.textRenderer.getWidth(s);
        //?}
    }

    /** 폭에 맞게 줄바꿈 — 공백이 있으면 공백에서, 없으면(한글 긴 단어) 글자 단위로 자른다. */
    private List<String> wrap(String text, int maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder cur = new StringBuilder();
            int lastSpace = -1;
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                cur.append(ch);
                if (ch == ' ') lastSpace = cur.length() - 1;
                if (this.textW(cur.toString()) > maxW && cur.length() > 1) {
                    String line;
                    if (lastSpace > 0) {
                        line = cur.substring(0, lastSpace);
                        String rest = cur.substring(lastSpace + 1);
                        cur.setLength(0);
                        cur.append(rest);
                    } else {
                        line = cur.substring(0, cur.length() - 1);
                        cur.setLength(0);
                        cur.append(ch);
                    }
                    out.add(line);
                    lastSpace = -1;
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    private int panelW() {
        return Math.min(this.width - 32, 440);
    }

    private int panelH() {
        return Math.max(150, Math.min(this.height - 32, 360));
    }

    /** 배치를 계산하고, 그릴 본문 영역 [top, bottom)을 돌려준다. */
    private int[] layout() {
        int pw = this.panelW(), ph = this.panelH();
        int px = (this.width - pw) / 2, py = (this.height - ph) / 2;
        int textX = px + PAD, textRight = px + pw - PAD - 6; // 오른쪽 6px = 스크롤바 자리
        int textWidth = textRight - textX;
        String text = this.bodyText();
        if (this.wrappedForWidth != textWidth || !text.equals(this.wrappedText)) {
            this.lines = this.wrap(text, textWidth);
            this.wrappedForWidth = textWidth;
            this.wrappedText = text;
        }
        this.btnY = py + ph - PAD - BTN_H;
        int textTop = py + PAD + 16;
        int textBottom = this.btnY - 8;
        if (this.checkboxLabel != null) {
            this.boxW = BOX_SIZE + BOX_LABEL_GAP + this.textW(this.checkboxLabel);
            this.boxX = px + (pw - this.boxW) / 2;
            this.boxY = this.btnY - 8 - BOX_SIZE;
            textBottom = this.boxY - 8;
        }
        this.visibleLines = Math.max(1, (textBottom - textTop) / LINE_H);
        this.scroll = Math.max(0, Math.min(this.scroll, this.maxScroll()));
        if (this.cancelLabel != null) {
            this.acceptX = this.width / 2 - BTN_GAP / 2 - BTN_W;
            this.cancelX = this.width / 2 + BTN_GAP / 2;
        } else {
            this.acceptX = (this.width - BTN_W) / 2;
            this.cancelX = -1;
        }
        return new int[]{px, py, pw, ph, textX, textTop, textWidth};
    }

    private int maxScroll() {
        return Math.max(0, this.lines.size() - this.visibleLines);
    }

    private void scrollBy(int d) {
        this.scroll = Math.max(0, Math.min(this.maxScroll(), this.scroll + d));
    }

    // ---- 입력 ----

    private boolean hitBtn(int bx, double mx, double my) {
        return bx >= 0 && mx >= bx && mx < bx + BTN_W && my >= this.btnY && my < this.btnY + BTN_H;
    }

    private void handleClick(double mx, double my) {
        if (this.checkboxLabel != null && mx >= this.boxX && mx < this.boxX + this.boxW
                && my >= this.boxY && my < this.boxY + BOX_SIZE) {
            this.checked = !this.checked;
            RoomListScreen.playClick();
        } else if (this.hitBtn(this.acceptX, mx, my)) {
            if (this.canAccept()) {
                RoomListScreen.playClick();
                this.accept();
            }
        } else if (this.hitBtn(this.cancelX, mx, my)) {
            RoomListScreen.playClick();
            this.cancel();
        }
    }

    private boolean handleKey(int key) {
        if (key == KEY_ENTER || key == KEY_KP_ENTER) { this.accept(); return true; }
        if (key == KEY_ESCAPE) { this.cancel(); return true; }
        if (key == KEY_UP) { this.scrollBy(-1); return true; }
        if (key == KEY_DOWN) { this.scrollBy(1); return true; }
        if (key == KEY_PAGE_UP) { this.scrollBy(-this.visibleLines); return true; }
        if (key == KEY_PAGE_DOWN) { this.scrollBy(this.visibleLines); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        this.scrollBy(verticalAmount > 0 ? -3 : verticalAmount < 0 ? 3 : 0);
        return true;
    }

    //? if >=26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        return this.handleKey(input.key()) || super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) this.handleClick(click.x(), click.y());
        return true;
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return this.handleKey(input.key()) || super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
        if (click.button() == 0) this.handleClick(click.x(), click.y());
        return true;
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return this.handleKey(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) this.handleClick(mouseX, mouseY);
        return true;
    }
    //?}

    // ---- 렌더 ----

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(ctx, mouseX, mouseY, deltaTicks);
        int[] l = this.layout();
        int px = l[0], py = l[1], pw = l[2], ph = l[3], textX = l[4], textTop = l[5], textWidth = l[6];
        ctx.fill(0, 0, this.width, this.height, DIM_COLOR);
        ctx.fill(px, py, px + pw, py + ph, this.accentColor);
        ctx.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, this.bgColor);
        ctx.text(this.font, "§l" + this.heading, px + (pw - this.textW("§l" + this.heading)) / 2, py + PAD, this.accentColor);
        for (int i = 0; i < this.visibleLines && this.scroll + i < this.lines.size(); i++) {
            ctx.text(this.font, this.lines.get(this.scroll + i), textX, textTop + i * LINE_H, 0xFFE0E0E0);
        }
        this.drawScrollbar(ctx, textX + textWidth + 3, textTop);
        if (this.checkboxLabel != null) {
            RoomListScreen.drawSprite(ctx, this.checked ? "widget/checkbox_selected" : "widget/checkbox", this.boxX, this.boxY, BOX_SIZE, BOX_SIZE);
            ctx.text(this.font, this.checkboxLabel, this.boxX + BOX_SIZE + BOX_LABEL_GAP, this.boxY + (BOX_SIZE - 9) / 2, 0xFFFFFFFF);
        }
        this.button(ctx, this.acceptX, this.acceptLabel, this.canAccept(), mouseX, mouseY);
        if (this.cancelLabel != null) this.button(ctx, this.cancelX, this.cancelLabel, true, mouseX, mouseY);
    }

    private void drawScrollbar(GuiGraphicsExtractor ctx, int x, int top) {
        int max = this.maxScroll();
        if (max <= 0) return;
        int h = this.visibleLines * LINE_H;
        int thumb = Math.max(12, h * this.visibleLines / this.lines.size());
        int y = top + (h - thumb) * this.scroll / max;
        ctx.fill(x, top, x + 3, top + h, 0x40FFFFFF);
        ctx.fill(x, y, x + 3, y + thumb, this.accentColor);
    }

    private void button(GuiGraphicsExtractor ctx, int bx, String label, boolean enabled, int mx, int my) {
        String sprite = !enabled ? "widget/button_disabled" : this.hitBtn(bx, mx, my) ? "widget/button_highlighted" : "widget/button";
        RoomListScreen.drawSprite(ctx, sprite, bx, this.btnY, BTN_W, BTN_H);
        ctx.text(this.font, label, bx + (BTN_W - this.textW(label)) / 2, this.btnY + (BTN_H - 8) / 2, enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        this.cancel();
    }
    *///?} else {
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        super.render(ctx, mouseX, mouseY, deltaTicks);
        int[] l = this.layout();
        int px = l[0], py = l[1], pw = l[2], ph = l[3], textX = l[4], textTop = l[5], textWidth = l[6];
        //? if >=1.21.6 {
        /*ctx.createNewRootLayer();
        *///?} else {
        ctx.draw();
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, 0f, 400f);
        //?}
        ctx.fill(0, 0, this.width, this.height, DIM_COLOR);
        ctx.fill(px, py, px + pw, py + ph, this.accentColor);
        ctx.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, this.bgColor);
        ctx.drawTextWithShadow(this.textRenderer, "§l" + this.heading, px + (pw - this.textW("§l" + this.heading)) / 2, py + PAD, this.accentColor);
        for (int i = 0; i < this.visibleLines && this.scroll + i < this.lines.size(); i++) {
            ctx.drawTextWithShadow(this.textRenderer, this.lines.get(this.scroll + i), textX, textTop + i * LINE_H, 0xFFE0E0E0);
        }
        this.drawScrollbar(ctx, textX + textWidth + 3, textTop);
        if (this.checkboxLabel != null) {
            RoomListScreen.drawSprite(ctx, this.checked ? "widget/checkbox_selected" : "widget/checkbox", this.boxX, this.boxY, BOX_SIZE, BOX_SIZE);
            ctx.drawTextWithShadow(this.textRenderer, this.checkboxLabel, this.boxX + BOX_SIZE + BOX_LABEL_GAP, this.boxY + (BOX_SIZE - 9) / 2, 0xFFFFFFFF);
        }
        this.button(ctx, this.acceptX, this.acceptLabel, this.canAccept(), mouseX, mouseY);
        if (this.cancelLabel != null) this.button(ctx, this.cancelX, this.cancelLabel, true, mouseX, mouseY);
        //? if <1.21.6 {
        ctx.getMatrices().pop();
        //?}
    }

    private void drawScrollbar(DrawContext ctx, int x, int top) {
        int max = this.maxScroll();
        if (max <= 0) return;
        int h = this.visibleLines * LINE_H;
        int thumb = Math.max(12, h * this.visibleLines / this.lines.size());
        int y = top + (h - thumb) * this.scroll / max;
        ctx.fill(x, top, x + 3, top + h, 0x40FFFFFF);
        ctx.fill(x, y, x + 3, y + thumb, this.accentColor);
    }

    private void button(DrawContext ctx, int bx, String label, boolean enabled, int mx, int my) {
        String sprite = !enabled ? "widget/button_disabled" : this.hitBtn(bx, mx, my) ? "widget/button_highlighted" : "widget/button";
        RoomListScreen.drawSprite(ctx, sprite, bx, this.btnY, BTN_W, BTN_H);
        ctx.drawTextWithShadow(this.textRenderer, label, bx + (BTN_W - this.textW(label)) / 2, this.btnY + (BTN_H - 8) / 2, enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    @Override
    public void close() {
        this.cancel();
    }
    //?}
}
