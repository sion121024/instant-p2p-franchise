package kfc.udp.client.gui;

import kfc.udp.client.signaling.BanStatus;
import kfc.udp.client.signaling.P2PConfig;
//? if >=26.1 {
/*import net.minecraft.client.gui.screens.Screen;
*///?} else {
import net.minecraft.client.gui.screen.Screen;
//?}

/**
 * 가맹점(사설 서버)에서 막혔을 때 화면 가운데에 빨갛게 띄우는 안내 — 어떤 제한인지(계정·기기·IP), 사유,
 * 시간제면 남은 시간을 초 단위로 보여 준다. 공지·약관과 같은 알림창 모양(PanelScreen)이다.
 * <p>
 * 남은 시간은 서버가 알려 준 해제 시각에서 매 프레임 다시 계산한다. 0이 되면 "끝났다"로 바뀌고,
 * 그다음 확인(BanStatus.poll) 때 서버 기준으로도 풀린다.
 */
public final class BannedScreen extends PanelScreen {

    private final BanStatus.Info info;

    public BannedScreen(Screen parent, BanStatus.Info info) {
        super(parent,
                tr("instant-p2p.ban.heading", tr("instant-p2p.ban.kind." + kindKey(info.kind()))),
                "",
                null,
                tr("instant-p2p.ban.to_official"),
                tr("instant-p2p.ban.close"),
                0xFFFF5555, 0xFF1A0000,
                () -> {
                    P2PConfig.setServerProfile(P2PConfig.ServerProfile.OFFICIAL);
                    BanStatus.reset();
                },
                null);
        this.info = info;
    }

    private static String kindKey(String kind) {
        return switch (kind == null ? "" : kind) {
            case "account", "hwid", "ip" -> kind;
            default -> "unknown";
        };
    }

    @Override
    public void tick() {
        super.tick();
        BanStatus.poll(); // 운영자가 풀면 다음 확인(15초 안)에 "끝났다"로 바뀐다
    }

    @Override
    protected String bodyText() {
        StringBuilder b = new StringBuilder();
        boolean lifted = !BanStatus.current().banned();
        if (this.info.reason() != null && !this.info.reason().isBlank()) {
            b.append(tr("instant-p2p.ban.reason", this.info.reason())).append('\n');
        }
        long ms = this.info.remainingMs();
        if (lifted) {
            b.append(tr("instant-p2p.ban.expired"));
        } else if (ms < 0) {
            b.append(tr("instant-p2p.ban.permanent"));
        } else if (ms == 0) {
            b.append(tr("instant-p2p.ban.expired"));
        } else {
            long sec = (ms + 999) / 1000;
            b.append(tr("instant-p2p.ban.remaining", formatDuration(sec), sec));
        }
        return b.append("\n\n").append(tr("instant-p2p.ban.help")).toString();
    }

    /** 93784 → "1일 02:03:04", 하루 미만이면 "02:03:04". */
    static String formatDuration(long sec) {
        long d = sec / 86400, h = sec % 86400 / 3600, m = sec % 3600 / 60, s = sec % 60;
        String hms = String.format("%02d:%02d:%02d", h, m, s);
        return d > 0 ? tr("instant-p2p.ban.days", d) + " " + hms : hms;
    }
}
