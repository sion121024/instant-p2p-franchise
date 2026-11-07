package kfc.udp.client.signaling;

import kfc.udp.client.anticheat.AcConfig;
import kfc.udp.client.anticheat.Box;
import kfc.udp.client.anticheat.Combat;
import kfc.udp.client.anticheat.Engine;
import kfc.udp.client.anticheat.MoveEnv;
import kfc.udp.client.anticheat.MoveInput;
import kfc.udp.client.anticheat.PlayerData;
import kfc.udp.client.anticheat.World;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//?}

/**
 * 안티치트 엔진({@link Engine}, 마크 클래스 없는 순수 판정)과 마인크래프트를 잇는 층.
 * <p>
 * <b>무엇을 어디서 받나</b>
 * <ul>
 *   <li>이동·입력·클라 틱 끝·스윙·질주·능력·인벤 클릭 — {@code AcServerPlayMixin}(패킷 처리 직전, 서버 스레드)</li>
 *   <li>퐁·보내는 넉백/텔레포트 — {@code AcServerCommonMixin}</li>
 *   <li>채굴 시작/중단/완료 — {@code AcBlockBreakMixin}</li>
 *   <li>공격·블록 우클릭 — Fabric {@code AttackEntityCallback}·{@code UseBlockCallback}</li>
 *   <li>매 서버 틱 — 엔티티 위치 기록(리치 랙 보정), 트랜잭션 핑 송신, 알림 전달, 대기 중 강퇴</li>
 * </ul>
 * <b>방장은 검사하지 않는다</b>(P2PBanManager.isHost) — 서버를 도는 게 방장 PC라 방장을 막을 방법도 이유도 없다.
 * 목적은 "방장이 게스트로부터 자기 세계를 지키는 것"이다.
 */
public final class AntiCheatHooks {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-anticheat");

    private AntiCheatHooks() {}

    /** 서버 스레드 — 믹스인이 네트워크 스레드에서 한 번 더 불리는 것(바닐라가 메인 스레드로 다시 넘김)을 거른다. */
    public static volatile Thread serverThread;
    private static volatile MinecraftServer lastServer;
    private static long tick;
    private static long lastTickStartNs;
    private static final Map<UUID, ArrayDeque<Long>> CHAT = new ConcurrentHashMap<>();

    public static Engine engine() {
        return Engine.INSTANCE;
    }

    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    private static boolean on() {
        return P2PConfig.getAntiCheat().enabled;
    }

    public static boolean isEnabled() {
        return on();
    }

    /** 설정 화면에서 저장할 때. */
    public static void applySettings(AntiCheatSettings s) {
        P2PConfig.setAntiCheat(s);
        engine().setConfig(s.toEngineConfig());
        if (!s.enabled) engine().resetAll();
        LOG.info("[anticheat] 설정 적용: 사용={} 민감도={} 처리={}", s.enabled, s.sensitivity, s.action);
    }

    /** KfcudpClient.onInitializeClient에서 1회. */
    public static void register() {
        engine().setConfig(P2PConfig.getAntiCheat().toEngineConfig());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverThread = Thread.currentThread();
            lastServer = server;
            tick = 0;
            engine().resetAll();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            engine().resetAll();
            serverThread = null;
            lastServer = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            serverThread = Thread.currentThread();
            long now = System.nanoTime();
            // 방장 서버가 250ms 넘게 멈췄다 풀리면 그동안 쌓인 패킷이 몰려 처리된다 — 타이머 오탐 방지
            if (lastTickStartNs != 0 && now - lastTickStartNs > 250_000_000L) engine().onServerStall();
            lastTickStartNs = now;
            engine().serverTick(++tick);
        });
        ServerTickEvents.END_SERVER_TICK.register(AntiCheatHooks::onEndTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            engine().quit(uuidOf(handler.player));
            CHAT.remove(uuidOf(handler.player));
            AcIntegrity.forget(uuidOf(handler.player));
        });
        AttackEntityCallback.EVENT.register(AntiCheatHooks::onAttack);
        UseBlockCallback.EVENT.register(AntiCheatHooks::onUseBlock);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(AntiCheatHooks::onChat);
        AcIntegrity.registerServer();
        LOG.info("[anticheat] registered");
    }

    /** 채팅 도배 — 8초에 6개 넘으면 그 메시지만 막는다(강퇴하지 않음). */
    private static boolean chatSpam(UUID id) {
        if (!on() || !P2PConfig.getAntiCheat().chatSpam) return false;
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = CHAT.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > 8000) q.pollFirst();
            return q.size() > 6;
        }
    }

    /** 패킷 밖(무결성 응답 등)에서 정한 강퇴 — 다음 서버 틱에 실행. */
    public static void kickLater(UUID id, String check) {
        engine().requestKick(id, check);
    }

    static String reasonKey(String check) {
        return "instant-p2p.anticheat.check." + check;
    }

    //? if >=26.1 {
    /*private static UUID uuidOf(ServerPlayer p) { return p.getUUID(); }

    private static boolean guest(ServerPlayer p) {
        return on() && lastServer != null && !P2PBanManager.isHost(lastServer, p);
    }

    private static void onJoin(ServerPlayer p) {
        if (lastServer == null || P2PBanManager.isHost(lastServer, p)) return;
        PlayerData d = engine().join(p.getUUID(), P2PBanManager.profileName(p.getGameProfile()), p.getId());
        if (d != null) AcIntegrity.ask(p);
    }

    private static void onEndTick(MinecraftServer server) {
        lastServer = server;
        if (!on()) { drainAlerts(server); return; }
        Set<Integer> seen = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!seen.add(p.getId())) continue;
            engine().recordEntity(p.getId(), box(p.getBoundingBox()));
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (P2PBanManager.isHost(server, p)) continue;
            for (net.minecraft.world.entity.Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(8.0, 6.0, 8.0),
                    ent -> ent instanceof net.minecraft.world.entity.LivingEntity)) {
                if (seen.add(e.getId())) engine().recordEntity(e.getId(), box(e.getBoundingBox()));
            }
            int id = engine().pingFor(p.getUUID());
            if (id != 0) p.connection.send(new net.minecraft.network.protocol.common.ClientboundPingPacket(id));
            String k = engine().takeKick(p.getUUID());
            if (k != null) kick(p, k);
        }
        AcIntegrity.tick(server, tick);
        if (tick % 20 == 0) engine().checkIntegrityTimeouts();
        drainAlerts(server);
    }

    static Box box(net.minecraft.world.phys.AABB b) {
        return new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    // ── 이동 (AcServerPlayMixin) ──

    // @return null이면 바닐라 처리 계속. 아니면 결과(셋백이면 믹스인이 텔레포트하고 패킷을 버린다). 
    public static Engine.Out onMove(ServerPlayer p, net.minecraft.network.protocol.game.ServerboundMovePlayerPacket pk,
                                    boolean awaitingTeleport) {
        if (!guest(p)) return null;
        double nx = pk.getX(p.getX()), ny = pk.getY(p.getY()), nz = pk.getZ(p.getZ());
        MoveInput m = new MoveInput(pk.hasPosition(), pk.hasRotation(), nx, ny, nz, pk.getYRot(p.getYRot()), pk.getXRot(p.getXRot()),
                pk.isOnGround(), pk.horizontalCollision());
        MoveEnv env = env(p, nx - p.getX(), ny - p.getY(), nz - p.getZ(), awaitingTeleport);
        Engine.Out o = engine().onMove(p.getUUID(), m, env);
        handleOut(p, o);
        return o;
    }

    private static MoveEnv env(ServerPlayer p, double dx, double dy, double dz, boolean awaitingTeleport) {
        MoveEnv e = new MoveEnv();
        var ab = p.getAbilities();
        e.creative = p.isCreative(); e.spectator = p.isSpectator(); e.mayFly = ab.mayfly; e.flying = ab.flying;
        e.flySpeed = ab.getFlyingSpeed();
        e.passenger = p.isPassenger(); e.sleeping = p.isSleeping(); e.gliding = p.isFallFlying(); e.riptide = p.isAutoSpinAttack();
        e.dead = p.isDeadOrDying();
        e.awaitingTeleport = awaitingTeleport;
        var level = p.level();
        var inState = p.getInBlockState();
        e.inWater = p.isInWater(); e.inLava = p.isInLava(); e.underWater = p.isUnderWater();
        e.inPowderSnow = p.isInPowderSnow || p.wasInPowderSnow;
        e.onClimbable = p.onClimbable();
        e.inBubbleColumn = inState.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN);
        e.inScaffolding = inState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
        Object so = ((kfc.udp.client.anticheat.AcEntityAccess) p).kfcudp$stuckMultiplier();
        net.minecraft.world.phys.Vec3 stuck = so instanceof net.minecraft.world.phys.Vec3 v ? v : null;
        e.stuck = stuck != null && stuck.lengthSqr() > 1.0E-7
                || inState.is(net.minecraft.world.level.block.Blocks.COBWEB)
                || inState.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH);
        var below = level.getBlockState(p.getBlockPosBelowThatAffectsMyMovement());
        e.blockFriction = modFriction(below.getBlock().getFriction(), frictionModifier(p));
        e.airDragModifier = airDragModifier(p);
        e.blockSpeedFactor = ((kfc.udp.client.anticheat.AcEntityAccess) p).kfcudp$blockSpeedFactor();
        e.blockJumpFactor = ((kfc.udp.client.anticheat.AcEntityAccess) p).kfcudp$blockJumpFactor();
        var on = level.getBlockState(p.getOnPos()).getBlock();
        e.bounceFactor = on instanceof net.minecraft.world.level.block.SlimeBlock ? 1.0f
                : on instanceof net.minecraft.world.level.block.BedBlock ? 0.66f : 0f;
        e.movementSpeed = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        e.jumpStrength = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
        e.gravity = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY);
        e.stepHeight = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        e.sneakingSpeed = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
        e.waterMovementEfficiency = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.WATER_MOVEMENT_EFFICIENCY);
        e.sprinting = p.isSprinting();
        e.sneakingPose = p.isCrouching() || p.isVisuallyCrawling();
        e.usingItem = p.isUsingItem();
        var jb = p.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
        e.jumpBoostAmp = jb == null ? -1 : jb.getAmplifier();
        var lev = p.getEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
        e.levitationAmp = lev == null ? -1 : lev.getAmplifier();
        e.slowFalling = p.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
        e.dolphinsGrace = p.hasEffect(net.minecraft.world.effect.MobEffects.DOLPHINS_GRACE);
        var in = p.getLastClientInput();
        e.hasInput = true;
        e.forward = in.forward(); e.backward = in.backward(); e.left = in.left(); e.right = in.right();
        e.jumpKey = in.jump(); e.shiftKey = in.shift(); e.sprintKey = in.sprint();
        e.ticksExisted = p.tickCount;
        e.hurtTime = p.hurtTime;
        e.foodLevel = p.getFoodData().getFoodLevel();
        net.minecraft.world.phys.AABB cur = p.getBoundingBox();
        net.minecraft.world.phys.AABB nb = cur.move(dx, dy, dz);
        e.groundBelowNew = !level.noCollision(p, new net.minecraft.world.phys.AABB(nb.minX + 1e-3, nb.minY - 0.03, nb.minZ + 1e-3, nb.maxX - 1e-3, nb.minY, nb.maxZ - 1e-3));
        e.ceilingAboveNew = !level.noCollision(p, new net.minecraft.world.phys.AABB(nb.minX + 1e-3, nb.maxY, nb.minZ + 1e-3, nb.maxX - 1e-3, nb.maxY + 0.03, nb.maxZ - 1e-3));
        e.horizontalBlockedNew = !level.noCollision(p, new net.minecraft.world.phys.AABB(nb.minX - 0.03, nb.minY + 0.01, nb.minZ - 0.03, nb.maxX + 0.03, nb.maxY - 0.01, nb.maxZ + 0.03));
        e.groundBelowPrev = !level.noCollision(p, new net.minecraft.world.phys.AABB(cur.minX + 1e-3, cur.minY - 0.03, cur.minZ + 1e-3, cur.maxX - 1e-3, cur.minY, cur.maxZ - 1e-3));
        e.horizontalBlockedPrev = !level.noCollision(p, new net.minecraft.world.phys.AABB(cur.minX - 0.03, cur.minY + 0.01, cur.minZ - 0.03, cur.maxX + 0.03, cur.maxY - 0.01, cur.maxZ + 0.03));
        e.liquidAtNew = !level.getFluidState(net.minecraft.core.BlockPos.containing(p.getX() + dx, p.getY() + dy, p.getZ() + dz)).isEmpty();
        e.pushingEntities = level.getEntities(p, cur.inflate(0.1), ent -> ent.isPushable() && !ent.isSpectator()).size();
        e.fireworkBoosts = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.FireworkRocketEntity.class, cur.inflate(1.5)).size();
        scanNearby(e, level, cur);
        return e;
    }

    // 주변 블록 한 번 훑기 — 움직이는 피스톤(밀림), 꿀·슬라임(벽 미끄러짐·튕김). 
    private static void scanNearby(MoveEnv e, net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB b) {
        for (net.minecraft.core.BlockPos bp : net.minecraft.core.BlockPos.betweenClosed(
                net.minecraft.core.BlockPos.containing(b.minX - 1, b.minY - 1, b.minZ - 1),
                net.minecraft.core.BlockPos.containing(b.maxX + 1, b.maxY + 1, b.maxZ + 1))) {
            var st = level.getBlockState(bp);
            if (st.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) e.pushedByBlock = true;
            else if (st.is(net.minecraft.world.level.block.Blocks.HONEY_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
                if (b.inflate(0.05).intersects(new net.minecraft.world.phys.AABB(bp))) e.nearSticky = true;
            }
        }
    }

    public static void onClientTickEnd(ServerPlayer p) {
        if (!guest(p)) return;
        Engine.Out o = engine().onClientTickEnd(p.getUUID());
        if (o.setback != null) setback(p, o.setback);
        handleOut(p, o);
    }

    public static void onSwing(ServerPlayer p) {
        if (!guest(p)) return;
        handleOut(p, engine().onSwing(p.getUUID()));
    }

    public static void onSprint(ServerPlayer p, boolean start) {
        if (!guest(p)) return;
        handleOut(p, engine().onSprint(p.getUUID(), start, p.getFoodData().getFoodLevel(), p.isSwimming(),
                p.isCreative() || p.getAbilities().mayfly));
    }

    public static void onAbilities(ServerPlayer p, boolean flying) {
        if (!guest(p)) return;
        handleOut(p, engine().onAbilities(p.getUUID(), flying, p.getAbilities().mayfly));
    }

    public static void onContainerClick(ServerPlayer p) {
        if (!guest(p)) return;
        handleOut(p, engine().onContainerClick(p.getUUID()));
    }

    // ── 넉백·텔레포트·퐁 (AcServerCommonMixin) ──

    public static void onSend(ServerPlayer p, net.minecraft.network.protocol.Packet<?> packet) {
        if (!guest(p)) return;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket m) {
            if (m.id() == p.getId()) engine().onVelocity(p.getUUID(), m.movement().x, m.movement().y, m.movement().z, false);
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundExplodePacket x) {
            x.playerKnockback().ifPresent(v -> engine().onVelocity(p.getUUID(), v.x, v.y, v.z, true));
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            engine().onTeleport(p.getUUID(), p.getX(), p.getY(), p.getZ());
        }
    }

    public static void onPong(ServerPlayer p, int id) {
        if (!Engine.isOurPing(id)) return;
        MinecraftServer s = lastServer;
        UUID u = p.getUUID();
        if (s != null) s.execute(() -> engine().onPong(u, id)); // 이동 패킷과 같은 순서로(메인 스레드 큐)
    }

    // ── 공격·설치·채굴 ──

    private static net.minecraft.world.InteractionResult onAttack(
            net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level world,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.entity.Entity target,
            net.minecraft.world.phys.EntityHitResult hitResult) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !guest(sp)) return net.minecraft.world.InteractionResult.PASS;
        double range = Math.max(sp.entityInteractionRange(), weaponRange(sp));
        // 눈 높이 후보: 지금 실제 자세의 눈(포복·수영 0.4 포함) + 서기/웅크림 중 다른 쪽(자세가 막 바뀐 틱)
        double eyeStand = sp.getEyeY();
        double eyeCrouch = sp.getY() + sp.getEyeHeight(sp.isCrouching() ? net.minecraft.world.entity.Pose.STANDING : net.minecraft.world.entity.Pose.CROUCHING);
        Combat.Attack a = new Combat.Attack(target.getId(), box(target.getBoundingBox()), sp.getX(), eyeStand, eyeCrouch, sp.getZ(),
                range, sp.isUsingItem(), sp.containerMenu != sp.inventoryMenu, sp.isPassenger(),
                target instanceof net.minecraft.world.entity.player.Player);
        Engine.Out o = engine().onAttack(sp.getUUID(), a);
        handleOut(sp, o);
        return o.cancel ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS;
    }

    private static net.minecraft.world.InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player,
                                                                     net.minecraft.world.level.Level world,
                                                                     net.minecraft.world.InteractionHand hand,
                                                                     net.minecraft.world.phys.BlockHitResult hit) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !guest(sp)) return net.minecraft.world.InteractionResult.PASS;
        var pos = hit.getBlockPos();
        var loc = hit.getLocation();
        // 눈 높이 후보: 지금 실제 자세의 눈(포복·수영 0.4 포함) + 서기/웅크림 중 다른 쪽(자세가 막 바뀐 틱)
        double eyeStand = sp.getEyeY();
        double eyeCrouch = sp.getY() + sp.getEyeHeight(sp.isCrouching() ? net.minecraft.world.entity.Pose.STANDING : net.minecraft.world.entity.Pose.CROUCHING);
        boolean air = world.getBlockState(pos).isAir();
        boolean blocked = obstructed(sp, world, pos, loc, eyeStand) && obstructed(sp, world, pos, loc, eyeCrouch);
        World.Place pl = new World.Place(pos.getX(), pos.getY(), pos.getZ(), hit.getDirection().ordinal(), loc.x, loc.y, loc.z,
                sp.getX(), eyeStand, eyeCrouch, sp.getZ(), sp.blockInteractionRange(), air, blocked, sp.isCreative());
        Engine.Out o = engine().onPlace(sp.getUUID(), pl);
        handleOut(sp, o);
        return o.cancel ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS;
    }

    // 눈 → 목표 사이 다른 블록이 가로막는지 — 클라는 처음 닿는 외곽선을 집으므로 다른 블록에 먼저 닿으면 벽 너머 상호작용. 
    private static boolean obstructed(ServerPlayer sp, net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos,
                                      net.minecraft.world.phys.Vec3 target, double eyeY) {
        var eye = new net.minecraft.world.phys.Vec3(sp.getX(), eyeY, sp.getZ());
        var r = world.clip(new net.minecraft.world.level.ClipContext(eye, target,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, sp));
        if (r.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
        if (r.getBlockPos().equals(pos)) return false;
        return r.getLocation().distanceTo(eye) < target.distanceTo(eye) - 0.2;
    }

    // @return true면 이 채굴 동작을 취소(블록은 클라에 되돌려 보낸다). 
    public static boolean onDig(ServerPlayer sp, net.minecraft.core.BlockPos pos, int action, net.minecraft.core.Direction face) {
        if (!guest(sp)) return false;
        var level = sp.level();
        var state = level.getBlockState(pos);
        var shape = state.getShape(level, pos);
        net.minecraft.world.phys.AABB bb = shape.isEmpty() ? new net.minecraft.world.phys.AABB(pos) : shape.bounds().move(pos);
        // 눈 높이 후보: 지금 실제 자세의 눈(포복·수영 0.4 포함) + 서기/웅크림 중 다른 쪽(자세가 막 바뀐 틱)
        double eyeStand = sp.getEyeY();
        double eyeCrouch = sp.getY() + sp.getEyeHeight(sp.isCrouching() ? net.minecraft.world.entity.Pose.STANDING : net.minecraft.world.entity.Pose.CROUCHING);
        var center = bb.getCenter();
        boolean blocked = action == World.Dig.START
                && obstructed(sp, level, pos, center, eyeStand) && obstructed(sp, level, pos, center, eyeCrouch);
        boolean valuable = state.is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE)
                || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)
                || state.is(net.minecraft.world.level.block.Blocks.EMERALD_ORE)
                || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_EMERALD_ORE);
        boolean stone = state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER);
        World.Dig d = new World.Dig(action, pos.getX(), pos.getY(), pos.getZ(), face.ordinal(), box(bb),
                state.getDestroyProgress(sp, level, pos), sp.getX(), eyeStand, eyeCrouch, sp.getZ(), sp.blockInteractionRange(),
                blocked, sp.isCreative(), valuable, stone);
        Engine.Out o = engine().onDig(sp.getUUID(), d);
        handleOut(sp, o);
        if (o.cancel) sp.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, pos));
        return o.cancel;
    }

    private static boolean onChat(net.minecraft.network.chat.PlayerChatMessage message, ServerPlayer player,
                                  net.minecraft.network.chat.ChatType.Bound params) {
        if (player == null || lastServer == null || P2PBanManager.isHost(lastServer, player)) return true;
        return !chatSpam(player.getUUID());
    }

    // ── 조치 ──

    private static void handleOut(ServerPlayer p, Engine.Out o) {
        if (o == null) return;
        if (o.kick != null) engine().requestKick(uuidOf(p), o.kick); // 패킷 처리 도중 끊지 않고 다음 틱에
    }

    // 이동 셋백 — 믹스인이 패킷을 버린 뒤 부른다. 
    public static void setback(ServerPlayer p, double[] pos) {
        p.connection.teleport(pos[0], pos[1], pos[2], p.getYRot(), p.getXRot());
    }

    private static Component checkName(String id) {
        return Component.translatable(reasonKey(id));
    }

    private static void kick(ServerPlayer target, String check) {
        if (target.hasDisconnected()) return;
        String name = P2PBanManager.profileName(target.getGameProfile());
        LOG.warn("[anticheat] {} 강퇴 — 검사={}", name, check);
        notifyHost(Component.translatable("instant-p2p.anticheat.notify.kick", name, checkName(check)));
        HostEventLogBridge.anticheat(name, target.getUUID().toString(), check, "kick", -1, null);
        target.connection.disconnect(Component.translatable("instant-p2p.anticheat.kicked", checkName(check)));
        engine().quit(target.getUUID());
    }

    private static void drainAlerts(MinecraftServer server) {
        for (Engine.Alert a : engine().drainAlerts()) {
            if (a.kick()) continue; // 강퇴 알림은 kick()에서
            LOG.warn("[anticheat] {} {} VL={} {}", a.name(), a.check(), String.format("%.1f", a.vl()), a.detail());
            notifyHost(Component.translatable("instant-p2p.anticheat.notify.flag", a.name(), checkName(a.check()),
                    String.format("%.1f", a.vl()), a.detail() == null ? "" : a.detail()));
            HostEventLogBridge.anticheat(a.name(), a.uuid().toString(), a.check(), "flag", a.vl(), a.detail());
        }
    }

    private static void notifyHost(Component msg) {
        MinecraftServer server = lastServer;
        if (server == null || !P2PConfig.getAntiCheat().notifyHost) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (P2PBanManager.isHost(server, p)) p.sendSystemMessage(msg);
        }
    }
    *///?} else {
    private static UUID uuidOf(ServerPlayerEntity p) { return p.getUuid(); }

    private static boolean guest(ServerPlayerEntity p) {
        return on() && lastServer != null && !P2PBanManager.isHost(lastServer, p);
    }

    private static void onJoin(ServerPlayerEntity p) {
        if (lastServer == null || P2PBanManager.isHost(lastServer, p)) return;
        PlayerData d = engine().join(p.getUuid(), P2PBanManager.profileName(p.getGameProfile()), p.getId());
        if (d != null) AcIntegrity.ask(p);
    }

    private static void onEndTick(MinecraftServer server) {
        lastServer = server;
        if (!on()) { drainAlerts(server); return; }
        Set<Integer> seen = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (seen.add(p.getId())) engine().recordEntity(p.getId(), box(p.getBoundingBox()));
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (P2PBanManager.isHost(server, p)) continue;
            for (net.minecraft.entity.Entity e : world(p).getOtherEntities(p, p.getBoundingBox().expand(8.0, 6.0, 8.0),
                    ent -> ent instanceof net.minecraft.entity.LivingEntity)) {
                if (seen.add(e.getId())) engine().recordEntity(e.getId(), box(e.getBoundingBox()));
            }
            int id = engine().pingFor(p.getUuid());
            if (id != 0) p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.common.CommonPingS2CPacket(id));
            String k = engine().takeKick(p.getUuid());
            if (k != null) kick(p, k);
        }
        AcIntegrity.tick(server, tick);
        if (tick % 20 == 0) engine().checkIntegrityTimeouts();
        drainAlerts(server);
    }

    static Box box(net.minecraft.util.math.Box b) {
        return new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    public static Engine.Out onMove(ServerPlayerEntity p, net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket pk,
                                    boolean awaitingTeleport) {
        if (!guest(p)) return null;
        double nx = pk.getX(p.getX()), ny = pk.getY(p.getY()), nz = pk.getZ(p.getZ());
        boolean hcol = horizontalCollision(pk);
        MoveInput m = new MoveInput(pk.changesPosition(), pk.changesLook(), nx, ny, nz, pk.getYaw(p.getYaw()), pk.getPitch(p.getPitch()),
                pk.isOnGround(), hcol);
        MoveEnv env = env(p, nx - p.getX(), ny - p.getY(), nz - p.getZ(), awaitingTeleport);
        Engine.Out o = engine().onMove(p.getUuid(), m, env);
        handleOut(p, o);
        return o;
    }

    private static MoveEnv env(ServerPlayerEntity p, double dx, double dy, double dz, boolean awaitingTeleport) {
        MoveEnv e = new MoveEnv();
        var ab = p.getAbilities();
        e.creative = p.isCreative(); e.spectator = p.isSpectator(); e.mayFly = ab.allowFlying; e.flying = ab.flying;
        e.flySpeed = ab.getFlySpeed();
        e.passenger = p.hasVehicle(); e.sleeping = p.isSleeping(); e.gliding = isGliding(p); e.riptide = p.isUsingRiptide();
        e.dead = p.isDead();
        e.awaitingTeleport = awaitingTeleport;
        net.minecraft.world.World level = world(p);
        var inState = p.getBlockStateAtPos();
        e.inWater = p.isTouchingWater(); e.inLava = p.isInLava(); e.underWater = p.isSubmergedInWater();
        e.inPowderSnow = p.inPowderSnow || p.wasInPowderSnow;
        e.onClimbable = p.isClimbing();
        e.inBubbleColumn = inState.isOf(net.minecraft.block.Blocks.BUBBLE_COLUMN);
        e.inScaffolding = inState.isOf(net.minecraft.block.Blocks.SCAFFOLDING);
        Object so = ((kfc.udp.client.anticheat.AcEntityAccess) p).kfcudp$stuckMultiplier();
        net.minecraft.util.math.Vec3d stuck = so instanceof net.minecraft.util.math.Vec3d v ? v : null;
        e.stuck = stuck != null && stuck.lengthSquared() > 1.0E-7
                || inState.isOf(net.minecraft.block.Blocks.COBWEB)
                || inState.isOf(net.minecraft.block.Blocks.SWEET_BERRY_BUSH);
        e.blockFriction = level.getBlockState(p.getVelocityAffectingPos()).getBlock().getSlipperiness();
        e.blockSpeedFactor = ((kfc.udp.client.anticheat.AcEntityAccess) p).kfcudp$blockSpeedFactor();
        e.blockJumpFactor = ((kfc.udp.client.anticheat.AcEntityAccess) p).kfcudp$blockJumpFactor();
        var on = level.getBlockState(p.getLandingPos()).getBlock();
        e.bounceFactor = on instanceof net.minecraft.block.SlimeBlock ? 1.0f
                : on instanceof net.minecraft.block.BedBlock ? 0.66f : 0f;
        fillAttributesAndInput(e, p);
        e.sprinting = p.isSprinting();
        e.sneakingPose = p.isInSneakingPose() || p.isCrawling();
        e.usingItem = p.isUsingItem();
        var jb = p.getStatusEffect(net.minecraft.entity.effect.StatusEffects.JUMP_BOOST);
        e.jumpBoostAmp = jb == null ? -1 : jb.getAmplifier();
        var lev = p.getStatusEffect(net.minecraft.entity.effect.StatusEffects.LEVITATION);
        e.levitationAmp = lev == null ? -1 : lev.getAmplifier();
        e.slowFalling = p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING);
        e.dolphinsGrace = p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.DOLPHINS_GRACE);
        e.ticksExisted = p.age;
        e.hurtTime = p.hurtTime;
        e.foodLevel = p.getHungerManager().getFoodLevel();
        net.minecraft.util.math.Box cur = p.getBoundingBox();
        net.minecraft.util.math.Box nb = cur.offset(dx, dy, dz);
        e.groundBelowNew = !level.isSpaceEmpty(p, new net.minecraft.util.math.Box(nb.minX + 1e-3, nb.minY - 0.03, nb.minZ + 1e-3, nb.maxX - 1e-3, nb.minY, nb.maxZ - 1e-3));
        e.ceilingAboveNew = !level.isSpaceEmpty(p, new net.minecraft.util.math.Box(nb.minX + 1e-3, nb.maxY, nb.minZ + 1e-3, nb.maxX - 1e-3, nb.maxY + 0.03, nb.maxZ - 1e-3));
        e.horizontalBlockedNew = !level.isSpaceEmpty(p, new net.minecraft.util.math.Box(nb.minX - 0.03, nb.minY + 0.01, nb.minZ - 0.03, nb.maxX + 0.03, nb.maxY - 0.01, nb.maxZ + 0.03));
        e.groundBelowPrev = !level.isSpaceEmpty(p, new net.minecraft.util.math.Box(cur.minX + 1e-3, cur.minY - 0.03, cur.minZ + 1e-3, cur.maxX - 1e-3, cur.minY, cur.maxZ - 1e-3));
        e.horizontalBlockedPrev = !level.isSpaceEmpty(p, new net.minecraft.util.math.Box(cur.minX - 0.03, cur.minY + 0.01, cur.minZ - 0.03, cur.maxX + 0.03, cur.maxY - 0.01, cur.maxZ + 0.03));
        e.liquidAtNew = !level.getFluidState(net.minecraft.util.math.BlockPos.ofFloored(p.getX() + dx, p.getY() + dy, p.getZ() + dz)).isEmpty();
        e.pushingEntities = level.getOtherEntities(p, cur.expand(0.1), ent -> ent.isPushable() && !ent.isSpectator()).size();
        e.fireworkBoosts = level.getEntitiesByClass(net.minecraft.entity.projectile.FireworkRocketEntity.class, cur.expand(1.5), r -> true).size();
        scanNearby(e, level, cur);
        return e;
    }

    private static void scanNearby(MoveEnv e, net.minecraft.world.World level, net.minecraft.util.math.Box b) {
        for (net.minecraft.util.math.BlockPos bp : net.minecraft.util.math.BlockPos.iterate(
                net.minecraft.util.math.BlockPos.ofFloored(b.minX - 1, b.minY - 1, b.minZ - 1),
                net.minecraft.util.math.BlockPos.ofFloored(b.maxX + 1, b.maxY + 1, b.maxZ + 1))) {
            var st = level.getBlockState(bp);
            if (st.isOf(net.minecraft.block.Blocks.MOVING_PISTON)) e.pushedByBlock = true;
            else if (st.isOf(net.minecraft.block.Blocks.HONEY_BLOCK) || st.isOf(net.minecraft.block.Blocks.SLIME_BLOCK)) {
                if (b.expand(0.05).intersects(new net.minecraft.util.math.Box(bp))) e.nearSticky = true;
            }
        }
    }

    public static void onClientTickEnd(ServerPlayerEntity p) {
        if (!guest(p)) return;
        Engine.Out o = engine().onClientTickEnd(p.getUuid());
        if (o.setback != null) setback(p, o.setback);
        handleOut(p, o);
    }

    public static void onSwing(ServerPlayerEntity p) {
        if (!guest(p)) return;
        handleOut(p, engine().onSwing(p.getUuid()));
    }

    public static void onSprint(ServerPlayerEntity p, boolean start) {
        if (!guest(p)) return;
        handleOut(p, engine().onSprint(p.getUuid(), start, p.getHungerManager().getFoodLevel(), p.isSwimming(),
                p.isCreative() || p.getAbilities().allowFlying));
    }

    public static void onAbilities(ServerPlayerEntity p, boolean flying) {
        if (!guest(p)) return;
        handleOut(p, engine().onAbilities(p.getUuid(), flying, p.getAbilities().allowFlying));
    }

    public static void onContainerClick(ServerPlayerEntity p) {
        if (!guest(p)) return;
        handleOut(p, engine().onContainerClick(p.getUuid()));
    }

    public static void onSend(ServerPlayerEntity p, net.minecraft.network.packet.Packet<?> packet) {
        if (!guest(p)) return;
        if (packet instanceof net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket m) {
            if (m.getEntityId() == p.getId()) {
                double[] v = velocityOf(m);
                engine().onVelocity(p.getUuid(), v[0], v[1], v[2], false);
            }
        } else if (packet instanceof net.minecraft.network.packet.s2c.play.ExplosionS2CPacket x) {
            double[] v = knockbackOf(x);
            if (v != null) engine().onVelocity(p.getUuid(), v[0], v[1], v[2], true);
        } else if (packet instanceof net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket) {
            engine().onTeleport(p.getUuid(), p.getX(), p.getY(), p.getZ());
        }
    }

    public static void onPong(ServerPlayerEntity p, int id) {
        if (!Engine.isOurPing(id)) return;
        MinecraftServer s = lastServer;
        UUID u = p.getUuid();
        if (s != null) s.execute(() -> engine().onPong(u, id));
    }

    private static net.minecraft.util.ActionResult onAttack(
            net.minecraft.entity.player.PlayerEntity player, net.minecraft.world.World world, net.minecraft.util.Hand hand,
            net.minecraft.entity.Entity target, net.minecraft.util.hit.EntityHitResult hitResult) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp) || !guest(sp)) return net.minecraft.util.ActionResult.PASS;
        double range = Math.max(sp.getEntityInteractionRange(), weaponRange(sp));
        double eyeStand = sp.getEyeY();
        double eyeCrouch = sp.getY() + sp.getEyeHeight(sp.isInSneakingPose() ? net.minecraft.entity.EntityPose.STANDING : net.minecraft.entity.EntityPose.CROUCHING);
        Combat.Attack a = new Combat.Attack(target.getId(), box(target.getBoundingBox()), sp.getX(), eyeStand, eyeCrouch, sp.getZ(),
                range, sp.isUsingItem(), sp.currentScreenHandler != sp.playerScreenHandler, sp.hasVehicle(),
                target instanceof net.minecraft.entity.player.PlayerEntity);
        Engine.Out o = engine().onAttack(sp.getUuid(), a);
        handleOut(sp, o);
        return o.cancel ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
    }

    private static net.minecraft.util.ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player,
                                                              net.minecraft.world.World world, net.minecraft.util.Hand hand,
                                                              net.minecraft.util.hit.BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp) || !guest(sp)) return net.minecraft.util.ActionResult.PASS;
        var pos = hit.getBlockPos();
        var loc = hit.getPos();
        double eyeStand = sp.getEyeY();
        double eyeCrouch = sp.getY() + sp.getEyeHeight(sp.isInSneakingPose() ? net.minecraft.entity.EntityPose.STANDING : net.minecraft.entity.EntityPose.CROUCHING);
        boolean air = world.getBlockState(pos).isAir();
        boolean blocked = obstructed(sp, world, pos, loc, eyeStand) && obstructed(sp, world, pos, loc, eyeCrouch);
        World.Place pl = new World.Place(pos.getX(), pos.getY(), pos.getZ(), hit.getSide().ordinal(), loc.x, loc.y, loc.z,
                sp.getX(), eyeStand, eyeCrouch, sp.getZ(), sp.getBlockInteractionRange(), air, blocked, sp.isCreative());
        Engine.Out o = engine().onPlace(sp.getUuid(), pl);
        handleOut(sp, o);
        return o.cancel ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
    }

    private static boolean obstructed(ServerPlayerEntity sp, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
                                      net.minecraft.util.math.Vec3d target, double eyeY) {
        var eye = new net.minecraft.util.math.Vec3d(sp.getX(), eyeY, sp.getZ());
        var r = world.raycast(new net.minecraft.world.RaycastContext(eye, target,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, sp));
        if (r.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) return false;
        if (r.getBlockPos().equals(pos)) return false;
        return r.getPos().distanceTo(eye) < target.distanceTo(eye) - 0.2;
    }

    public static boolean onDig(ServerPlayerEntity sp, net.minecraft.util.math.BlockPos pos, int action, net.minecraft.util.math.Direction face) {
        if (!guest(sp)) return false;
        net.minecraft.world.World level = world(sp);
        var state = level.getBlockState(pos);
        var shape = state.getOutlineShape(level, pos);
        net.minecraft.util.math.Box bb = shape.isEmpty() ? new net.minecraft.util.math.Box(pos) : shape.getBoundingBox().offset(pos);
        double eyeStand = sp.getEyeY();
        double eyeCrouch = sp.getY() + sp.getEyeHeight(sp.isInSneakingPose() ? net.minecraft.entity.EntityPose.STANDING : net.minecraft.entity.EntityPose.CROUCHING);
        var center = bb.getCenter();
        boolean blocked = action == World.Dig.START
                && obstructed(sp, level, pos, center, eyeStand) && obstructed(sp, level, pos, center, eyeCrouch);
        boolean valuable = state.isOf(net.minecraft.block.Blocks.DIAMOND_ORE)
                || state.isOf(net.minecraft.block.Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.isOf(net.minecraft.block.Blocks.ANCIENT_DEBRIS)
                || state.isOf(net.minecraft.block.Blocks.EMERALD_ORE)
                || state.isOf(net.minecraft.block.Blocks.DEEPSLATE_EMERALD_ORE);
        boolean stone = state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_NETHER);
        World.Dig d = new World.Dig(action, pos.getX(), pos.getY(), pos.getZ(), face.ordinal(), box(bb),
                state.calcBlockBreakingDelta(sp, level, pos), sp.getX(), eyeStand, eyeCrouch, sp.getZ(), sp.getBlockInteractionRange(),
                blocked, sp.isCreative(), valuable, stone);
        Engine.Out o = engine().onDig(sp.getUuid(), d);
        handleOut(sp, o);
        if (o.cancel) sp.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket(level, pos));
        return o.cancel;
    }

    private static boolean onChat(net.minecraft.network.message.SignedMessage message, ServerPlayerEntity player,
                                  net.minecraft.network.message.MessageType.Parameters params) {
        if (player == null || lastServer == null || P2PBanManager.isHost(lastServer, player)) return true;
        return !chatSpam(player.getUuid());
    }

    private static void handleOut(ServerPlayerEntity p, Engine.Out o) {
        if (o == null) return;
        if (o.kick != null) engine().requestKick(uuidOf(p), o.kick); // 패킷 처리 도중 끊지 않고 다음 틱에
    }

    public static void setback(ServerPlayerEntity p, double[] pos) {
        p.networkHandler.requestTeleport(pos[0], pos[1], pos[2], p.getYaw(), p.getPitch());
    }

    private static Text checkName(String id) {
        return Text.translatable(reasonKey(id));
    }

    private static void kick(ServerPlayerEntity target, String check) {
        if (target.isDisconnected()) return;
        String name = P2PBanManager.profileName(target.getGameProfile());
        LOG.warn("[anticheat] {} 강퇴 — 검사={}", name, check);
        notifyHost(Text.translatable("instant-p2p.anticheat.notify.kick", name, checkName(check)));
        HostEventLogBridge.anticheat(name, target.getUuid().toString(), check, "kick", -1, null);
        target.networkHandler.disconnect(Text.translatable("instant-p2p.anticheat.kicked", checkName(check)));
        engine().quit(target.getUuid());
    }

    private static void drainAlerts(MinecraftServer server) {
        for (Engine.Alert a : engine().drainAlerts()) {
            if (a.kick()) continue;
            LOG.warn("[anticheat] {} {} VL={} {}", a.name(), a.check(), String.format("%.1f", a.vl()), a.detail());
            notifyHost(Text.translatable("instant-p2p.anticheat.notify.flag", a.name(), checkName(a.check()),
                    String.format("%.1f", a.vl()), a.detail() == null ? "" : a.detail()));
            HostEventLogBridge.anticheat(a.name(), a.uuid().toString(), a.check(), "flag", a.vl(), a.detail());
        }
    }

    private static void notifyHost(Text msg) {
        MinecraftServer server = lastServer;
        if (server == null || !P2PConfig.getAntiCheat().notifyHost) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (P2PBanManager.isHost(server, p)) p.sendMessage(msg, false);
        }
    }

    //?}

    // ── 버전별로 이름이 갈리는 작은 조각 — 중첩 조건 대신 범위를 나눈 최상위 블록(이 저장소의 다른 파일과 같은 방식) ──

    //? if >=26.2 {
    /*private static float frictionModifier(ServerPlayer p) {
        return (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FRICTION_MODIFIER);
    }
    private static float airDragModifier(ServerPlayer p) {
        return (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER);
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static float frictionModifier(ServerPlayer p) { return 1f; }
    private static float airDragModifier(ServerPlayer p) { return 1f; }
    *///?}
    //? if >=26.3 {
    /*// 창(스피어) 같은 무기는 공격 거리가 더 길다.
    private static double weaponRange(ServerPlayer sp) {
        var ar = sp.getAttackRangeWith(sp.getMainHandItem());
        return ar == null ? 0 : ar.effectiveMaxRange(sp) + ar.hitboxMargin();
    }
    *///?}
    //? if >=26.1 <26.3 {
    /*private static double weaponRange(ServerPlayer sp) { return 0; }
    *///?}
    //? if >=1.21.11 <26.1 {
    /*private static double weaponRange(ServerPlayerEntity sp) {
        var ar = sp.getAttackRange();
        return ar == null ? 0 : ar.getEffectiveMaxRange(sp) + ar.hitboxMargin();
    }
    *///?}
    //? if <1.21.11 {
    private static double weaponRange(ServerPlayerEntity sp) { return 0; }
    //?}
    //? if >=1.21.9 <26.1 {
    /*private static net.minecraft.world.World world(ServerPlayerEntity p) { return p.getEntityWorld(); }
    private static double[] velocityOf(net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket m) {
        var v = m.getVelocity();
        return new double[]{v.x, v.y, v.z};
    }
    *///?}
    //? if <1.21.9 {
    private static net.minecraft.world.World world(ServerPlayerEntity p) { return p.getWorld(); }
    private static double[] velocityOf(net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket m) {
        return new double[]{m.getVelocityX(), m.getVelocityY(), m.getVelocityZ()};
    }
    //?}
    //? if >=1.21.2 <26.1 {
    private static boolean isGliding(ServerPlayerEntity p) { return p.isGliding(); }
    private static boolean horizontalCollision(net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket pk) { return pk.horizontalCollision(); }
    private static double[] knockbackOf(net.minecraft.network.packet.s2c.play.ExplosionS2CPacket x) {
        return x.playerKnockback().map(v -> new double[]{v.x, v.y, v.z}).orElse(null);
    }
    private static void fillAttributesAndInput(MoveEnv e, ServerPlayerEntity p) {
        e.movementSpeed = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.MOVEMENT_SPEED);
        e.jumpStrength = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.JUMP_STRENGTH);
        e.gravity = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GRAVITY);
        e.stepHeight = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.STEP_HEIGHT);
        e.sneakingSpeed = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.SNEAKING_SPEED);
        e.waterMovementEfficiency = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.WATER_MOVEMENT_EFFICIENCY);
        var in = p.getPlayerInput();
        e.hasInput = true;
        e.forward = in.forward(); e.backward = in.backward(); e.left = in.left(); e.right = in.right();
        e.jumpKey = in.jump(); e.shiftKey = in.sneak(); e.sprintKey = in.sprint();
    }
    //?}
    //? if <1.21.2 {
    /*private static boolean isGliding(ServerPlayerEntity p) { return p.isFallFlying(); }
    private static boolean horizontalCollision(net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket pk) { return false; }
    private static double[] knockbackOf(net.minecraft.network.packet.s2c.play.ExplosionS2CPacket x) {
        return new double[]{x.getPlayerVelocityX(), x.getPlayerVelocityY(), x.getPlayerVelocityZ()};
    }
    private static void fillAttributesAndInput(MoveEnv e, ServerPlayerEntity p) {
        e.movementSpeed = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);
        e.jumpStrength = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_JUMP_STRENGTH);
        e.gravity = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_GRAVITY);
        e.stepHeight = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_STEP_HEIGHT);
        e.sneakingSpeed = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.PLAYER_SNEAKING_SPEED);
        e.waterMovementEfficiency = p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_WATER_MOVEMENT_EFFICIENCY);
        e.hasInput = false;
    }
    *///?}

    // 26.x FRICTION_MODIFIER 적용 — LivingEntity.computeModifiedFriction과 같은 식. 
    static float modFriction(float friction, float modifier) {
        return Math.max(0f, Math.min(1f, 1f - (1f - friction) * modifier));
    }
}
