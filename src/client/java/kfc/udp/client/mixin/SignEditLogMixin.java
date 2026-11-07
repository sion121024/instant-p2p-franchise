package kfc.udp.client.mixin;

import kfc.udp.client.signaling.HostEventLog;
import kfc.udp.client.signaling.P2PBanManager;
import kfc.udp.client.signaling.SignSnapshot;
//? if >=26.1 {
/*import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
*///?} else {
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 방장 서버에서 표지판 글을 바꾸면 전후를 방장 이벤트 기록에 남긴다(1.5~, HostEventLog.sign).
 * 표지판 앞/뒤 구분 인자가 26.3 에서 boolean → SignTextSlot 으로 바뀌었다(서로 안 겹치는 블록 3개).
 */
@Mixin(SignBlockEntity.class)
public abstract class SignEditLogMixin {

    @Unique
    private String kfcudp$before;

    //? if >=26.3 {
    /*@Inject(method = "updateSignText", at = @At("HEAD"))
    private void kfcudp$pre(Player player, net.minecraft.world.level.block.entity.SignTextSlot slot, List<?> messages, CallbackInfo ci) {
        this.kfcudp$before = SignSnapshot.signText(this);
    }

    @Inject(method = "updateSignText", at = @At("RETURN"))
    private void kfcudp$post(Player player, net.minecraft.world.level.block.entity.SignTextSlot slot, List<?> messages, CallbackInfo ci) {
        this.kfcudp$log(player);
    }
    *///?}
    //? if >=26.1 <26.3 {
    /*@Inject(method = "updateSignText", at = @At("HEAD"))
    private void kfcudp$pre(Player player, boolean front, List<?> messages, CallbackInfo ci) {
        this.kfcudp$before = SignSnapshot.signText(this);
    }

    @Inject(method = "updateSignText", at = @At("RETURN"))
    private void kfcudp$post(Player player, boolean front, List<?> messages, CallbackInfo ci) {
        this.kfcudp$log(player);
    }
    *///?}
    //? if <26.1 {
    @Inject(method = "tryChangeText", at = @At("HEAD"))
    private void kfcudp$pre(PlayerEntity player, boolean front, List<?> messages, CallbackInfo ci) {
        this.kfcudp$before = SignSnapshot.signText(this);
    }

    @Inject(method = "tryChangeText", at = @At("RETURN"))
    private void kfcudp$post(PlayerEntity player, boolean front, List<?> messages, CallbackInfo ci) {
        this.kfcudp$log(player);
    }
    //?}

    //? if >=26.1 {
    /*@Unique
    private void kfcudp$log(Player player) {
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return;
        BlockPos pos = be.getBlockPos();
        HostEventLog.sign(player.getUUID().toString(), P2PBanManager.profileName(player.getGameProfile()),
                String.valueOf(level.dimension()), pos.getX(), pos.getY(), pos.getZ(), this.kfcudp$before, SignSnapshot.signText(this));
    }
    *///?} else {
    @Unique
    private void kfcudp$log(PlayerEntity player) {
        BlockEntity be = (BlockEntity) (Object) this;
        World world = be.getWorld();
        if (world == null || world.isClient()) return;
        BlockPos pos = be.getPos();
        HostEventLog.sign(player.getUuid().toString(), P2PBanManager.profileName(player.getGameProfile()),
                String.valueOf(world.getRegistryKey().getValue()), pos.getX(), pos.getY(), pos.getZ(), this.kfcudp$before, SignSnapshot.signText(this));
    }
    //?}
}
