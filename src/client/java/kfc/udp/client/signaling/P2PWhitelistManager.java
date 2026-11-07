package kfc.udp.client.signaling;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//? if >=26.1 {
/*import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
*///?} else {
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
//?}

/**
 * 바닐라 화이트리스트(whitelist on/off/add/remove/list)를 그대로 옮겨온 것.
 * {@code add}는 {@link P2PBanManager#lookupProfile}로 접속 이력 없는 플레이어도
 * Mojang API/usercache.json 캐시를 통해 이름만으로 찾는다(바닐라와 동일).
 * <p>
 * 방장은 {@link P2PBanManager#checkCanJoin}에서 이미 모든 체크보다 먼저 통과되므로
 * 화이트리스트가 켜져 있어도 항상 들어올 수 있다.
 */
public class P2PWhitelistManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-whitelist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_DIR      = Path.of("config", "instant-p2p");
    private static final Path WHITELIST_FILE  = CONFIG_DIR.resolve("whitelist.json");

    /** uuid → {uuid,name} */
    private static final Map<String, JsonObject> whitelist = new LinkedHashMap<>();
    /** 명단은 저장되지만 on/off는 방 열 때마다 꺼진 상태로 시작한다 — 세션 간 지속 안 함. */
    private static volatile boolean enabled = false;

    // -------------------------------------------------------------------------
    // 자동완성 제공자
    // -------------------------------------------------------------------------

    //? if >=26.1 {
    /*private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (ctx, builder) -> {
                for (ServerPlayer sp : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                    // 이미 명단에 있는 사람은 빼고 제안한다(1.5~)
                    if (whitelist.containsKey(P2PBanManager.profileId(sp.getGameProfile()).toString())) continue;
                    builder.suggest(P2PBanManager.profileName(sp.getGameProfile()));
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> WHITELISTED_PLAYER_NAMES =
            (ctx, builder) -> {
                for (JsonObject o : whitelist.values()) {
                    if (o.has("name")) builder.suggest(o.get("name").getAsString());
                }
                return builder.buildFuture();
            };
    *///?} else {
    private static final SuggestionProvider<ServerCommandSource> ONLINE_PLAYERS =
            (ctx, builder) -> {
                for (ServerPlayerEntity sp : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                    // 이미 명단에 있는 사람은 빼고 제안한다(1.5~)
                    if (whitelist.containsKey(P2PBanManager.profileId(sp.getGameProfile()).toString())) continue;
                    builder.suggest(P2PBanManager.profileName(sp.getGameProfile()));
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<ServerCommandSource> WHITELISTED_PLAYER_NAMES =
            (ctx, builder) -> {
                for (JsonObject o : whitelist.values()) {
                    if (o.has("name")) builder.suggest(o.get("name").getAsString());
                }
                return builder.buildFuture();
            };
    //?}

    // -------------------------------------------------------------------------
    // 데이터 로드 / 저장
    // -------------------------------------------------------------------------

    public static void load() {
        whitelist.clear();
        enabled = false;
        JsonArray arr = JsonFiles.readArray(WHITELIST_FILE); // 깨진 파일은 .corrupt 로 치운다(1.5~)
        if (arr == null) return;
        for (JsonElement e : arr) {
            try {
                JsonObject o = e.getAsJsonObject();
                if (o.has("uuid")) whitelist.put(o.get("uuid").getAsString(), o);
            } catch (Exception ex) {
                LOG.warn("[instant-p2p] skipped a bad whitelist entry");
            }
        }
    }

    private static void saveWhitelist() {
        JsonArray arr = new JsonArray();
        whitelist.values().forEach(arr::add);
        JsonFiles.writeAtomic(WHITELIST_FILE, arr);
    }

    // -------------------------------------------------------------------------
    // 화이트리스트 조작
    // -------------------------------------------------------------------------

    public static void addPlayer(String uuid, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        whitelist.put(uuid, o);
        saveWhitelist();
        kfc.udp.client.quic.QuicBridge.aclChanged(); // 서버의 허용 목록도 맞춘다(1.5~)
    }

    public static boolean removePlayer(String name) {
        boolean removed = whitelist.entrySet().removeIf(e ->
                e.getValue().get("name").getAsString().equalsIgnoreCase(name));
        if (removed) {
            saveWhitelist();
            kfc.udp.client.quic.QuicBridge.aclChanged();
        }
        return removed;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        kfc.udp.client.quic.QuicBridge.aclChanged();
    }

    /** 서버에 올릴 허용 목록(UUID) — 화이트리스트가 꺼져 있으면 null(= 누구나). P2PBanManager.aclJson 이 쓴다. */
    public static List<String> allowListOrNull() {
        return enabled ? new ArrayList<>(whitelist.keySet()) : null;
    }

    /** 화이트리스트가 켜져 있고 명단에 있는지 — 입장 승인제가 명단에 있는 사람은 바로 들여보낸다(AdmitGate). */
    public static boolean isWhitelisted(String uuid) {
        return enabled && whitelist.containsKey(uuid);
    }

    /** {@link P2PBanManager#checkCanJoin}에서 호출 — 화이트리스트가 꺼져 있으면 항상 통과. */
    public static boolean canJoin(String uuid) {
        return !enabled || whitelist.containsKey(uuid);
    }

    // -------------------------------------------------------------------------
    // 커맨드 등록
    // -------------------------------------------------------------------------

    /** openToLan 후 커맨드 매니저 재초기화로 날아간 명령어 재등록 */
    public static void reregisterToDispatcher(MinecraftServer server) {
        //? if >=26.1 {
        /*registerCommands(server.getCommands().getDispatcher());
        *///?} else {
        registerCommands(server.getCommandManager().getDispatcher());
        //?}
    }

    public static void registerCommands() {
        load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
    }

    //? if >=26.1 {
    /*private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
    *///?} else {
    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
    //?}
        dispatcher.register(literal("whitelist")
                .requires(P2PBanManager.requireAdminOrHost())
                .then(literal("on").executes(ctx -> executeSetEnabled(ctx.getSource(), true)))
                .then(literal("off").executes(ctx -> executeSetEnabled(ctx.getSource(), false)))
                .then(literal("list").executes(ctx -> executeList(ctx.getSource())))
                .then(literal("add")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .executes(ctx -> executeAdd(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(literal("remove")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(WHITELISTED_PLAYER_NAMES)
                                .executes(ctx -> executeRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"))))));

        // "whitelist"도 바닐라가 등록하는 이름이라 위 .requires()가 addChild() 병합
        // 과정에서 조용히 버려질 수 있다 — 실제로 트리에 남은 노드를 찾아 강제로
        // 덮어쓴다. P2PBanManager.forceRequirement / CommandNodeAccessor 주석 참고.
        var node = dispatcher.getRoot().getChild("whitelist");
        if (node instanceof kfc.udp.client.mixin.CommandNodeAccessor accessor) {
            accessor.kfcudp$setRequirement(P2PBanManager.requireAdminOrHost());
        }
    }

    // -------------------------------------------------------------------------
    // 커맨드 실행
    // -------------------------------------------------------------------------

    //? if >=26.1 {
    /*private static int executeSetEnabled(CommandSourceStack src, boolean value) {
        setEnabled(value);
        src.sendSuccess(() -> value ? P2PBanManager.msgFb("cmd.wl_on", "§aWhitelist is now enabled.")
                : P2PBanManager.msgFb("cmd.wl_off", "§aWhitelist is now disabled."), false);
        return 1;
    }

    private static int executeList(CommandSourceStack src) {
        if (whitelist.isEmpty()) {
            src.sendSuccess(() -> P2PBanManager.msgFb("cmd.wl_none", "§7There are no whitelisted players."), false);
            return 0;
        }
        StringBuilder names = new StringBuilder();
        for (JsonObject o : whitelist.values()) {
            if (names.length() > 0) names.append(", ");
            names.append(o.get("name").getAsString());
        }
        src.sendSuccess(() -> P2PBanManager.msgFb("cmd.wl_list", "§7There are %s whitelisted player(s): %s",
                String.valueOf(whitelist.size()), names.toString()), false);
        return whitelist.size();
    }

    private static int executeAdd(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        // 접속 중이 아니어도 Mojang API/usercache로 조회 (바닐라 whitelist add와 동일)
        P2PBanManager.ProfileLookup lookup = P2PBanManager.lookupProfile(server, name);
        if (lookup == null) {
            src.sendSuccess(() -> P2PBanManager.msgFb("cmd.player_not_found", "§cCould not find a player named %s", name), false);
            return 0;
        }
        if (whitelist.containsKey(lookup.id().toString())) {
            src.sendSuccess(() -> P2PBanManager.msgFb("cmd.wl_already", "§cPlayer is already whitelisted"), false);
            return 0;
        }

        addPlayer(lookup.id().toString(), lookup.name());
        src.sendSuccess(() -> P2PBanManager.msgFb("cmd.wl_added", "§aAdded %s to the whitelist", lookup.name()), false);
        return 1;
    }

    private static int executeRemove(CommandSourceStack src, String name) {
        boolean removed = removePlayer(name);
        if (removed) {
            src.sendSuccess(() -> P2PBanManager.msgFb("cmd.wl_removed", "§aRemoved %s from the whitelist", name), false);
            return 1;
        } else {
            src.sendSuccess(() -> P2PBanManager.msgFb("cmd.wl_not", "§cPlayer is not whitelisted: %s", name), false);
            return 0;
        }
    }
    *///?} else {
    private static int executeSetEnabled(ServerCommandSource src, boolean value) {
        setEnabled(value);
        src.sendFeedback(() -> value ? P2PBanManager.msgFb("cmd.wl_on", "§aWhitelist is now enabled.")
                : P2PBanManager.msgFb("cmd.wl_off", "§aWhitelist is now disabled."), false);
        return 1;
    }

    private static int executeList(ServerCommandSource src) {
        if (whitelist.isEmpty()) {
            src.sendFeedback(() -> P2PBanManager.msgFb("cmd.wl_none", "§7There are no whitelisted players."), false);
            return 0;
        }
        StringBuilder names = new StringBuilder();
        for (JsonObject o : whitelist.values()) {
            if (names.length() > 0) names.append(", ");
            names.append(o.get("name").getAsString());
        }
        src.sendFeedback(() -> P2PBanManager.msgFb("cmd.wl_list", "§7There are %s whitelisted player(s): %s",
                String.valueOf(whitelist.size()), names.toString()), false);
        return whitelist.size();
    }

    private static int executeAdd(ServerCommandSource src, String name) {
        MinecraftServer server = src.getServer();
        // 접속 중이 아니어도 Mojang API/usercache로 조회 (바닐라 whitelist add와 동일)
        P2PBanManager.ProfileLookup lookup = P2PBanManager.lookupProfile(server, name);
        if (lookup == null) {
            src.sendFeedback(() -> P2PBanManager.msgFb("cmd.player_not_found", "§cCould not find a player named %s", name), false);
            return 0;
        }
        if (whitelist.containsKey(lookup.id().toString())) {
            src.sendFeedback(() -> P2PBanManager.msgFb("cmd.wl_already", "§cPlayer is already whitelisted"), false);
            return 0;
        }

        addPlayer(lookup.id().toString(), lookup.name());
        src.sendFeedback(() -> P2PBanManager.msgFb("cmd.wl_added", "§aAdded %s to the whitelist", lookup.name()), false);
        return 1;
    }

    private static int executeRemove(ServerCommandSource src, String name) {
        boolean removed = removePlayer(name);
        if (removed) {
            src.sendFeedback(() -> P2PBanManager.msgFb("cmd.wl_removed", "§aRemoved %s from the whitelist", name), false);
            return 1;
        } else {
            src.sendFeedback(() -> P2PBanManager.msgFb("cmd.wl_not", "§cPlayer is not whitelisted: %s", name), false);
            return 0;
        }
    }
    //?}
}
