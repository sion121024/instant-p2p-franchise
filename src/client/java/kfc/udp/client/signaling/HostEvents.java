package kfc.udp.client.signaling;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
//? if >=26.1 {
/*import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
*///?} else {
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
//?}

/**
 * 방장 서버의 게임 이벤트 → {@link HostEventLog}(1.5~) — 블록 부수기(표지판·독서대는 따로), 상자류 열기, 플레이어 사망.
 * 블록 놓기는 Fabric 이벤트가 없어 원본은 BlockPlaceLogMixin 으로 잡으려 했지만 등록하지 않았다(PORT-NOTES).
 * 공개 방이 아니면 HostEventLog 가 버린다.
 */
public final class HostEvents {

    private HostEvents() {}

    // 플레이어의 차원 이름 — 1.21.9 에서 Yarn 이름이 getWorld → getEntityWorld 로 바뀌었다(서로 안 겹치는 블록 3개).
    //? if >=26.1 {
    /*private static String dimOf(ServerPlayer sp) {
        return String.valueOf(sp.level().dimension());
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*private static String dimOf(ServerPlayerEntity sp) {
        return String.valueOf(sp.getEntityWorld().getRegistryKey().getValue());
    }
    *///?}
    //? if <1.21.9 {
    private static String dimOf(ServerPlayerEntity sp) {
        return String.valueOf(sp.getWorld().getRegistryKey().getValue());
    }
    //?}

    //? if >=26.1 {
    /*private static String blockId(BlockState st) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(st.getBlock()));
    }

    public static String blockIdOf(BlockState st) {
        return blockId(st);
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return;
            String uuid = sp.getUUID().toString(), nick = P2PBanManager.profileName(sp.getGameProfile());
            String dim = String.valueOf(world.dimension());
            String id = blockId(state);
            HostEventLog.block(uuid, nick, dim, pos.getX(), pos.getY(), pos.getZ(), false, id);
            if (id.contains("sign") || id.endsWith(":lectern")) {
                HostEventLog.signBroken(uuid, nick, dim, pos.getX(), pos.getY(), pos.getZ());
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) {
                BlockPos pos = hit.getBlockPos();
                if (world.getBlockEntity(pos) instanceof Container) {
                    HostEventLog.open(sp.getUUID().toString(), P2PBanManager.profileName(sp.getGameProfile()),
                            String.valueOf(world.dimension()), pos.getX(), pos.getY(), pos.getZ(), blockId(world.getBlockState(pos)));
                }
            }
            return InteractionResult.PASS; // 기록만 하고 동작은 그대로
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer sp)) return;
            Entity att = source.getEntity();
            String killer = null, kname = "";
            if (att instanceof ServerPlayer ap) {
                killer = ap.getUUID().toString();
                kname = "player";
            } else if (att != null) {
                kname = String.valueOf(EntityType.getKey(att.getType()));
            }
            HostEventLog.death(sp.getUUID().toString(), P2PBanManager.profileName(sp.getGameProfile()), dimOf(sp),
                    (int) sp.getX(), (int) sp.getY(), (int) sp.getZ(), killer, kname);
        });
    }
    *///?} else {
    private static String blockId(BlockState st) {
        return String.valueOf(Registries.BLOCK.getId(st.getBlock()));
    }

    public static String blockIdOf(BlockState st) {
        return blockId(st);
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return;
            String uuid = sp.getUuid().toString(), nick = P2PBanManager.profileName(sp.getGameProfile());
            String dim = String.valueOf(world.getRegistryKey().getValue());
            String id = blockId(state);
            HostEventLog.block(uuid, nick, dim, pos.getX(), pos.getY(), pos.getZ(), false, id);
            if (id.contains("sign") || id.endsWith(":lectern")) {
                HostEventLog.signBroken(uuid, nick, dim, pos.getX(), pos.getY(), pos.getZ());
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity sp) {
                BlockPos pos = hit.getBlockPos();
                if (world.getBlockEntity(pos) instanceof Inventory) {
                    HostEventLog.open(sp.getUuid().toString(), P2PBanManager.profileName(sp.getGameProfile()),
                            String.valueOf(world.getRegistryKey().getValue()), pos.getX(), pos.getY(), pos.getZ(),
                            blockId(world.getBlockState(pos)));
                }
            }
            return ActionResult.PASS; // 기록만 하고 동작은 그대로
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity sp)) return;
            Entity att = source.getAttacker();
            String killer = null, kname = "";
            if (att instanceof ServerPlayerEntity ap) {
                killer = ap.getUuid().toString();
                kname = "player";
            } else if (att != null) {
                kname = String.valueOf(EntityType.getId(att.getType()));
            }
            HostEventLog.death(sp.getUuid().toString(), P2PBanManager.profileName(sp.getGameProfile()), dimOf(sp),
                    (int) sp.getX(), (int) sp.getY(), (int) sp.getZ(), killer, kname);
        });
    }
    //?}
}
