package kfc.udp.client.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kfc.udp.client.KfcudpClient;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
*///?} else {
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 입장 승인제(1.5~) — 방 설정에서 켜면(P2PConfig.isApprovalGate) 처음 보는 사람은 방장이 {@code /admit <이름>} 할
 * 때까지 <b>관전자로 제자리에 묶인다</b>. 그동안
 * <ul>
 *   <li>투명하고 탭 목록에서도 다른 사람에게 안 보인다(방장에게만 보인다).</li>
 *   <li>움직이거나 다른 사람 시점으로 들어갈 수 없다(매 틱 처음 자리로 되돌린다).</li>
 *   <li>채팅·명령을 쓸 수 없다(ChatGuard).</li>
 *   <li>참여 메시지는 방장에게만 [승인]/[거절] 버튼과 함께 간다(PlayerJoinMessageMixin) — 승인하면 그때 모두에게 나간다.</li>
 * </ul>
 * 한 번 승인한 사람은 config/instant-p2p/admitted.json 에 남아 다음부터 바로 들어온다({@code /unadmit} 으로 뺀다).
 * OP·화이트리스트 명단에 있는 사람도 바로 들어온다. 10분 동안 승인이 없으면 내보낸다.
 * <p>
 * 공개 방에서 낯선 사람이 들어오자마자 부수거나 채팅으로 도배하는 걸 막으려는 기능이다.
 */
public final class AdmitGate {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p");
    private static final Path FILE = Path.of("config", "instant-p2p", "admitted.json");
    /** /admit 명령으로 넘길 수 있는 이름만 붙잡는다 — 아니면 승인할 방법이 없어 그냥 내보낸다. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final long PENDING_TTL_MS = 600_000;

    /** 승인을 기다리는 사람 — 붙잡은 자리(되돌릴 위치·시선)와 시각. */
    private record Pending(String name, double x, double y, double z, float yaw, float pitch, long at) {}

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** 승인한 사람(소문자 UUID → 이름) — admitted.json. */
    private static final Map<String, String> trusted = new LinkedHashMap<>();
    /** admitted.json 을 마지막으로 읽었을 때의 수정 시각 — 파일을 손으로 고쳐도 다음 판정부터 반영된다. */
    private static long loadedStamp = Long.MIN_VALUE;
    /** 들어온 지 몇 틱 뒤에 탭 목록을 다시 맞출지(입장 패킷이 다 나간 뒤에 숨겨야 먹는다). */
    private static final Map<UUID, Integer> hideQueue = new ConcurrentHashMap<>();
    /** 붙잡아 둔 참여 메시지 — 승인하면 그때 모두에게 보낸다. */
    private static final Map<UUID, Object> heldJoin = new ConcurrentHashMap<>();

    private AdmitGate() {}

    private static long stamp() {
        try {
            return Files.getLastModifiedTime(FILE).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    public static synchronized void load() {
        trusted.clear();
        loadedStamp = stamp();
        JsonArray arr = JsonFiles.readArray(FILE);
        if (arr == null) return;
        for (JsonElement e : arr) {
            try {
                JsonObject o = e.getAsJsonObject();
                String uuid = o.get("uuid").getAsString().toLowerCase(Locale.ROOT);
                UUID.fromString(uuid); // 형식 확인
                trusted.put(uuid, o.has("name") ? o.get("name").getAsString() : "?");
            } catch (Exception ex) {
                LOG.warn("[instant-p2p] skipped a bad admitted.json entry");
            }
        }
    }

    private static synchronized void save() {
        JsonArray arr = new JsonArray();
        trusted.forEach((u, n) -> {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", u);
            o.addProperty("name", n);
            arr.add(o);
        });
        JsonFiles.writeAtomic(FILE, arr);
        loadedStamp = stamp();
    }

    private static synchronized boolean isTrusted(UUID id) {
        if (stamp() != loadedStamp) load();
        return trusted.containsKey(id.toString().toLowerCase(Locale.ROOT));
    }

    public static boolean isPending(UUID id) {
        return pending.containsKey(id);
    }

    public static List<String> pendingNames() {
        List<String> out = new ArrayList<>();
        pending.values().forEach(p -> out.add(p.name()));
        return out;
    }

    /** 방을 닫을 때 — 붙잡아 둔 사람을 전부 풀어 원래 게임 모드로 돌린다. */
    public static void reset(MinecraftServer server) {
        for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
            setHidden(server, e.getKey(), false);
            restoreMode(server, e.getValue().name());
        }
        pending.clear();
        heldJoin.clear();
    }

    public static void onLeave(MinecraftServer server, UUID id) {
        Pending p = pending.remove(id);
        heldJoin.remove(id);
        if (p != null) {
            setHidden(server, id, false);
            restoreMode(server, p.name()); // 다음 접속 때 관전자로 남아 있지 않게
        }
    }

    private static void restoreMode(MinecraftServer server, String name) {
        P2PBanManager.runServerCommand(server,
                "gamemode " + KfcudpClient.getActiveGameMode().name().toLowerCase(Locale.ROOT) + " " + name);
    }

    /** 탭 목록 숨기기 대기열 처리 + 오래 기다린 사람 내보내기. 매 서버 틱. */
    private static void processHideQueue(MinecraftServer server) {
        for (Map.Entry<UUID, Integer> e : hideQueue.entrySet()) {
            if (e.getValue() > 1) {
                e.setValue(e.getValue() - 1);
            } else {
                hideQueue.remove(e.getKey());
                refreshList(server, e.getKey());
            }
        }
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Pending q : pending.values()) {
            if (now - q.at() > PENDING_TTL_MS) stale.add(q.name());
        }
        for (String n : stale) deny(server, n);
    }

    public static void registerTick() {
        ServerTickEvents.END_SERVER_TICK.register(AdmitGate::tick);
    }

    private static float angleDiff(float a, float b) {
        return Math.abs((a - b + 540.0f) % 360.0f - 180.0f);
    }

    private static boolean drifted(double x, double y, double z, float yaw, float pitch, Pending p) {
        double dx = x - p.x(), dy = y - p.y(), dz = z - p.z();
        return dx * dx + dy * dy + dz * dz > 4.0e-4 || angleDiff(yaw, p.yaw()) > 0.5f || Math.abs(pitch - p.pitch()) > 0.5f;
    }

    /** 이 사람을 붙잡아야 하는지 — 승인제가 켜진 방이고, 방장·승인 명단·OP·화이트리스트 누구도 아닐 때. */
    public static boolean shouldHold(MinecraftServer server, UUID id, String name) {
        if (!KfcudpClient.isRoomActive() || !P2PConfig.isApprovalGate()) return false;
        if (id.equals(KfcudpClient.currentHostUuid())) return false;
        return !isTrusted(id)
                && !P2POpManager.contains(id.toString())
                && !P2PWhitelistManager.isWhitelisted(id.toString())
                && !P2PBanManager.isOpProfile(server, new P2PBanManager.ProfileLookup(id, name));
    }

    private static UUID findPending(String name) {
        for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** 승인 — 풀어 주고, 원래 게임 모드·붙잡은 자리로 돌리고, 승인 명단에 넣는다. @return 처리했으면 1 */
    public static int admit(MinecraftServer server, String name) {
        UUID id = findPending(name);
        if (id == null) return 0;
        Pending p = pending.remove(id);
        setHidden(server, id, false);
        reveal(server, id);
        broadcastHeldJoin(server, id);
        synchronized (AdmitGate.class) {
            trusted.put(id.toString().toLowerCase(Locale.ROOT), p.name());
        }
        save();
        String mode = KfcudpClient.getActiveGameMode().name().toLowerCase(Locale.ROOT);
        P2PBanManager.runServerCommand(server, "gamemode " + mode + " " + p.name());
        // 관전자로 있는 동안 움직이지 못했지만, 모드를 바꾸면 위치가 살짝 튈 수 있어 붙잡은 자리로 정확히 돌린다.
        P2PBanManager.runServerCommand(server, String.format(Locale.ROOT,
                "execute as %s at @s run tp @s %.2f %.2f %.2f %.2f %.2f", p.name(), p.x(), p.y(), p.z(), p.yaw(), p.pitch()));
        P2PBanManager.overlayByName(server, p.name(), "msg.admitted", "§aThe host admitted you!");
        return 1;
    }

    /** 거절 — 내보낸다. @return 처리했으면 1 */
    public static int deny(MinecraftServer server, String name) {
        UUID id = findPending(name);
        if (id == null) return 0;
        Pending p = pending.remove(id);
        heldJoin.remove(id);
        P2PBanManager.kickByName(server, p.name(), "msg.admit_denied", "§cThe host did not admit you.");
        return 1;
    }

    /** 승인 명단에서 뺀다 — 다음 접속부터 다시 붙잡힌다. @return 처리했으면 1 */
    public static int untrust(String name) {
        String hit = null;
        synchronized (AdmitGate.class) {
            for (Map.Entry<String, String> e : trusted.entrySet()) {
                if (e.getValue().equalsIgnoreCase(name)) {
                    hit = e.getKey();
                    break;
                }
            }
            if (hit != null) trusted.remove(hit);
        }
        if (hit == null) return 0;
        save();
        return 1;
    }

    public static synchronized List<String> trustedNames() {
        return new ArrayList<>(trusted.values());
    }

    // ── 버전별 서버 API — 아래는 Mojang(26.x) / Yarn(1.21.x) 이름만 다르고 하는 일은 같다 ─────────

    //? if >=26.1 {
    /*public static void onJoin(MinecraftServer server, ServerPlayer p) {
        UUID id = P2PBanManager.profileId(p.getGameProfile());
        String name = P2PBanManager.profileName(p.getGameProfile());
        hideQueue.put(id, 2);
        if (!shouldHold(server, id, name)) return;
        if (!NAME.matcher(name).matches()) {
            P2PBanManager.kickFb(p, "msg.admit_denied", "§cThe host did not admit you.");
            return;
        }
        pending.put(id, new Pending(name, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), System.currentTimeMillis()));
        hidden(p, true);
        boolean ok = spectate(p);
        LOG.info("[admit] holding a joiner in spectator (changed={})", ok);
        // 입장 처리 도중 게임 모드가 다시 덮어써질 수 있어 한 틱 뒤에 한 번 더
        server.execute(() -> {
            if (pending.containsKey(id)) spectate(p);
        });
        P2PBanManager.overlayFb(p, "msg.admit_wait", "§eYou are watching until the host admits you.");
    }

    private static void hidden(ServerPlayer sp, boolean on) {
        sp.setInvisible(on || sp.hasEffect(MobEffects.INVISIBILITY)); // 투명 물약은 그대로 둔다
    }

    private static void setHidden(MinecraftServer server, UUID id, boolean on) {
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp != null) hidden(sp, on);
    }

    private static boolean spectate(ServerPlayer p) {
        return p.setGameMode(GameType.SPECTATOR);
    }

    // 새로 붙잡은 사람을 다른 사람들의 탭 목록에서 뺀다.
    private static void hideNewPending(MinecraftServer server, ServerPlayer p, UUID id) {
        ClientboundPlayerInfoRemovePacket pk = new ClientboundPlayerInfoRemovePacket(List.of(id));
        for (ServerPlayer o : server.getPlayerList().getPlayers()) {
            if (o != p) o.connection.send(pk);
        }
    }

    // 새로 들어온 사람에게 이미 붙잡혀 있는 사람들을 안 보이게 한다.
    private static void hidePendingFrom(MinecraftServer server, ServerPlayer to) {
        List<UUID> ids = new ArrayList<>(pending.keySet());
        ids.remove(to.getUUID());
        if (!ids.isEmpty()) to.connection.send(new ClientboundPlayerInfoRemovePacket(ids));
    }

    private static void refreshList(MinecraftServer server, UUID id) {
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp == null) return;
        hidePendingFrom(server, sp);
        if (pending.containsKey(id)) hideNewPending(server, sp, id);
    }

    // 승인하면 탭 목록에 다시 넣는다.
    private static void reveal(MinecraftServer server, UUID id) {
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp != null) server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(sp)));
    }

    private static void tick(MinecraftServer server) {
        processHideQueue(server);
        if (pending.isEmpty()) return;
        for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp == null) continue;
            Pending p = e.getValue();
            if (sp.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) spectate(sp);
            if (!sp.isInvisible()) hidden(sp, true);
            if (sp.getCamera() != sp) sp.setCamera(sp); // 관전자 시점 들어가기 금지
            if (drifted(sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot(), p)) {
                sp.teleportTo(sp.level(), p.x(), p.y(), p.z(), java.util.Set.of(), p.yaw(), p.pitch(), false);
            }
        }
    }

    // 참여 메시지를 방장에게만 [승인]/[거절] 버튼과 함께 보낸다.
    public static void announceHold(MinecraftServer server, UUID id, String name, Object joinMessage) {
        heldJoin.put(id, joinMessage);
        ServerPlayer host = server.getPlayerList().getPlayer(KfcudpClient.currentHostUuid());
        if (host == null) return;
        host.sendSystemMessage(((Component) joinMessage).copy()
                .append(Component.literal(" "))
                .append(Component.translatable("instant-p2p.msg.admit_button").setStyle(runStyle("/admit " + name)))
                .append(Component.literal(" "))
                .append(Component.translatable("instant-p2p.msg.deny_button").setStyle(runStyle("/deny " + name))));
    }

    private static void broadcastHeldJoin(MinecraftServer server, UUID id) {
        Object msg = heldJoin.remove(id);
        if (msg == null) return;
        UUID host = KfcudpClient.currentHostUuid();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!sp.getUUID().equals(host)) sp.sendSystemMessage((Component) msg);
        }
    }
    *///?} else {
    public static void onJoin(MinecraftServer server, ServerPlayerEntity p) {
        UUID id = P2PBanManager.profileId(p.getGameProfile());
        String name = P2PBanManager.profileName(p.getGameProfile());
        hideQueue.put(id, 2);
        if (!shouldHold(server, id, name)) return;
        if (!NAME.matcher(name).matches()) {
            P2PBanManager.kickFb(p, "msg.admit_denied", "§cThe host did not admit you.");
            return;
        }
        pending.put(id, new Pending(name, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), System.currentTimeMillis()));
        hidden(p, true);
        boolean ok = spectate(p);
        LOG.info("[admit] holding a joiner in spectator (changed={})", ok);
        // 입장 처리 도중 게임 모드가 다시 덮어써질 수 있어 한 틱 뒤에 한 번 더
        server.execute(() -> {
            if (pending.containsKey(id)) spectate(p);
        });
        P2PBanManager.overlayFb(p, "msg.admit_wait", "§eYou are watching until the host admits you.");
    }

    private static void hidden(ServerPlayerEntity sp, boolean on) {
        sp.setInvisible(on || sp.hasStatusEffect(StatusEffects.INVISIBILITY)); // 투명 물약은 그대로 둔다
    }

    private static void setHidden(MinecraftServer server, UUID id, boolean on) {
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
        if (sp != null) hidden(sp, on);
    }

    private static boolean spectate(ServerPlayerEntity p) {
        return p.changeGameMode(GameMode.SPECTATOR);
    }

    /** 새로 붙잡은 사람을 다른 사람들의 탭 목록에서 뺀다. */
    private static void hideNewPending(MinecraftServer server, ServerPlayerEntity p, UUID id) {
        PlayerRemoveS2CPacket pk = new PlayerRemoveS2CPacket(List.of(id));
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o != p) o.networkHandler.sendPacket(pk);
        }
    }

    /** 새로 들어온 사람에게 이미 붙잡혀 있는 사람들을 안 보이게 한다. */
    private static void hidePendingFrom(MinecraftServer server, ServerPlayerEntity to) {
        List<UUID> ids = new ArrayList<>(pending.keySet());
        ids.remove(to.getUuid());
        if (!ids.isEmpty()) to.networkHandler.sendPacket(new PlayerRemoveS2CPacket(ids));
    }

    private static void refreshList(MinecraftServer server, UUID id) {
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
        if (sp == null) return;
        hidePendingFrom(server, sp);
        if (pending.containsKey(id)) hideNewPending(server, sp, id);
    }

    /** 승인하면 탭 목록에 다시 넣는다. */
    private static void reveal(MinecraftServer server, UUID id) {
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
        if (sp != null) server.getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(List.of(sp)));
    }

    private static void tick(MinecraftServer server) {
        processHideQueue(server);
        if (pending.isEmpty()) return;
        for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(e.getKey());
            if (sp == null) continue;
            Pending p = e.getValue();
            if (sp.interactionManager.getGameMode() != GameMode.SPECTATOR) spectate(sp);
            if (!sp.isInvisible()) hidden(sp, true);
            if (sp.getCameraEntity() != sp) sp.setCameraEntity(sp); // 관전자 시점 들어가기 금지
            if (drifted(sp.getX(), sp.getY(), sp.getZ(), sp.getYaw(), sp.getPitch(), p)) {
                sp.networkHandler.requestTeleport(p.x(), p.y(), p.z(), p.yaw(), p.pitch());
            }
        }
    }

    /** 참여 메시지를 방장에게만 [승인]/[거절] 버튼과 함께 보낸다. */
    public static void announceHold(MinecraftServer server, UUID id, String name, Object joinMessage) {
        heldJoin.put(id, joinMessage);
        ServerPlayerEntity host = server.getPlayerManager().getPlayer(KfcudpClient.currentHostUuid());
        if (host == null) return;
        host.sendMessage(((Text) joinMessage).copy()
                .append(Text.literal(" "))
                .append(Text.translatable("instant-p2p.msg.admit_button").setStyle(runStyle("/admit " + name)))
                .append(Text.literal(" "))
                .append(Text.translatable("instant-p2p.msg.deny_button").setStyle(runStyle("/deny " + name))));
    }

    private static void broadcastHeldJoin(MinecraftServer server, UUID id) {
        Object msg = heldJoin.remove(id);
        if (msg == null) return;
        UUID host = KfcudpClient.currentHostUuid();
        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            if (!sp.getUuid().equals(host)) sp.sendMessage((Text) msg, false);
        }
    }
    //?}

    // 클릭하면 명령을 실행하는 글자 — ClickEvent 가 1.21.5 에서 레코드(RunCommand)로 바뀌었다.
    //? if >=26.1 {
    /*private static Style runStyle(String cmd) {
        return Style.EMPTY.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(cmd));
    }
    *///?}
    //? if >=1.21.5 <26.1 {
    private static Style runStyle(String cmd) {
        return Style.EMPTY.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand(cmd));
    }
    //?}
    //? if <1.21.5 {
    /*private static Style runStyle(String cmd) {
        return Style.EMPTY.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, cmd));
    }
    *///?}
}
