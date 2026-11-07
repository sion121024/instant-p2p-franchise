package kfc.udp.client.signaling;

import kfc.udp.client.KfcudpClient;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.network.ServerPlayerEntity;
//?}

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 방장 쪽 채팅 지킴이(1.5~, ChatGuardMixin·ChatGuardCommandMixin 이 부른다).
 * <ul>
 *   <li><b>도배 강퇴</b> — 1초에 5번 이상 채팅(또는 /msg 류)을 보내면 내보낸다.</li>
 *   <li><b>공개 방 1초 1메시지</b> — 그보다 빠른 채팅은 전달하지 않고 본인에게만 알린다.</li>
 *   <li><b>안전 필터</b> — ChatShield 에 걸리면 전달하지 않고, 방장에게 알리고, 서명 증거와 함께 자동 신고한다.</li>
 *   <li><b>승인 대기 중</b>(AdmitGate)이면 채팅·명령을 못 쓴다.</li>
 * </ul>
 * 방장 자신의 채팅에는 걸지 않는다.
 */
public final class ChatGuard {

    public static final int FLOOD_LIMIT = 5;
    public static final long FLOOD_WINDOW_MS = 1_000;
    public static final long MIN_GAP_MS = 1_000;

    private static final Map<UUID, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastDelivered = new ConcurrentHashMap<>();
    private static volatile MinecraftServer server;
    /** 채팅과 같은 효과가 있는 명령 — 도배·필터를 같이 건다. */
    private static final Set<String> CHAT_LIKE = Set.of("me", "msg", "tell", "w", "teammsg", "tm", "say");

    private ChatGuard() {}

    public static void noteServer(MinecraftServer s) {
        server = s;
    }

    /** 1초 창 안의 시도 수가 상한에 닿았는지(시도 자체를 센다 — 전달 여부와 무관). */
    static boolean flood(UUID id, long now) {
        ArrayDeque<Long> q = attempts.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= FLOOD_WINDOW_MS) q.pollFirst();
            q.addLast(now);
            return q.size() >= FLOOD_LIMIT;
        }
    }

    /** 마지막으로 전달한 지 MIN_GAP_MS 가 지났으면 전달(시각 갱신). */
    static boolean allow(UUID id, long now) {
        Long last = lastDelivered.get(id);
        if (last != null && now - last < MIN_GAP_MS) return false;
        lastDelivered.put(id, now);
        return true;
    }

    public static void forget(UUID id) {
        attempts.remove(id);
        lastDelivered.remove(id);
    }

    private static boolean isChatLike(String command) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        int sp = c.indexOf(' ');
        String head = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (head.startsWith("minecraft:")) head = head.substring("minecraft:".length());
        return CHAT_LIKE.contains(head);
    }

    /** 채팅류 명령에서 실제 말 부분 — /msg 이름 말 이면 "말", /say 말 이면 "말". */
    private static String commandText(String command) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = c.split(" ", 3);
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (head.startsWith("minecraft:")) head = head.substring("minecraft:".length());
        boolean toPlayer = head.equals("msg") || head.equals("tell") || head.equals("w");
        if (toPlayer) return parts.length > 2 ? parts[2] : "";
        return c.contains(" ") ? c.substring(c.indexOf(' ') + 1) : "";
    }

    /** 승인 대기 중인 사람은 어차피 말을 못 하므로 필터 대상이 아니다. */
    static String shieldHit(UUID id, String text) {
        return applies(id) && !AdmitGate.isPending(id) ? ChatShield.check(text) : null;
    }

    private static boolean applies(UUID id) {
        return KfcudpClient.isRoomActive() && !id.equals(KfcudpClient.currentHostUuid());
    }

    //? if >=26.1 {
    /*private static UUID idOf(ServerPlayer p) {
        return p.getUUID();
    }

    // 채팅 패킷이 왔다 — 도배면 내보내고 true(패킷을 버린다).
    public static boolean onPacket(ServerPlayer p) {
        UUID id = idOf(p);
        if (applies(id) && flood(id, System.currentTimeMillis())) {
            forget(id);
            P2PBanManager.kickFb(p, "msg.chat_flood", "§cYou were removed for sending chat messages too fast.");
            return true;
        }
        return false;
    }

    // @return 명령을 실행해도 되면 true
    public static boolean allowCommand(ServerPlayer p, String command) {
        if (AdmitGate.isPending(idOf(p))) {
            P2PBanManager.overlayFb(p, "msg.cmd_pending", "§eYou can use commands once the host admits you.");
            return false;
        }
        if (!isChatLike(command)) return true;
        UUID id = idOf(p);
        if (applies(id) && flood(id, System.currentTimeMillis())) {
            forget(id);
            P2PBanManager.kickFb(p, "msg.chat_flood", "§cYou were removed for sending chat messages too fast.");
            return false;
        }
        String hit = shieldHit(id, commandText(command));
        if (hit != null) {
            shieldDrop(p, hit, null);
            return false;
        }
        return allowDelivery(p);
    }

    // @return 이 채팅을 모두에게 전달해도 되면 true
    public static boolean allowChat(ServerPlayer p, String text, Object message) {
        String hit = shieldHit(idOf(p), text);
        if (hit != null) {
            shieldDrop(p, hit, message);
            return false;
        }
        return allowDelivery(p);
    }

    private static void shieldDrop(ServerPlayer p, String hit, Object message) {
        boolean child = ChatShield.CHILD.equals(hit);
        P2PBanManager.overlayFb(p, child ? "msg.shield_child" : "msg.shield_illegal",
                child ? "§cThis message was not delivered (child-safety rule)." : "§cThis message was not delivered (illegal/scam rule).");
        P2PBanManager.tellHostFb(server, child ? "msg.shield_host_child" : "msg.shield_host_illegal",
                child ? "§7[Safety] Did not deliver a message from %s (child safety)" : "§7[Safety] Did not deliver a message from %s (illegal/scam)",
                P2PBanManager.profileName(p.getGameProfile()));
        ReportClient.submitShield(idOf(p), message, p.getGameProfile());
    }

    public static boolean allowDelivery(ServerPlayer p) {
        UUID id = idOf(p);
        if (AdmitGate.isPending(id)) {
            P2PBanManager.overlayFb(p, "msg.chat_pending", "§eYou can chat once the host admits you.");
            return false;
        }
        if (applies(id) && KfcudpClient.isActivePublicRoom() && !allow(id, System.currentTimeMillis())) {
            P2PBanManager.overlayFb(p, "msg.chat_slow", "§eYou can send one chat message per second here.");
            return false;
        }
        return true;
    }
    *///?} else {
    private static UUID idOf(ServerPlayerEntity p) {
        return p.getUuid();
    }

    /** 채팅 패킷이 왔다 — 도배면 내보내고 true(패킷을 버린다). */
    public static boolean onPacket(ServerPlayerEntity p) {
        UUID id = idOf(p);
        if (applies(id) && flood(id, System.currentTimeMillis())) {
            forget(id);
            P2PBanManager.kickFb(p, "msg.chat_flood", "§cYou were removed for sending chat messages too fast.");
            return true;
        }
        return false;
    }

    /** @return 명령을 실행해도 되면 true */
    public static boolean allowCommand(ServerPlayerEntity p, String command) {
        if (AdmitGate.isPending(idOf(p))) {
            P2PBanManager.overlayFb(p, "msg.cmd_pending", "§eYou can use commands once the host admits you.");
            return false;
        }
        if (!isChatLike(command)) return true;
        UUID id = idOf(p);
        if (applies(id) && flood(id, System.currentTimeMillis())) {
            forget(id);
            P2PBanManager.kickFb(p, "msg.chat_flood", "§cYou were removed for sending chat messages too fast.");
            return false;
        }
        String hit = shieldHit(id, commandText(command));
        if (hit != null) {
            shieldDrop(p, hit, null);
            return false;
        }
        return allowDelivery(p);
    }

    /** @return 이 채팅을 모두에게 전달해도 되면 true */
    public static boolean allowChat(ServerPlayerEntity p, String text, Object message) {
        String hit = shieldHit(idOf(p), text);
        if (hit != null) {
            shieldDrop(p, hit, message);
            return false;
        }
        return allowDelivery(p);
    }

    private static void shieldDrop(ServerPlayerEntity p, String hit, Object message) {
        boolean child = ChatShield.CHILD.equals(hit);
        P2PBanManager.overlayFb(p, child ? "msg.shield_child" : "msg.shield_illegal",
                child ? "§cThis message was not delivered (child-safety rule)." : "§cThis message was not delivered (illegal/scam rule).");
        P2PBanManager.tellHostFb(server, child ? "msg.shield_host_child" : "msg.shield_host_illegal",
                child ? "§7[Safety] Did not deliver a message from %s (child safety)" : "§7[Safety] Did not deliver a message from %s (illegal/scam)",
                P2PBanManager.profileName(p.getGameProfile()));
        ReportClient.submitShield(idOf(p), message, p.getGameProfile());
    }

    public static boolean allowDelivery(ServerPlayerEntity p) {
        UUID id = idOf(p);
        if (AdmitGate.isPending(id)) {
            P2PBanManager.overlayFb(p, "msg.chat_pending", "§eYou can chat once the host admits you.");
            return false;
        }
        if (applies(id) && KfcudpClient.isActivePublicRoom() && !allow(id, System.currentTimeMillis())) {
            P2PBanManager.overlayFb(p, "msg.chat_slow", "§eYou can send one chat message per second here.");
            return false;
        }
        return true;
    }
    //?}
}
