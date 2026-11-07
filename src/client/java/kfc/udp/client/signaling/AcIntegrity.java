package kfc.udp.client.signaling;

import kfc.udp.client.anticheat.Engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//? if >=26.1 {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
//?}

/**
 * 안티치트 무결성 확인 — 방장이 접속한 게스트에게 nonce를 보내면, 게스트의 이 모드가 자기 모드 목록과 이 모드 jar
 * 해시를 돌려준다. 방장은 엔진({@link Engine#onIntegrity})으로 알려진 핵 모드·엑스레이 모드·변조된 jar를 본다.
 * <p>
 * 접속 직후 바로 보내면 게스트 쪽 채널 등록이 끝나기 전이라 빠질 수 있어(RoomRoles 주석과 같은 문제) 2초 뒤에 보낸다.
 * 이 기능이 없는 구버전 게스트는 15초 안에 답이 없어 "응답 없음(구버전?)" 알림만 뜨고 강퇴되지 않는다.
 */
public final class AcIntegrity {

    private AcIntegrity() {}

    static final int MAX_MODS = 512;
    static final int MAX_ID = 64;

    /** 보낼 차례가 된 게스트(UUID → 보낼 서버 틱). */
    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();

    //? if >=26.1 {
    /*public record Request(String nonce) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Request> ID = new CustomPacketPayload.Type<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", "ac_req"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(Request::write, Request::new);
        private Request(FriendlyByteBuf buf) { this(buf.readUtf(64)); }
        private void write(FriendlyByteBuf buf) { buf.writeUtf(nonce, 64); }
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record Report(String nonce, String version, String build, List<String> mods) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Report> ID = new CustomPacketPayload.Type<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", "ac_report"));
        public static final StreamCodec<FriendlyByteBuf, Report> CODEC = CustomPacketPayload.codec(Report::write, Report::new);
        private Report(FriendlyByteBuf buf) { this(buf.readUtf(64), buf.readUtf(64), buf.readUtf(80), readMods(buf)); }
        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(nonce, 64); buf.writeUtf(version, 64); buf.writeUtf(build, 80);
            int n = Math.min(mods.size(), MAX_MODS);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) buf.writeUtf(clip(mods.get(i)), MAX_ID);
        }
        private static List<String> readMods(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_MODS) throw new IllegalArgumentException("too many mods");
            List<String> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(buf.readUtf(MAX_ID));
            return l;
        }
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void registerServer() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Request.ID, Request.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Report.ID, Report.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Report.ID, (payload, context) -> {
            ServerPlayer sp = context.player();
            MinecraftServer server = context.server();
            server.execute(() -> onReport(sp, payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Request.ID, (payload, context) ->
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new Report(payload.nonce(), P2PConfig.MOD_VERSION, BuildProof.sha256(), myMods())));
    }

    static void ask(ServerPlayer p) {
        DUE.put(p.getUUID(), -1L);
    }

    // 서버 틱마다(AntiCheatHooks) — 2초 지난 게스트에게 요청을 보낸다. 
    static void tick(MinecraftServer server, long tick) {
        if (DUE.isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Long due = DUE.get(p.getUUID());
            if (due == null) continue;
            if (due < 0) { DUE.put(p.getUUID(), tick + 40); continue; }
            if (tick < due) continue;
            DUE.remove(p.getUUID());
            String nonce = Engine.INSTANCE.integrityChallenge(p.getUUID());
            if (nonce != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, Request.ID)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Request(nonce));
            }
        }
    }

    private static void onReport(ServerPlayer sp, Report r) {
        if (sp.hasDisconnected()) return;
        Engine.Out o = Engine.INSTANCE.onIntegrity(sp.getUUID(), r.nonce(), r.version(), r.build(), r.mods(),
                P2PConfig.MOD_VERSION, BuildProof.sha256());
        if (o.kick != null) AntiCheatHooks.kickLater(sp.getUUID(), o.kick);
    }
    *///?} else {
    public record Request(String nonce) implements CustomPayload {
        public static final CustomPayload.Id<Request> ID = new CustomPayload.Id<>(net.minecraft.util.Identifier.of("instant-p2p", "ac_req"));
        public static final PacketCodec<PacketByteBuf, Request> CODEC = CustomPayload.codecOf(Request::write, Request::new);
        private Request(PacketByteBuf buf) { this(buf.readString(64)); }
        private void write(PacketByteBuf buf) { buf.writeString(nonce, 64); }
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public record Report(String nonce, String version, String build, List<String> mods) implements CustomPayload {
        public static final CustomPayload.Id<Report> ID = new CustomPayload.Id<>(net.minecraft.util.Identifier.of("instant-p2p", "ac_report"));
        public static final PacketCodec<PacketByteBuf, Report> CODEC = CustomPayload.codecOf(Report::write, Report::new);
        private Report(PacketByteBuf buf) { this(buf.readString(64), buf.readString(64), buf.readString(80), readMods(buf)); }
        private void write(PacketByteBuf buf) {
            buf.writeString(nonce, 64); buf.writeString(version, 64); buf.writeString(build, 80);
            int n = Math.min(mods.size(), MAX_MODS);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) buf.writeString(clip(mods.get(i)), MAX_ID);
        }
        private static List<String> readMods(PacketByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_MODS) throw new IllegalArgumentException("too many mods");
            List<String> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(buf.readString(MAX_ID));
            return l;
        }
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void registerServer() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(Request.ID, Request.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(Report.ID, Report.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Report.ID, (payload, context) -> {
            ServerPlayerEntity sp = context.player();
            MinecraftServer server = context.server();
            server.execute(() -> onReport(sp, payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Request.ID, (payload, context) ->
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new Report(payload.nonce(), P2PConfig.MOD_VERSION, BuildProof.sha256(), myMods())));
    }

    static void ask(ServerPlayerEntity p) {
        DUE.put(p.getUuid(), -1L);
    }

    static void tick(MinecraftServer server, long tick) {
        if (DUE.isEmpty()) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Long due = DUE.get(p.getUuid());
            if (due == null) continue;
            if (due < 0) { DUE.put(p.getUuid(), tick + 40); continue; }
            if (tick < due) continue;
            DUE.remove(p.getUuid());
            String nonce = Engine.INSTANCE.integrityChallenge(p.getUuid());
            if (nonce != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, Request.ID)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Request(nonce));
            }
        }
    }

    private static void onReport(ServerPlayerEntity sp, Report r) {
        if (sp.isDisconnected()) return;
        Engine.Out o = Engine.INSTANCE.onIntegrity(sp.getUuid(), r.nonce(), r.version(), r.build(), r.mods(),
                P2PConfig.MOD_VERSION, BuildProof.sha256());
        if (o.kick != null) AntiCheatHooks.kickLater(sp.getUuid(), o.kick);
    }
    //?}

    static void forget(UUID id) {
        DUE.remove(id);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > MAX_ID ? s.substring(0, MAX_ID) : s;
    }

    /** 이 클라에 깔린 모드 id 목록(라이브러리·내장 모드 포함). */
    private static List<String> myMods() {
        List<String> l = new ArrayList<>();
        int bytes = 0;
        for (var m : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            String id = clip(m.getMetadata().getId());
            bytes += id.length() * 3 + 3;
            if (l.size() >= MAX_MODS || bytes > 28_000) break; // 서버행 페이로드 한도(32KB) 안쪽
            l.add(id);
        }
        return l;
    }
}
