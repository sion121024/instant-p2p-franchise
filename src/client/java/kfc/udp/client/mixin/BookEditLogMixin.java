package kfc.udp.client.mixin;

import kfc.udp.client.signaling.BookSnapshot;
import kfc.udp.client.signaling.HostEventLog;
import kfc.udp.client.signaling.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
*///?} else {
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 방장 서버에서 책(책과 깃펜) 수정 전후를 방장 이벤트 기록에 남긴다(1.5~, HostEventLog.book) — 책으로 욕설·개인정보를
 * 퍼뜨린 뒤 지우는 걸 신고 처리 때 확인할 수 있게. 공개 방이 아니면 HostEventLog 가 버린다.
 */
//? if >=26.1 {
/*@Mixin(ServerGamePacketListenerImpl.class)
*///?} else {
@Mixin(ServerPlayNetworkHandler.class)
//?}
public abstract class BookEditLogMixin {

    @Unique
    private String kfcudp$before = "";

    //? if >=26.1 {
    /*@Shadow
    public ServerPlayer player;

    @Inject(method = "handleEditBook", at = @At("HEAD"))
    private void kfcudp$pre(ServerboundEditBookPacket packet, CallbackInfo ci) {
        this.kfcudp$before = this.kfcudp$describe(packet.slot());
    }

    @Inject(method = "handleEditBook", at = @At("RETURN"))
    private void kfcudp$post(ServerboundEditBookPacket packet, CallbackInfo ci) {
        HostEventLog.book(this.player.getUUID().toString(), P2PBanManager.profileName(this.player.getGameProfile()),
                packet.slot(), this.kfcudp$before, this.kfcudp$describe(packet.slot()));
    }

    @Unique
    private String kfcudp$describe(int slot) {
        try {
            return slot >= 0 && slot < this.player.getInventory().getContainerSize()
                    ? BookSnapshot.describeStack(this.player.getInventory().getItem(slot)) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
    *///?} else {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onBookUpdate", at = @At("HEAD"))
    private void kfcudp$pre(BookUpdateC2SPacket packet, CallbackInfo ci) {
        this.kfcudp$before = this.kfcudp$describe(packet.slot());
    }

    @Inject(method = "onBookUpdate", at = @At("RETURN"))
    private void kfcudp$post(BookUpdateC2SPacket packet, CallbackInfo ci) {
        HostEventLog.book(this.player.getUuid().toString(), P2PBanManager.profileName(this.player.getGameProfile()),
                packet.slot(), this.kfcudp$before, this.kfcudp$describe(packet.slot()));
    }

    @Unique
    private String kfcudp$describe(int slot) {
        try {
            return slot >= 0 && slot < this.player.getInventory().size()
                    ? BookSnapshot.describeStack(this.player.getInventory().getStack(slot)) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
    //?}
}
