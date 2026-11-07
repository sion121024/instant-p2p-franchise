package kfc.udp.client.signaling;

import kfc.udp.client.quic.QuicBridge;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import com.mojang.brigadier.CommandDispatcher;
//? if >=26.1 {
/*import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
*///?} else {
import net.minecraft.command.CommandSource;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//? if >=1.21.9
//import net.minecraft.server.PlayerConfigEntry;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

//? if >=26.1 {
/*import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
*///?} else {
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
//?}

/**
 * 서버(호스팅 중인 방)의 밴/킥 명령어 + 로그인 단계 거부(checkCanJoin)를 담당하는
 * 동시에, 방 목록 화면(RoomListScreen)의 "개인 차단" 아이콘도 여기 하나로 합쳐서
 * 처리한다 — 예전엔 별도 클래스(P2PBlockManager)가 자기만의 파일(blocked-players.json)을
 * 따로 관리했는데, 인게임에서 밴한 사람은 방 목록에서도 안 보여야 하고 방 목록에서
 * 차단한 사람은 내 방에 못 들어와야 한다는 요구를 "동기화"로 풀면 두 파일이 어긋날
 * 여지가 늘 남는다 — 그래서 애초에 같은 맵/같은 파일({@link #bannedPlayers},
 * banned-players.json) 하나만 두고 양쪽이 그걸 그대로 참조하게 했다.
 * <p>
 * <b>양방향 차단이 되는 원리</b> — 필터링은 전부 조회하는 쪽(클라이언트) 로컬에서만
 * 일어난다(서버가 대상을 가려서 안 보내주는 게 아니다):
 * <ul>
 *   <li>"내가 밴/차단한 사람 방이 안 보임" — 내 화면이 방 목록을 받은 뒤, 방장의
 *       hostUuid가 {@link #isPlayerBanned}면 그 방을 그냥 숨긴다.</li>
 *   <li>"밴/차단한 사람한테 내 방이 안 보임" — 내가 방을 공개할 때 내 밴 목록을
 *       해시로 바꿔 방 공지 payload에 같이 실어 보낸다({@link #encodeBannedPlayerHashes},
 *       PublicRoomAnnouncer 참고). 그 목록을 받아보는 모든 클라이언트가 "내 UUID가
 *       이 방의 밴 목록에 있나"를 {@link #isBannedIn}으로 확인해서, 있으면 자기
 *       화면에서 그 방을 숨긴다.</li>
 * </ul>
 * 후자는 서버가 강제하는 게 아니라 각 클라이언트가 스스로 지키는 방식이라 완벽한
 * 보안 경계는 아니지만(악의적으로 이 모드를 고쳐 쓰면 무시할 수 있음), "서로 안
 * 보고 싶다"는 사회적 기능 목적에는 충분하다. IP 밴({@link #bannedIps})은 이
 * 통합 대상이 아니다 — 방 목록 차단은 어디까지나 UUID(플레이어) 단위 취향이고,
 * IP 밴은 더 강한 별도의 수동 조치로 남겨둔다.
 */
public class P2PBanManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-ban");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private static final Path CONFIG_DIR     = Path.of("config", "instant-p2p");
    private static final Path BANNED_PLAYERS = CONFIG_DIR.resolve("banned-players.json");
    private static final Path BANNED_IPS     = CONFIG_DIR.resolve("banned-ips.json");

    private static final Map<String, JsonObject> bannedPlayers = new LinkedHashMap<>();
    private static final Map<String, JsonObject> bannedIps     = new LinkedHashMap<>();

    /**
     * 터널을 지나면 모든 조인자가 127.0.0.1 로 보이기 때문에
     * MC 가 보는 로컬 포트 → 실제 원격 IP 매핑을 QuicHost 가 등록해 준다.
     */
    private static final Map<Integer, String> tunnelPortToIp = new ConcurrentHashMap<>();
    /** 로그인 시점에 확정된 UUID → 실제 원격 IP (ban-ip 명령에서 사용) */
    private static final Map<UUID, String>    uuidToRealIp   = new ConcurrentHashMap<>();
    /** QuicHost가 QUIC 연결 성사 시 등록 — 실제 IP → 직결/중계 여부 (참여 메시지 접미사용) */
    private static final Map<String, Boolean> connectionTypeByIp = new ConcurrentHashMap<>();
    /**
     * 터널 로컬 포트 → 그 연결이 지금 중계인지 묻는 함수(1.5~, QuicHost 가 등록). IP 단위 기록은 같은 IP 에서 둘이
     * 들어오면(같은 집) 섞였고, 중계로 온 연결은 출처가 TURN 서버라 IP 자체를 모른다 — 연결 단위로 묻는다.
     */
    private static final Map<Integer, Supplier<Boolean>> tunnelPortType = new ConcurrentHashMap<>();
    private static final Map<UUID, Supplier<Boolean>> uuidToType = new ConcurrentHashMap<>();

    /** 재접속 도배 막기(1.5~) — UUID → 최근 1분 안의 로그인 시각들 / 잠금이 풀리는 시각. */
    private static final Map<UUID, ArrayDeque<Long>> loginTimes = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> loginLockUntil = new ConcurrentHashMap<>();

    /** 방 정원 게이트. KfcudpClient 가 방을 열 때 세팅, 닫을 때 0. */
    private static volatile int roomMaxPlayers = 0;

    /**
     * 1분 안에 6번 넘게 들어오면 2분 동안 막는다(1.5~) — 들어왔다 나갔다를 반복해 입장·퇴장 메시지로 채팅을
     * 도배하거나, 방장의 로그인 처리(등급 새로고침·안티치트 등)를 계속 돌리게 하는 걸 막는다.
     */
    private static boolean loginTooOften(UUID id) {
        long now = System.currentTimeMillis();
        Long lock = loginLockUntil.get(id);
        if (lock != null && now < lock) return true;
        if (loginTimes.size() > 512) { // 표가 무한히 늘지 않게
            loginTimes.clear();
            loginLockUntil.entrySet().removeIf(e -> now >= e.getValue());
        }
        ArrayDeque<Long> q = loginTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() > 6) {
                loginLockUntil.put(id, now + 120_000);
                q.clear();
                return true;
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 버전 호환 헬퍼 — 1.21.9에서 GameProfile/isHost/권한 체크 API가 바뀜
    // -------------------------------------------------------------------------

    /** {@code GameProfile#getId()} → {@code id()} (1.21.9+, record화) */
    public static UUID profileId(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.id();
        *///?} else {
        return profile.getId();
        //?}
    }

    /** {@code GameProfile#getName()} → {@code name()} (1.21.9+, record화) */
    public static String profileName(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.name();
        *///?} else {
        return profile.getName();
        //?}
    }

    // 아래 6개는 Yarn/Mojang에서 이름만 다른 서버 API를 한 줄씩 감싼 것 — 이게 있어야
    // checkCanJoin과 명령어 실행부(executeBan 등)를 에라별로 복붙하지 않고 한 벌만 둘 수 있다
    // (본문은 공용, 시그니처 줄만 분기 — registerBanCommands와 같은 방식).

    /** 채팅 컴포넌트 생성 — Text(Yarn)/Component(Mojang). 브리가디어 {@code literal}과 이름이 겹쳐 msg로 둔다.
     * KfcudpClient도 같은 이유로 이걸 쓴다(에라별 본문 복붙을 피하려면 이 한 줄이 필요하다). */
    //? if >=26.1 {
    /*public static Component msg(String text) {
        return Component.literal(text);
    }

    public static Component msgKey(String key, Object... args) {
        return Component.translatable(key, args);
    }

    // 명령 실행자에게 피드백 한 줄 — sendFeedback → sendSuccess 개명(26.1).
    private static void feedback(CommandSourceStack src, String text) {
        src.sendSuccess(() -> msg(text), false);
    }

    // LAN(방)이 열려 있는지 — isRemote → isPublished 개명(26.1).
    private static boolean isHosting(MinecraftServer server) {
        return server.isPublished();
    }

    private static List<ServerPlayer> onlinePlayers(MinecraftServer server) {
        return server.getPlayerList().getPlayers();
    }

    private static ServerPlayer playerByUuid(MinecraftServer server, UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    private static ServerPlayer playerByName(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    private static void disconnect(ServerPlayer player, String reason) {
        player.connection.disconnect(msg(reason));
    }

    // ── 1.5: 번역 키 + 영어 대체 문구 ─────────────────────────────────────────
    // 접속자가 이 모드를 안 깔았어도(=번역 파일이 없어도) 영어 문구로 보이게 translatableWithFallback 을 쓴다.
    // key 는 "instant-p2p." 뒤 부분만 받는다.
    public static Component msgFb(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("instant-p2p." + key, fallback, args);
    }

    private static void feedbackFb(CommandSourceStack src, String key, String fallback, Object... args) {
        src.sendSuccess(() -> msgFb(key, fallback, args), false);
    }

    private static void disconnectFb(ServerPlayer player, String key, String fallback, Object... args) {
        player.connection.disconnect(msgFb(key, fallback, args));
    }

    public static void kickFb(ServerPlayer player, String key, String fallback, Object... args) {
        disconnectFb(player, key, fallback, args);
    }

    // 액션바(채팅 위 한 줄)로 띄운다 — 채팅을 막을 때 채팅창을 또 채우지 않게.
    public static void overlayFb(ServerPlayer player, String key, String fallback, Object... args) {
        player.sendSystemMessage(msgFb(key, fallback, args), true);
    }

    public static void overlayByName(MinecraftServer server, String name, String key, String fallback) {
        ServerPlayer p = playerByName(server, name);
        if (p != null) overlayFb(p, key, fallback);
    }

    public static void kickByName(MinecraftServer server, String name, String key, String fallback) {
        ServerPlayer p = playerByName(server, name);
        if (p != null) disconnectFb(p, key, fallback);
    }

    // 서버 권한으로 명령 하나를 조용히 실행한다(출력 없음) — 입장 승인제의 게임 모드·위치 되돌리기용.
    public static void runServerCommand(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    // 방장에게만 알린다 — 방장이 없으면(서버가 null 이거나 방장이 아직 안 들어옴) 버린다.
    public static void tellHostFb(MinecraftServer server, String key, String fallback, Object... args) {
        if (server == null) return;
        ServerPlayer host = server.getPlayerList().getPlayer(kfc.udp.client.KfcudpClient.currentHostUuid());
        if (host != null) host.sendSystemMessage(msgFb(key, fallback, args));
    }
    *///?} else {
    public static Text msg(String text) {
        return Text.literal(text);
    }

    public static Text msgKey(String key, Object... args) {
        return Text.translatable(key, args);
    }

    /** 명령 실행자에게 피드백 한 줄 — sendFeedback → sendSuccess 개명(26.1). */
    private static void feedback(ServerCommandSource src, String text) {
        src.sendFeedback(() -> msg(text), false);
    }

    /** LAN(방)이 열려 있는지 — isRemote → isPublished 개명(26.1). */
    private static boolean isHosting(MinecraftServer server) {
        return server.isRemote();
    }

    private static List<ServerPlayerEntity> onlinePlayers(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList();
    }

    private static ServerPlayerEntity playerByUuid(MinecraftServer server, UUID id) {
        return server.getPlayerManager().getPlayer(id);
    }

    private static ServerPlayerEntity playerByName(MinecraftServer server, String name) {
        return server.getPlayerManager().getPlayer(name);
    }

    private static void disconnect(ServerPlayerEntity player, String reason) {
        player.networkHandler.disconnect(msg(reason));
    }

    // ── 1.5: 번역 키 + 영어 대체 문구 ─────────────────────────────────────────
    // 접속자가 이 모드를 안 깔았어도(=번역 파일이 없어도) 영어 문구로 보이게 translatableWithFallback 을 쓴다.
    // key 는 "instant-p2p." 뒤 부분만 받는다.
    public static Text msgFb(String key, String fallback, Object... args) {
        return Text.translatableWithFallback("instant-p2p." + key, fallback, args);
    }

    private static void feedbackFb(ServerCommandSource src, String key, String fallback, Object... args) {
        src.sendFeedback(() -> msgFb(key, fallback, args), false);
    }

    private static void disconnectFb(ServerPlayerEntity player, String key, String fallback, Object... args) {
        player.networkHandler.disconnect(msgFb(key, fallback, args));
    }

    public static void kickFb(ServerPlayerEntity player, String key, String fallback, Object... args) {
        disconnectFb(player, key, fallback, args);
    }

    /** 액션바(채팅 위 한 줄)로 띄운다 — 채팅을 막을 때 채팅창을 또 채우지 않게. */
    public static void overlayFb(ServerPlayerEntity player, String key, String fallback, Object... args) {
        player.sendMessage(msgFb(key, fallback, args), true);
    }

    public static void overlayByName(MinecraftServer server, String name, String key, String fallback) {
        ServerPlayerEntity p = playerByName(server, name);
        if (p != null) overlayFb(p, key, fallback);
    }

    public static void kickByName(MinecraftServer server, String name, String key, String fallback) {
        ServerPlayerEntity p = playerByName(server, name);
        if (p != null) disconnectFb(p, key, fallback);
    }

    /** 서버 권한으로 명령 하나를 조용히 실행한다(출력 없음) — 입장 승인제의 게임 모드·위치 되돌리기용. */
    public static void runServerCommand(MinecraftServer server, String command) {
        CommandManager manager = server.getCommandManager();
        manager.execute(manager.getDispatcher().parse(command, server.getCommandSource().withSilent()), command);
    }

    /** 방장에게만 알린다 — 방장이 없으면(서버가 null 이거나 방장이 아직 안 들어옴) 버린다. */
    public static void tellHostFb(MinecraftServer server, String key, String fallback, Object... args) {
        if (server == null) return;
        ServerPlayerEntity host = server.getPlayerManager().getPlayer(kfc.udp.client.KfcudpClient.currentHostUuid());
        if (host != null) host.sendMessage(msgFb(key, fallback, args));
    }
    //?}

    /** {@code MinecraftServer#isHost(GameProfile)} → {@code isHost(PlayerConfigEntry)} (1.21.9+) */
    private static boolean isHost(MinecraftServer server, GameProfile profile) {
        if (profile == null) return false;
        //? if >=26.1 {
        /*return server.isSingleplayerOwner(new NameAndId(profile));
        *///?}
        //? if >=1.21.9 <26.1 {
        /*return server.isHost(new PlayerConfigEntry(profile));
        *///?}
        //? if <1.21.9 {
        return server.isHost(profile);
        //?}
    }

    /** OP 목록에 올라 있는지(방장이 /op로 준 것) — "명령어 허용"(치트) 설정과 무관하다.
     * OP 목록 조회 API가 1.21.9(PlayerConfigEntry)·26.1(getOps/NameAndId)에서 바뀌었다. */
    private static boolean isOp(MinecraftServer server, GameProfile profile) {
        if (profile == null) return false;
        //? if >=26.1 {
        /*return server.getPlayerList().getOps().get(new NameAndId(profile)) != null;
        *///?}
        //? if >=1.21.9 <26.1 {
        /*return server.getPlayerManager().getOpList().get(new PlayerConfigEntry(profile)) != null;
        *///?}
        //? if <1.21.9 {
        return server.getPlayerManager().getOpList().get(profile) != null;
        //?}
    }

    /** 다른 패키지(예: KfcudpClient)에서 "이 플레이어가 방장인가"를 물을 때 쓰는 공개 버전. */
    //? if >=26.1 {
    /*public static boolean isHost(MinecraftServer server, ServerPlayer player) {
    *///?} else {
    public static boolean isHost(MinecraftServer server, ServerPlayerEntity player) {
    //?}
        return player != null && isHost(server, player.getGameProfile());
    }

    /**
     * kick/ban/pardon/whitelist — 방장이거나, 방장이 /op로 OP를 준 사람만 쓸 수 있다("명령어 허용" 설정과 무관).
     * <p>
     * 바닐라의 {@code hasPermissionLevel}/{@code isOperator} 경로는 쓰지 않고 OP 목록 자체를 본다({@link #isOp}) —
     * {@code PlayerManager.isOperator()}가 {@code ops.contains(entry) || (isHost && areCommandsAllowed) || cheatsAllowed}로
     * 구현돼 있어서, "명령어 허용"(치트)이 켜지면 OP를 안 받은 접속자까지 전부 op 취급되어 누구나 ban을 쓰게 된다.
     */
    //? if >=26.1 {
    /*static Predicate<CommandSourceStack> requireAdminOrHost() {
        return src -> {
            MinecraftServer server = src.getServer();
            return server != null && src.getEntity() instanceof ServerPlayer sp
                    && (isHost(server, sp) || isOp(server, sp.getGameProfile()));
        };
    }
    *///?} else {
    static Predicate<ServerCommandSource> requireAdminOrHost() {
        return src -> {
            MinecraftServer server = src.getServer();
            return server != null && src.getEntity() instanceof ServerPlayerEntity sp
                    && (isHost(server, sp) || isOp(server, sp.getGameProfile()));
        };
    }
    //?}

    /**
     * OP 부여·회수는 방장만 쓸 수 있다 — OP를 받은 사람도 못 쓴다. OP는 명령어 권한 레벨 4 전체(치트 포함)에
     * kick/ban까지 주는 권한이라, OP끼리 서로 줄 수 있으면 방장이 모르는 사이 방 전체 권한이 퍼진다.
     */
    //? if >=26.1 {
    /*static Predicate<CommandSourceStack> requireHost() {
        return src -> {
            MinecraftServer server = src.getServer();
            return server != null && src.getEntity() instanceof ServerPlayer sp && isHost(server, sp);
        };
    }
    *///?} else {
    static Predicate<ServerCommandSource> requireHost() {
        return src -> {
            MinecraftServer server = src.getServer();
            return server != null && src.getEntity() instanceof ServerPlayerEntity sp && isHost(server, sp);
        };
    }
    //?}

    /**
     * 입장 승인(/admit /deny /unadmit) — 방장만. 서버가 없을 때(명령 트리 빌드 중) true 인 건 바닐라 명령 트리를
     * 만들 때 한 번 평가되기 때문이다 — 실제 실행 때는 서버가 있다(1.5 원본 그대로).
     */
    //? if >=26.1 {
    /*static Predicate<CommandSourceStack> requireHostNoConfirm() {
        return src -> {
            MinecraftServer server = src.getServer();
            if (server == null) return true;
            return src.getEntity() instanceof ServerPlayer sp && isHost(server, sp);
        };
    }
    *///?} else {
    static Predicate<ServerCommandSource> requireHostNoConfirm() {
        return src -> {
            MinecraftServer server = src.getServer();
            if (server == null) return true;
            return src.getEntity() instanceof ServerPlayerEntity sp && isHost(server, sp);
        };
    }
    //?}

    /** {@link #lookupProfile}의 결과 — 버전 무관하게 uuid+name만 필요할 때 쓰는 최소 표현. */
    public record ProfileLookup(UUID id, String name) {}

    /**
     * 접속 이력 없는(오프라인) 플레이어도 이름으로 찾는다 — 바닐라
     * {@code GameProfileArgumentType}이 쓰는 것과 동일한 usercache.json 캐시 →
     * Mojang API 경로. 서버 스레드에서 블로킹 호출이라(바닐라도 동일) 커맨드
     * 실행 컨텍스트에서만 불러야 한다.
     * <p>
     * 1.21.9부터 캐시 접근 경로가 {@code getUserCache()} → {@code getApiServices().nameToIdCache()}로,
     * 반환 타입이 {@code GameProfile} → {@code PlayerConfigEntry}로 바뀌었다.
     */
    static ProfileLookup lookupProfile(MinecraftServer server, String name) {
        //? if >=26.1 {
        /*return server.services().nameToIdCache().get(name)
                .map(e -> new ProfileLookup(e.id(), e.name()))
                .orElse(null);
        *///?}
        //? if >=1.21.9 <26.1 {
        /*return server.getApiServices().nameToIdCache().findByName(name)
                .map(e -> new ProfileLookup(e.id(), e.name()))
                .orElse(null);
        *///?}
        //? if <1.21.9 {
        return server.getUserCache().findByName(name)
                .map(p -> new ProfileLookup(profileId(p), profileName(p)))
                .orElse(null);
        //?}
    }

    /** {@code addToOperators(GameProfile)} → {@code addToOperators(PlayerConfigEntry)} (1.21.9+) → {@code PlayerList.op(NameAndId)} (26.1+) */
    private static void grantOp(MinecraftServer server, ProfileLookup lookup) {
        //? if >=26.1 {
        /*server.getPlayerList().op(new NameAndId(lookup.id(), lookup.name()));
        *///?}
        //? if >=1.21.9 <26.1 {
        /*server.getPlayerManager().addToOperators(new PlayerConfigEntry(lookup.id(), lookup.name()));
        *///?}
        //? if <1.21.9 {
        server.getPlayerManager().addToOperators(new GameProfile(lookup.id(), lookup.name()));
        //?}
    }

    static boolean isOpProfile(MinecraftServer server, ProfileLookup lookup) {
        return isOp(server, new GameProfile(lookup.id(), lookup.name()));
    }

    static void grantOpPublic(MinecraftServer server, ProfileLookup lookup, int level) {
        grantOpAtLevel(server, lookup, level);
    }

    static void revokeOpPublic(MinecraftServer server, ProfileLookup lookup) {
        revokeOp(server, lookup);
    }

    /**
     * 레벨을 지정해 OP 를 준다(1.5~) — 바닐라 /op 는 서버 기본 레벨(4)을 주는데, 방장이 준 OP 는 2(명령 블록·게임
     * 규칙까지)로 둔다. ops.json 항목을 직접 만들어 넣고 권한 레벨 패킷을 다시 보낸다.
     */
    private static void grantOpAtLevel(MinecraftServer server, ProfileLookup lookup, int level) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", lookup.id().toString());
        o.addProperty("name", lookup.name());
        o.addProperty("level", level);
        o.addProperty("bypassesPlayerLimit", false);
        //? if >=26.1 {
        /*server.getPlayerList().getOps().add(new ServerOpListEntry(o));
        ServerPlayer sp = server.getPlayerList().getPlayer(lookup.id());
        if (sp != null) server.getPlayerList().sendPlayerPermissionLevel(sp);
        *///?} else {
        server.getPlayerManager().getOpList().add(new OperatorEntry(o));
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(lookup.id());
        if (sp != null) server.getPlayerManager().sendCommandTree(sp);
        //?}
    }

    private static void revokeOp(MinecraftServer server, ProfileLookup lookup) {
        //? if >=26.1 {
        /*server.getPlayerList().deop(new NameAndId(lookup.id(), lookup.name()));
        *///?}
        //? if >=1.21.9 <26.1 {
        /*server.getPlayerManager().removeFromOperators(new PlayerConfigEntry(lookup.id(), lookup.name()));
        *///?}
        //? if <1.21.9 {
        server.getPlayerManager().removeFromOperators(new GameProfile(lookup.id(), lookup.name()));
        //?}
    }

    // -------------------------------------------------------------------------
    // 자동완성 제공자
    // -------------------------------------------------------------------------

    // 목록을 뽑는 부분은 에라 공용(아래 3개) — 밴 맵 순회는 P2PBanManager.class 락 아래서만
    // 해야 하는데(load/save와 같은 락), 그 synchronized 블록이 에라별로 복붙돼 있으면 한쪽만
    // 고치는 사고가 난다. 자동완성 제공자 자체는 소스 타입이 달라 선언만 분기한다.
    /** 현재 접속 중인 플레이어 이름 */
    private static List<String> onlinePlayerNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (var sp : onlinePlayers(server)) {
            names.add(profileName(sp.getGameProfile()));
        }
        return names;
    }

    /** 밴된 플레이어 이름 */
    private static synchronized List<String> bannedNames() {
        List<String> names = new ArrayList<>();
        for (JsonObject o : bannedPlayers.values()) {
            if (o.has("name")) names.add(o.get("name").getAsString());
        }
        return names;
    }

    /** OP 후보 — 접속 중이고 방장·OP 가 아닌 사람(1.5~, /op 자동완성). */
    private static List<String> nonOpOnlineNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (var sp : onlinePlayers(server)) {
            if (isHost(server, sp.getGameProfile()) || isOp(server, sp.getGameProfile())
                    || P2POpManager.contains(profileId(sp.getGameProfile()).toString())) continue;
            names.add(profileName(sp.getGameProfile()));
        }
        return names;
    }

    /** 밴 후보 — 접속 중이고 방장이 아니며 아직 밴 안 된 사람(1.5~, /ban 자동완성). */
    private static List<String> unbannedOnlineNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (var sp : onlinePlayers(server)) {
            if (isHost(server, sp.getGameProfile()) || isPlayerBanned(profileId(sp.getGameProfile()).toString())) continue;
            names.add(profileName(sp.getGameProfile()));
        }
        return names;
    }

    /**
     * 밴된 IP — 자동완성엔 IP 원문 대신 짧은 꼬리표(SHA-256 앞 4바이트)를 보여 준다(1.5~). 방송 중에 /pardon-ip 를
     * 치면 자동완성 목록에 남의 IP 가 그대로 떴다. /pardon-ip 는 꼬리표도 받는다(resolveBannedIp).
     */
    private static synchronized List<String> bannedIpList() {
        List<String> tags = new ArrayList<>();
        for (String ip : bannedIps.keySet()) tags.add(ipTag(ip));
        return tags;
    }

    private static String ipTag(String ip) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "?";
        }
    }

    /** IP 원문이거나 꼬리표면 밴 목록의 IP 원문으로 — 못 찾으면 그대로 돌려준다. */
    private static synchronized String resolveBannedIp(String arg) {
        if (bannedIps.containsKey(arg)) return arg;
        for (String ip : bannedIps.keySet()) {
            if (ipTag(ip).equalsIgnoreCase(arg)) return ip;
        }
        return arg;
    }

    //? if >=26.1 {
    /*private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (ctx, b) -> SharedSuggestionProvider.suggest(onlinePlayerNames(ctx.getSource().getServer()), b);
    private static final SuggestionProvider<CommandSourceStack> BANNED_PLAYER_NAMES =
            (ctx, b) -> SharedSuggestionProvider.suggest(bannedNames(), b);
    private static final SuggestionProvider<CommandSourceStack> BANNED_IPS_LIST =
            (ctx, b) -> SharedSuggestionProvider.suggest(bannedIpList(), b);
    // 1.5~: op 는 아직 OP 가 아닌 사람만, ban 은 아직 밴 안 된 사람만 제안한다
    private static final SuggestionProvider<CommandSourceStack> OP_CANDIDATES =
            (ctx, b) -> SharedSuggestionProvider.suggest(nonOpOnlineNames(ctx.getSource().getServer()), b);
    private static final SuggestionProvider<CommandSourceStack> BAN_CANDIDATES =
            (ctx, b) -> SharedSuggestionProvider.suggest(unbannedOnlineNames(ctx.getSource().getServer()), b);
    private static final SuggestionProvider<CommandSourceStack> PENDING_NAMES =
            (ctx, b) -> SharedSuggestionProvider.suggest(AdmitGate.pendingNames(), b);
    private static final SuggestionProvider<CommandSourceStack> ADMITTED_NAMES =
            (ctx, b) -> SharedSuggestionProvider.suggest(AdmitGate.trustedNames(), b);
    // 현재 OP인 플레이어 이름 자동완성 (deop 용) — 월드 OP + 이 모드가 기억하는 OP(ops.json)
    private static final SuggestionProvider<CommandSourceStack> OP_NAMES = (ctx, b) -> {
        Set<String> all = new LinkedHashSet<>(Arrays.asList(ctx.getSource().getServer().getPlayerList().getOpNames()));
        all.addAll(P2POpManager.names());
        return SharedSuggestionProvider.suggest(all, b);
    };
    *///?} else {
    private static final SuggestionProvider<ServerCommandSource> ONLINE_PLAYERS =
            (ctx, b) -> CommandSource.suggestMatching(onlinePlayerNames(ctx.getSource().getServer()), b);
    private static final SuggestionProvider<ServerCommandSource> BANNED_PLAYER_NAMES =
            (ctx, b) -> CommandSource.suggestMatching(bannedNames(), b);
    private static final SuggestionProvider<ServerCommandSource> BANNED_IPS_LIST =
            (ctx, b) -> CommandSource.suggestMatching(bannedIpList(), b);
    // 1.5~: op 는 아직 OP 가 아닌 사람만, ban 은 아직 밴 안 된 사람만 제안한다
    private static final SuggestionProvider<ServerCommandSource> OP_CANDIDATES =
            (ctx, b) -> CommandSource.suggestMatching(nonOpOnlineNames(ctx.getSource().getServer()), b);
    private static final SuggestionProvider<ServerCommandSource> BAN_CANDIDATES =
            (ctx, b) -> CommandSource.suggestMatching(unbannedOnlineNames(ctx.getSource().getServer()), b);
    private static final SuggestionProvider<ServerCommandSource> PENDING_NAMES =
            (ctx, b) -> CommandSource.suggestMatching(AdmitGate.pendingNames(), b);
    private static final SuggestionProvider<ServerCommandSource> ADMITTED_NAMES =
            (ctx, b) -> CommandSource.suggestMatching(AdmitGate.trustedNames(), b);
    // 현재 OP인 플레이어 이름 자동완성 (deop 용) — 월드 OP + 이 모드가 기억하는 OP(ops.json)
    private static final SuggestionProvider<ServerCommandSource> OP_NAMES = (ctx, b) -> {
        Set<String> all = new LinkedHashSet<>(Arrays.asList(ctx.getSource().getServer().getPlayerManager().getOpNames()));
        all.addAll(P2POpManager.names());
        return CommandSource.suggestMatching(all, b);
    };
    //?}

    // -------------------------------------------------------------------------
    // 데이터 로드 / 저장
    // -------------------------------------------------------------------------

    // 두 밴 맵은 서버 스레드(명령어·로그인 거부), 렌더 스레드(방 목록 필터·차단 아이콘),
    // 공개 방 공지 스레드가 동시에 읽고 쓴다 — 전부 P2PBanManager.class 락 아래서만
    // 만진다(락 없는 LinkedHashMap은 순회 중 수정되면 예외가 나거나 조용히 깨진다).
    public static synchronized void load() {
        bannedPlayers.clear();
        bannedIps.clear();
        loadMap(BANNED_PLAYERS, bannedPlayers, "uuid");
        loadMap(BANNED_IPS,     bannedIps,     "ip");
        banListVersion++;
    }

    /** 밴 목록이 바뀔 때마다 오른다 — RoomListScreen이 바뀌었을 때만 방 목록을 다시 거르는 데 쓴다. */
    private static volatile int banListVersion = 0;

    public static int banListVersion() {
        return banListVersion;
    }

    // 1.5~: 깨진 파일은 .corrupt 로 치우고, 한 항목이 틀려도 나머지는 읽는다. 저장은 임시 파일 + 바꿔치기(JsonFiles).
    private static void loadMap(Path path, Map<String, JsonObject> map, String key) {
        JsonArray arr = JsonFiles.readArray(path);
        if (arr == null) return;
        for (JsonElement e : arr) {
            try {
                JsonObject o = e.getAsJsonObject();
                if (o.has(key)) map.put(o.get(key).getAsString(), o);
            } catch (Exception ex) {
                LOG.warn("[instant-p2p] skipped a bad entry in {}", path.getFileName());
            }
        }
    }

    private static void save(Path path, Map<String, JsonObject> map) {
        JsonArray arr = new JsonArray();
        map.values().forEach(arr::add);
        JsonFiles.writeAtomic(path, arr);
    }

    // -------------------------------------------------------------------------
    // 밴 데이터 조작
    // -------------------------------------------------------------------------

    public static void banPlayer(String uuid, String name, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        o.addProperty("created", FMT.format(Instant.now()));
        o.addProperty("source", "instant-p2p");
        o.addProperty("expires", "forever");
        o.addProperty("reason", reason != null ? reason : "Banned by operator.");
        synchronized (P2PBanManager.class) {
            bannedPlayers.put(uuid, o);
            banListVersion++;
            save(BANNED_PLAYERS, bannedPlayers);
        }
        // 내가 지금 공개 방을 열고 있으면 새 밴 목록을 즉시 재공지 — 그래야 밴한
        // 상대에게 내 방이 곧장 안 보인다(재접속/코드 재생성을 기다리지 않고).
        QuicBridge.republishPublicRoomIfActive();
        kfc.udp.client.ChatHideSync.apply(uuid, true); // 바닐라 "채팅에서 숨기기"도 같이
    }

    public static void banIp(String ip, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("ip", ip);
        o.addProperty("created", FMT.format(Instant.now()));
        o.addProperty("source", "instant-p2p");
        o.addProperty("expires", "forever");
        o.addProperty("reason", reason != null ? reason : "Banned by operator.");
        synchronized (P2PBanManager.class) {
            bannedIps.put(ip, o);
            save(BANNED_IPS, bannedIps);
        }
    }

    public static void pardonPlayer(String name) {
        List<String> removedUuids = new ArrayList<>();
        synchronized (P2PBanManager.class) {
            // 파일을 손으로 고쳐 name이 빠진 항목이 있어도 명령어가 NPE로 죽지 않게.
            bannedPlayers.entrySet().removeIf(e -> {
                JsonElement n = e.getValue().get("name");
                boolean match = n != null && n.getAsString().equalsIgnoreCase(name);
                if (match) removedUuids.add(e.getKey());
                return match;
            });
            if (!removedUuids.isEmpty()) {
                banListVersion++;
                save(BANNED_PLAYERS, bannedPlayers);
            }
        }
        if (removedUuids.isEmpty()) return;
        QuicBridge.republishPublicRoomIfActive();
        removedUuids.forEach(uuid -> kfc.udp.client.ChatHideSync.apply(uuid, false)); // 바닐라 숨기기도 같이 해제
    }

    /**
     * 서버에 올리는 방 차단/허용 목록(1.5~, QuicHost.pushAcl) — 하이픈 없는 UUID. 서버가 차단된 사람을 랑데부
     * 단계에서 먼저 거른다(마크 접속까지 가지 않는다). 화이트리스트가 꺼져 있으면 allow 는 null.
     */
    public static String aclJson() {
        JsonObject o = new JsonObject();
        JsonArray banned = new JsonArray();
        synchronized (P2PBanManager.class) {
            for (String u : bannedPlayers.keySet()) banned.add(u.replace("-", ""));
        }
        o.add("banned", banned);
        List<String> allow = P2PWhitelistManager.allowListOrNull();
        if (allow == null) {
            o.add("allow", JsonNull.INSTANCE);
        } else {
            JsonArray a = new JsonArray();
            for (String u : allow) a.add(u.replace("-", ""));
            o.add("allow", a);
        }
        return o.toString();
    }

    /** {@link #pardonPlayer}는 이름으로 찾지만(사람이 명령어로 칠 때 편함), 방
     * 목록 화면(RoomListScreen)의 차단 해제 클릭은 이미 UUID를 들고 있으므로
     * 이름 매칭 없이 정확히 그 항목만 지운다. */
    public static void pardonPlayerByUuid(String uuid) {
        boolean removed;
        synchronized (P2PBanManager.class) {
            removed = bannedPlayers.remove(uuid) != null;
            if (removed) {
                banListVersion++;
                save(BANNED_PLAYERS, bannedPlayers);
            }
        }
        if (!removed) return;
        QuicBridge.aclChanged();
        QuicBridge.republishPublicRoomIfActive();
        kfc.udp.client.ChatHideSync.apply(uuid, false); // 바닐라 숨기기도 같이 해제
    }

    public static synchronized void pardonIp(String ip) {
        bannedIps.remove(ip);
        save(BANNED_IPS, bannedIps);
    }

    public static synchronized boolean isPlayerBanned(String uuid) {
        return bannedPlayers.containsKey(uuid);
    }

    public static synchronized boolean hasBannedPlayers() {
        return !bannedPlayers.isEmpty();
    }
    /** 방 목록의 "차단 목록" 관리 화면(BlockedPlayersScreen)에서 쓴다 — 방
     * 목록 필터에 걸려 안 보이게 된 방장들을 이름으로라도 다시 볼 수 있게, 지금
     * 밴 목록에 있는 전체 UUID+이름. 순서는 삽입 순서 그대로(딱히 의미 없음). */
    public record BannedEntry(String uuid, String name) {}

    public static synchronized List<BannedEntry> listBannedPlayers() {
        List<BannedEntry> list = new ArrayList<>();
        for (JsonObject o : bannedPlayers.values()) {
            list.add(new BannedEntry(o.get("uuid").getAsString(), o.has("name") ? o.get("name").getAsString() : ""));
        }
        return list;
    }

    /** 방 공지 payload에 실어 보낼 밴 목록 — UUID 원문 대신 방 코드로 솔팅한 해시(쉼표로 이어붙임).
     * 원문을 실으면 로비에 붙은 누구나(패킷 캡처만으로도) 방장이 누구를 차단했는지 읽을 수 있었다.
     * 방마다 솔트가 달라 다른 방 목록과 대조해 같은 사람을 추적할 수도 없다. 단, 특정 UUID를 이미
     * 알면 "이 방장이 그 사람을 차단했나"는 확인된다 — 받는 쪽이 스스로 확인하는 구조상 불가피하다. */
    public static synchronized String encodeBannedPlayerHashes(String roomCode) {
        return String.join(",", bannedPlayers.keySet().stream().map(uuid -> banHash(roomCode, uuid)).toList());
    }

    /** 다른 방장의 payload에서 온 해시 목록에 내가 있는지 — "그 방장이 나를 밴했는지" 확인용. */
    public static boolean isBannedIn(String commaSeparatedHashes, String roomCode, String myUuid) {
        if (commaSeparatedHashes == null || commaSeparatedHashes.isEmpty() || myUuid == null) return false;
        String mine = banHash(roomCode, myUuid);
        for (String s : commaSeparatedHashes.split(",")) {
            if (s.equals(mine)) return true;
        }
        return false;
    }

    /** 지금 이 방(호스트 서버)에 접속해 있는 플레이어 — 방장 포함. 입장하려는 사람이 자기가 차단한 유저가
     * 방에 있는지 접속을 시작하기 전에 확인할 수 있게 해시로 보내 준다(RoomMembersProbe, QuicHost.sendMembers). */
    private static final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

    public static void playerJoined(UUID id) {
        onlinePlayers.add(id);
    }

    public static void playerLeft(UUID id) {
        onlinePlayers.remove(id);
    }

    /** 접속 중인 플레이어를 밴 목록과 같은 방식(방 코드로 솔팅한 해시)으로 이어붙인 것. */
    public static String encodeOnlinePlayerHashes(String roomCode) {
        return String.join(",", onlinePlayers.stream().map(id -> banHash(roomCode, id.toString())).toList());
    }

    /** 방장이 보낸 접속자 해시 중 내가 차단한 플레이어들의 이름(차단할 때 기록한 이름, 차단한 순서) — 없으면 빈 목록.
     * 해시는 방장 쪽에서 이름을 알 수 없으니 이름은 내 차단 목록에서 가져온다. */
    public static synchronized List<String> blockedPlayerNamesIn(String commaSeparatedHashes, String roomCode) {
        List<String> names = new ArrayList<>();
        if (commaSeparatedHashes == null || commaSeparatedHashes.isEmpty()) return names;
        List<String> present = List.of(commaSeparatedHashes.split(","));
        for (Map.Entry<String, JsonObject> e : bannedPlayers.entrySet()) {
            if (!present.contains(banHash(roomCode, e.getKey()))) continue;
            JsonObject o = e.getValue();
            names.add(o.has("name") && !o.get("name").getAsString().isEmpty()
                    ? o.get("name").getAsString() : e.getKey().substring(0, Math.min(8, e.getKey().length())));
        }
        return names;
    }

    // SHA-256 앞 8바이트면 충돌은 사실상 없고, UUID 원문(36자)보다 짧아 공지 URL도 줄어든다.
    /** 방 코드로 솔팅한 UUID 해시(밴 목록·접속자 목록 공용). 친구 표시(F4.0, FriendList)도 같은 걸 쓴다. */
    public static String roomHash(String roomCode, String uuid) {
        return banHash(roomCode, uuid);
    }

    private static String banHash(String roomCode, String uuid) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((roomCode + ":" + uuid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(d, 8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256은 모든 JVM에 필수로 들어 있다
        }
    }

    public static synchronized boolean isIpBanned(String ip) {
        return bannedIps.containsKey(ip);
    }

    // reason이 빠진 항목(손으로 고친 파일)이어도 로그인 거부 경로가 NPE로 죽지 않게 기본 사유를 쓴다.
    public static synchronized String getBanReason(String uuid) {
        return reasonOf(bannedPlayers.get(uuid));
    }

    public static synchronized String getIpBanReason(String ip) {
        return reasonOf(bannedIps.get(ip));
    }

    private static String reasonOf(JsonObject o) {
        if (o == null) return null;
        return o.has("reason") ? o.get("reason").getAsString() : "Banned by operator.";
    }

    // -------------------------------------------------------------------------
    // 터널 포트 ↔ 실제 IP 매핑 (QuicHost 가 호출)
    // -------------------------------------------------------------------------

    public static void registerTunnelPort(int localPort, String realIp) {
        if (realIp != null && !realIp.isEmpty()) tunnelPortToIp.put(localPort, realIp);
    }

    public static void unregisterTunnelPort(int localPort) {
        tunnelPortToIp.remove(localPort);
        tunnelPortType.remove(localPort);
    }

    public static void registerTunnelType(int localPort, Supplier<Boolean> relayed) {
        if (relayed != null) tunnelPortType.put(localPort, relayed);
    }

    /** 터널 뒤의 진짜 IP. 매핑이 없으면 소켓 주소 그대로. */
    private static String resolveRealIp(SocketAddress address) {
        if (!(address instanceof InetSocketAddress isa)) return null;
        String mapped = tunnelPortToIp.get(isa.getPort());
        if (mapped != null) return mapped;
        return isa.getAddress() != null ? isa.getAddress().getHostAddress() : null;
    }

    /** 로그인 시 기록해 둔 실제 IP (없으면 null) */
    public static String realIpOf(UUID uuid) {
        return uuidToRealIp.get(uuid);
    }

    public static void registerConnectionType(String realIp, boolean usesRelay) {
        if (realIp != null) connectionTypeByIp.put(realIp, usesRelay);
    }

    /** IP로 직접 조회 — QuicHost의 재확인(recheck) 로그에서 이전 값과 비교할 때 사용. */
    public static Boolean connectionTypeOfIp(String realIp) {
        return realIp != null ? connectionTypeByIp.get(realIp) : null;
    }

    /** 참여 메시지에 (직결 통신)/(중계 통신) 접미사를 붙일 때 사용. null이면 QUIC 터널이 아니거나 아직 모름. */
    public static Boolean connectionTypeOf(UUID uuid) {
        // 연결 단위 기록이 있으면 그게 먼저다(1.5~) — 같은 IP 의 두 사람·중계로 온 사람도 정확하다.
        Supplier<Boolean> type = uuidToType.get(uuid);
        if (type != null) {
            Boolean b = type.get();
            if (b != null) return b;
        }
        String ip = realIpOf(uuid);
        return ip != null ? connectionTypeByIp.get(ip) : null;
    }

    public static void setRoomMaxPlayers(int max) {
        roomMaxPlayers = max;
    }

    /** 0이면 방이 닫혀 있거나 정원 게이트가 없다는 뜻. */
    public static int getRoomMaxPlayers() {
        return roomMaxPlayers;
    }

    /** 정원에 세는 인원 — 개발자·서포터도 유령 취급하지 않고 그대로 센다. 정원이 실제로 차면 그
     * 특권도 뒤에 들어오는 일반 유저를 막는 데는 그대로 쓰인다(정원을 무시하고 "밀고 들어가는" 건
     * 특권을 가진 자기 자신뿐 — checkCanJoin의 !hasPerk 조건 참고). */
    public static int countedPlayers(MinecraftServer server) {
        return onlinePlayers(server).size();
    }

    // -------------------------------------------------------------------------
    // 로그인(LOGIN) 단계 밴 체크 — PlayerManagerMixin 에서 호출
    // -------------------------------------------------------------------------

    /**
     * 바닐라 {@code PlayerManager#checkCanJoin} 과 동일한 계약.
     * 거부 사유 Text 를 반환하면 플레이어는 월드에 스폰되기 전에 끊기므로
     * join/left 채팅 메시지가 남지 않는다. 통과면 null.
     */
    //? if >=26.1 {
    /*public static Component checkCanJoin(MinecraftServer server, SocketAddress address, GameProfile profile) {
    *///?} else {
    public static Text checkCanJoin(MinecraftServer server, SocketAddress address, GameProfile profile) {
    //?}
        if (server == null || profile == null) return null;
        // 방장(싱글플레이 오너)은 어떤 경우에도 막지 않는다
        if (isHost(server, profile)) return null;

        String realIp = resolveRealIp(address);
        if (realIp != null) uuidToRealIp.put(profileId(profile), realIp);
        // 이 로그인이 어느 터널 연결인지 — 직결/중계 표시와 배지(DevBadge.viaTunnelOrHost)가 이걸로 판정한다(1.5~).
        if (address instanceof InetSocketAddress isa) {
            Supplier<Boolean> type = tunnelPortType.get(isa.getPort());
            if (type != null) uuidToType.put(profileId(profile), type);
            else uuidToType.remove(profileId(profile));
        }

        // IP는 로그에 남기지 않는다 — 방장이 버그 리포트로 로그를 그대로 공유하면
        // 접속자의 실제 IP가 텍스트로 박제된다. 닉네임만으로 충분히 추적 가능.
        String uuid = profileId(profile).toString();
        if (isPlayerBanned(uuid)) {
            LOG.info("[instant-p2p] login refused (banned): {}", profileName(profile));
            return msgFb("msg.login_banned", "§cYou are banned: %s", getBanReason(uuid));
        }
        // 등급자에게 추방당한 상태라면 그 추방을 건 사람들이 전부 나가거나 풀어줄 때까지 재입장
        // 자체를 막는다(ExpelManager 클래스 주석 참고) — 방장은 이 지점에 오기 전에 이미 위에서
        // 통과됐으니 방장이 여기서 걸릴 일은 없다.
        if (ExpelManager.isExpelled(profileId(profile))) {
            LOG.info("[instant-p2p] login refused (expelled): {}", profileName(profile));
            return msgKey("instant-p2p.msg.still_expelled");
        }
        if (!P2PWhitelistManager.canJoin(uuid)) {
            LOG.info("[instant-p2p] login refused (not whitelisted): {}", profileName(profile));
            return msgKey("instant-p2p.msg.not_whitelisted");
        }
        if (realIp != null && isIpBanned(realIp)) {
            LOG.info("[instant-p2p] login refused (ip banned): {}", profileName(profile));
            return msgFb("msg.login_ip_banned", "§cYour IP is banned: %s", getIpBanReason(realIp));
        }
        if (loginTooOften(profileId(profile))) {
            LOG.info("[instant-p2p] login refused (rejoining too often): {}", profileName(profile));
            return msgFb("msg.login_throttled", "§cYou are reconnecting too often — please wait a couple of minutes");
        }
        // 아래 정원 판정(hasPerk)과, 바로 뒤에 계산돼 캐시되는 탭 목록 배지가 이 순간의 등급
        // 목록으로 결정된다 — 그래서 여기서 짧게 기다려 roles.json을 최신으로 맞춘다. 이게 없으면
        // 새로고침이 비동기라 첫 접속만 낡은 등급으로 처리되고 두 번째 접속부터 맞았다.
        // (1.5~: 밴·화이트리스트로 어차피 거절될 사람 때문에 서버에 묻지 않게 거절 판정 뒤로 옮겼다.)
        RoomRoles.ensureFreshForLogin(server);
        // 정원 초과도 같은 지점에서 막아야 join/left 로그가 안 남는다 — 제작자·서포터는 정원을 무시한다
        int max = roomMaxPlayers;
        if (max > 0 && !kfc.udp.client.DevBadge.hasPerk(profileId(profile)) && countedPlayers(server) >= max) {
            return msgKey("instant-p2p.msg.room_full", max);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // 커맨드 등록
    // -------------------------------------------------------------------------

    /** openToLan 후 커맨드 매니저 재초기화로 날아간 명령어 재등록 */
    public static void reregisterToDispatcher(MinecraftServer server) {
        //? if >=26.1 {
        /*var dispatcher = server.getCommands().getDispatcher();
        *///?} else {
        var dispatcher = server.getCommandManager().getDispatcher();
        //?}
        registerBanCommands(dispatcher);
    }

    //? if >=26.1 {
    /*private static void registerBanCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
    *///?} else {
    private static void registerBanCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
    //?}
        // ban <player> [<reason>]
        dispatcher.register(literal("ban")
                .requires(requireAdminOrHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(BAN_CANDIDATES)
                        .executes(ctx -> executeBan(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBan(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason"))))));

        // ban-ip <player|ip> [<reason>]
        dispatcher.register(literal("ban-ip")
                .requires(requireAdminOrHost())
                .then(argument("target", StringArgumentType.word())
                        .executes(ctx -> executeBanIp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "target"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBanIp(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target"),
                                        StringArgumentType.getString(ctx, "reason"))))));

        // pardon <player>
        dispatcher.register(literal("pardon")
                .requires(requireAdminOrHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(BANNED_PLAYER_NAMES)
                        .executes(ctx -> executePardon(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))));

        // pardon-ip <ip>
        dispatcher.register(literal("pardon-ip")
                .requires(requireAdminOrHost())
                .then(argument("ip", StringArgumentType.word())
                        .suggests(BANNED_IPS_LIST)
                        .executes(ctx -> executePardonIp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "ip")))));

        // kick <player> [<reason>]
        dispatcher.register(literal("kick")
                .requires(requireAdminOrHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYERS)
                        .executes(ctx -> executeKick(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeKick(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason"))))));

        // op <player> — 방장 전용(requireHost)
        dispatcher.register(literal("op")
                .requires(requireHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(OP_CANDIDATES)
                        .executes(ctx -> executeOp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))));

        // 입장 승인제(1.5~, AdmitGate) — admit <player> / deny <player> / unadmit <player>. 방장 전용.
        dispatcher.register(literal("admit")
                .requires(requireHostNoConfirm())
                .then(argument("player", StringArgumentType.word())
                        .suggests(PENDING_NAMES)
                        .executes(ctx -> executeAdmit(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), 0))));
        dispatcher.register(literal("deny")
                .requires(requireHostNoConfirm())
                .then(argument("player", StringArgumentType.word())
                        .suggests(PENDING_NAMES)
                        .executes(ctx -> executeAdmit(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), 1))));
        dispatcher.register(literal("unadmit")
                .requires(requireHostNoConfirm())
                .then(argument("player", StringArgumentType.word())
                        .suggests(ADMITTED_NAMES)
                        .executes(ctx -> executeAdmit(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), 2))));

        // deop <player> — 방장 전용
        dispatcher.register(literal("deop")
                .requires(requireHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(OP_NAMES)
                        .executes(ctx -> executeDeop(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))));

        // "ban"/"ban-ip"/"pardon"/"pardon-ip"/"kick"/"op"/"deop"은 바닐라도 등록하는
        // 이름이라, 위 .requires()가 addChild() 병합 과정에서 조용히 버려지고 바닐라 쪽
        // requirement가 그대로 남아있을 수 있다 — 실제로 트리에 남은 노드를 찾아
        // 강제로 덮어쓴다. CommandNodeAccessor 클래스 주석 참고.
        forceRequirement(dispatcher, "ban", requireAdminOrHost());
        forceRequirement(dispatcher, "ban-ip", requireAdminOrHost());
        forceRequirement(dispatcher, "pardon", requireAdminOrHost());
        forceRequirement(dispatcher, "pardon-ip", requireAdminOrHost());
        forceRequirement(dispatcher, "kick", requireAdminOrHost());
        // 예전엔 op/deop도 위와 같은 조건으로 덮어써서, 방장 전용이라는 .requires(requireHost())가 무시됐다.
        forceRequirement(dispatcher, "op", requireHost());
        forceRequirement(dispatcher, "deop", requireHost());
    }

    //? if >=26.1 {
    /*private static void forceRequirement(CommandDispatcher<CommandSourceStack> dispatcher, String name,
                                         Predicate<CommandSourceStack> requirement) {
        var node = dispatcher.getRoot().getChild(name);
        if (node instanceof kfc.udp.client.mixin.CommandNodeAccessor accessor) {
            accessor.kfcudp$setRequirement(requirement);
        }
    }
    *///?} else {
    private static void forceRequirement(CommandDispatcher<ServerCommandSource> dispatcher, String name,
                                         Predicate<ServerCommandSource> requirement) {
        var node = dispatcher.getRoot().getChild(name);
        if (node instanceof kfc.udp.client.mixin.CommandNodeAccessor accessor) {
            accessor.kfcudp$setRequirement(requirement);
        }
    }
    //?}

    public static void registerCommands() {
        load();
        P2POpManager.load();
        AdmitGate.load();
        AdmitGate.registerTick();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerBanCommands(dispatcher));
    }

    // -------------------------------------------------------------------------
    // 커맨드 실행
    // -------------------------------------------------------------------------

    // 실행부는 에라 공용 — 시그니처 줄만 분기하고 본문은 한 벌만 둔다(registerBanCommands와 같은 방식).
    // Yarn/Mojang에서 이름만 다른 서버 API는 위쪽 "버전 호환 헬퍼"의 feedback/isHosting/
    // playerByUuid/playerByName/onlinePlayers/disconnect로 감싸 뒀다.
    //? if >=26.1 {
    /*private static int executeBan(CommandSourceStack src, String name, String reason) {
    *///?} else {
    private static int executeBan(ServerCommandSource src, String name, String reason) {
    //?}
        MinecraftServer server = src.getServer();

        // LAN이 열려 있지 않으면 ban 불가
        if (!isHosting(server)) {
            feedbackFb(src, "cmd.not_hosting", "§cOnly available while hosting a room.");
            return 0;
        }

        // 접속 중이 아니어도 Mojang API/usercache로 조회 (바닐라 ban과 동일)
        ProfileLookup lookup = lookupProfile(server, name);
        if (lookup == null) {
            feedbackFb(src, "cmd.player_not_found", "§cCould not find a player named %s", name);
            return 0;
        }

        // 서버 오너(호스터)는 ban 불가
        GameProfile syntheticProfile = new GameProfile(lookup.id(), lookup.name());
        if (isHost(server, syntheticProfile)) {
            feedbackFb(src, "cmd.ban_owner", "§cYou cannot ban the room owner.");
            return 0;
        }
        if (isPlayerBanned(lookup.id().toString())) { // 바닐라처럼 "바뀐 게 없다"고 알린다(1.5~)
            feedbackFb(src, "cmd.ban_already", "§cNothing changed. The player is already banned");
            return 0;
        }

        String r = reason != null ? reason : "Banned by operator.";
        banPlayer(lookup.id().toString(), lookup.name(), r);
        var online = playerByUuid(server, lookup.id());
        if (online != null) {
            disconnectFb(online, "msg.disc_banned", "§cYou have been banned: %s", r);
        }
        feedbackFb(src, "cmd.banned", "§aBanned %s", lookup.name());
        return 1;
    }

    //? if >=26.1 {
    /*private static int executeBanIp(CommandSourceStack src, String target, String reason) {
    *///?} else {
    private static int executeBanIp(ServerCommandSource src, String target, String reason) {
    //?}
        MinecraftServer server = src.getServer();

        // LAN이 열려 있지 않으면 ban-ip 불가
        if (!isHosting(server)) {
            feedbackFb(src, "cmd.not_hosting", "§cOnly available while hosting a room.");
            return 0;
        }

        // 플레이어 이름으로 입력된 경우 IP로 변환, 아니면 직접 IP로 취급
        String ip = target;
        var p = playerByName(server, target);
        if (p != null) {
            // 서버 오너(호스터)는 ban-ip 불가
            if (isHost(server, p.getGameProfile())) {
                feedbackFb(src, "cmd.ban_owner", "§cYou cannot ban the room owner.");
                return 0;
            }
            // p.getIp() 는 터널 때문에 항상 127.0.0.1 → 로그인 때 기록한 실제 IP 사용
            ip = realIpOf(profileId(p.getGameProfile()));
            if (ip == null || ip.startsWith("127.")) {
                feedbackFb(src, "cmd.ip_unresolved", "§cCannot resolve the real IP of %s.", target);
                return 0;
            }
        }
        if (isIpBanned(ip)) {
            feedbackFb(src, "cmd.ip_ban_already", "§cNothing changed. That IP is already banned");
            return 0;
        }

        String r = reason != null ? reason : "Banned by operator.";
        banIp(ip, r);
        final String finalIp = ip;
        for (var sp : onlinePlayers(server)) {
            if (isHost(server, sp.getGameProfile())) continue;
            if (finalIp.equals(realIpOf(profileId(sp.getGameProfile())))) {
                disconnectFb(sp, "msg.disc_ip_banned", "§cYour IP has been banned: %s", r);
            }
        }
        // 이름으로 밴했으면 IP 원문은 채팅에 띄우지 않는다(방송 중 노출 방지) — 꼬리표만.
        if (p != null) feedbackFb(src, "cmd.ip_banned_player", "§aBanned the IP of %s (tag %s)", target, ipTag(finalIp));
        else feedbackFb(src, "cmd.ip_banned", "§aBanned IP: %s", finalIp);
        return 1;
    }

    //? if >=26.1 {
    /*private static int executePardon(CommandSourceStack src, String name) {
    *///?} else {
    private static int executePardon(ServerCommandSource src, String name) {
    //?}
        if (bannedNames().stream().noneMatch(n -> n.equalsIgnoreCase(name))) {
            feedbackFb(src, "cmd.pardon_none", "§cNothing changed. The player is not banned");
            return 0;
        }
        pardonPlayer(name);
        feedbackFb(src, "cmd.unbanned", "§aUnbanned %s", name);
        return 1;
    }

    //? if >=26.1 {
    /*private static int executePardonIp(CommandSourceStack src, String ip) {
    *///?} else {
    private static int executePardonIp(ServerCommandSource src, String ip) {
    //?}
        String banned = resolveBannedIp(ip); // IP 원문 또는 자동완성에 보인 꼬리표
        if (!isIpBanned(banned)) {
            feedbackFb(src, "cmd.pardon_ip_none", "§cNothing changed. That IP is not banned");
            return 0;
        }
        pardonIp(banned);
        feedbackFb(src, "cmd.ip_unbanned", "§aUnbanned IP: %s", ip);
        return 1;
    }

    //? if >=26.1 {
    /*private static int executeKick(CommandSourceStack src, String name, String reason) {
    *///?} else {
    private static int executeKick(ServerCommandSource src, String name, String reason) {
    //?}
        MinecraftServer server = src.getServer();

        if (!isHosting(server)) {
            feedbackFb(src, "cmd.not_hosting", "§cOnly available while hosting a room.");
            return 0;
        }

        var target = playerByName(server, name);
        if (target == null) {
            feedbackFb(src, "cmd.player_not_found", "§cCould not find a player named %s", name);
            return 0;
        }
        if (isHost(server, target.getGameProfile())) {
            feedbackFb(src, "cmd.kick_owner", "§cYou cannot kick the room owner.");
            return 0;
        }

        String r = reason != null ? reason : "Kicked by an operator.";
        disconnect(target, "§c" + r);
        feedbackFb(src, "cmd.kicked", "§aKicked %s", profileName(target.getGameProfile()));
        return 1;
    }

    //? if >=26.1 {
    /*private static int executeOp(CommandSourceStack src, String name) {
    *///?} else {
    private static int executeOp(ServerCommandSource src, String name) {
    //?}
        MinecraftServer server = src.getServer();

        if (!isHosting(server)) {
            feedbackFb(src, "cmd.not_hosting", "§cOnly available while hosting a room.");
            return 0;
        }

        ProfileLookup lookup = lookupProfile(server, name);
        if (lookup == null) {
            feedbackFb(src, "cmd.player_not_found", "§cCould not find a player named %s", name);
            return 0;
        }

        // 월드 OP 목록 + 이 모드의 ops.json(1.5~, P2POpManager — 다음에 방을 열어도 OP 가 이어진다) 둘 다 있으면 할 일이 없다.
        boolean inWorld = isOp(server, new GameProfile(lookup.id(), lookup.name()));
        boolean inFile = P2POpManager.contains(lookup.id().toString());
        if (inWorld && inFile) {
            feedbackFb(src, "cmd.op_already", "§cNothing changed. The player already is an operator");
            return 0;
        }
        grantOpAtLevel(server, lookup, 2);
        P2POpManager.noteGranted(lookup.id().toString());
        P2POpManager.add(lookup.id().toString(), lookup.name());
        feedbackFb(src, "cmd.opped", "§aMade %s a server operator", lookup.name());
        return 1;
    }

    /** mode 0 = admit(승인), 1 = deny(거절·내보내기), 2 = unadmit(승인 명단에서 빼기). */
    //? if >=26.1 {
    /*private static int executeAdmit(CommandSourceStack src, String name, int mode) {
    *///?} else {
    private static int executeAdmit(ServerCommandSource src, String name, int mode) {
    //?}
        MinecraftServer server = src.getServer();
        if (!isHosting(server)) {
            feedbackFb(src, "cmd.not_hosting", "§cOnly available while hosting a room.");
            return 0;
        }
        int r = mode == 0 ? AdmitGate.admit(server, name) : mode == 1 ? AdmitGate.deny(server, name) : AdmitGate.untrust(name);
        if (r == 0) {
            if (mode == 2) feedbackFb(src, "cmd.unadmit_none", "§cNothing changed. %s is not on the admitted list", name);
            else feedbackFb(src, "cmd.admit_none", "§cNobody named %s is waiting to be admitted", name);
            return 0;
        }
        feedbackFb(src,
                mode == 0 ? "cmd.admit_ok" : mode == 1 ? "cmd.deny_ok" : "cmd.unadmit_ok",
                mode == 0 ? "§aAdmitted %s" : mode == 1 ? "§aRemoved %s" : "§aRemoved %s from the admitted list",
                name);
        return 1;
    }

    //? if >=26.1 {
    /*private static int executeDeop(CommandSourceStack src, String name) {
    *///?} else {
    private static int executeDeop(ServerCommandSource src, String name) {
    //?}
        MinecraftServer server = src.getServer();

        ProfileLookup lookup = lookupProfile(server, name);
        if (lookup == null) {
            feedbackFb(src, "cmd.player_not_found", "§cCould not find a player named %s", name);
            return 0;
        }

        boolean inWorld = isOp(server, new GameProfile(lookup.id(), lookup.name()));
        boolean inFile = P2POpManager.contains(lookup.id().toString());
        if (!inWorld && !inFile) {
            feedbackFb(src, "cmd.deop_none", "§cNothing changed. The player is not an operator");
            return 0;
        }
        if (inWorld) revokeOp(server, lookup);
        P2POpManager.remove(lookup.id().toString());
        feedbackFb(src, "cmd.deopped", "§aMade %s no longer a server operator", lookup.name());
        return 1;
    }
}
