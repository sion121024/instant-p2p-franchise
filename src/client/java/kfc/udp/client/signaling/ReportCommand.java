package kfc.udp.client.signaling;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.Locale;
import java.util.Set;

// 26.x Fabric API에서 ClientCommandManager가 ClientCommands로 이름이 바뀌었다.
//? if >=26.1 {
/*import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
*///?} else {
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
//?}

/**
 * {@code /report <player> [분류] <내용...>} — 신고 화면(ReportScreen)과 같은 신고를 명령으로 보낸다(F3.5: 1.5 신고 체계로
 * 옮겼다 — 실제 전송·중복 막기·1분 모으기·채팅/표지판/책 첨부는 전부 {@link ReportClient#submit}).
 * <p>
 * 내용의 첫 단어가 분류 코드({@link ReportClient#CATEGORIES} — 원본과 같은 harass, behavior, money, illegal, other)면 그 분류로, 아니면 "other"(기타)로 보낸다. 대상은 지금 접속자나 최근 7일 안에 같은 방에
 * 있었던 사람(SessionRoster)에서 이름으로 찾는다. 신고 화면과 똑같이 신고한 사람은 자동으로 차단된다.
 * <p>
 * <b>클라이언트 전용 명령</b>이다(브리가디어 서버 디스패처를 타지 않는다) — 방장이 명령어를 막아도 항상 쓸 수 있다.
 */
public final class ReportCommand {

    private ReportCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("report")
                        .then(argument("player", StringArgumentType.word())
                                .then(argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> execute(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "reason")))))));
    }

    private static int execute(FabricClientCommandSource src, String targetName, String reason) {
        reason = reason.strip();
        if (reason.isEmpty()) {
            src.sendError(P2PBanManager.msg("§cPlease enter a reason."));
            return 0;
        }
        // 첫 단어가 분류 코드면 분류로 쓴다 — "/report 이름 spam 계속 광고함"
        String category = "other";
        int sp = reason.indexOf(' ');
        String head = (sp < 0 ? reason : reason.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (ReportClient.isCategory(head)) {
            category = head;
            reason = sp < 0 ? "" : reason.substring(sp + 1).strip();
        }
        if (reason.length() > 200) reason = reason.substring(0, 200); // 신고 화면 입력칸과 같은 상한

        P2PBanManager.BannedEntry target = findPlayer(targetName);
        if (target == null) {
            src.sendError(P2PBanManager.msg("Could not find a player named " + targetName));
            return 0;
        }
        if (!ReportClient.canReport(target.uuid())) {
            ReportClient.tellNoRoom();
            return 0;
        }
        ReportClient.submit(target.uuid(), category, reason); // 결과(접수·중복·실패)는 ReportClient 가 채팅으로 알린다
        kfc.udp.client.gui.ReportScreen.autoBlock(target.uuid(), target.name());
        return 1;
    }

    /** 지금 접속자 → 최근 같이 있었던 사람 순으로 이름(대소문자 무시)으로 찾는다. */
    private static P2PBanManager.BannedEntry findPlayer(String name) {
        for (P2PBanManager.BannedEntry e : SessionRoster.online()) {
            if (e.name().equalsIgnoreCase(name)) return e;
        }
        for (P2PBanManager.BannedEntry e : SessionRoster.offline(Set.of())) {
            if (e.name().equalsIgnoreCase(name)) return e;
        }
        return null;
    }
}
