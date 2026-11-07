package kfc.udp.client.mixin;

import kfc.udp.client.signaling.ChatGuard;
//? if >=26.1 {
/*import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
*///?} else {
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 방장 서버의 채팅 받기(1.5~) — 패킷이 오는 순간 도배를 세고(ChatGuard.onPacket — 넘으면 내보냄), 서명 확인을 마치고
 * 모두에게 뿌리기 직전에 안전 필터·공개 방 1초 1메시지·승인 대기를 건다(ChatGuard.allowChat). 막힌 메시지는 아무에게도
 * 전달되지 않는다.
 */
//? if >=26.1 {
/*@Mixin(ServerGamePacketListenerImpl.class)
*///?} else {
@Mixin(ServerPlayNetworkHandler.class)
//?}
public abstract class ChatGuardMixin {

    //? if >=26.1 {
    /*@Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void kfcudp$chatPacket(ServerboundChatPacket packet, CallbackInfo ci) {
        if (ChatGuard.onPacket(this.player)) ci.cancel();
    }

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void kfcudp$chatDeliver(PlayerChatMessage message, CallbackInfo ci) {
        if (!ChatGuard.allowChat(this.player, message.signedContent(), message)) ci.cancel();
    }
    *///?} else {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void kfcudp$chatPacket(ChatMessageC2SPacket packet, CallbackInfo ci) {
        if (ChatGuard.onPacket(this.player)) ci.cancel();
    }

    @Inject(method = "handleDecoratedMessage", at = @At("HEAD"), cancellable = true)
    private void kfcudp$chatDeliver(SignedMessage message, CallbackInfo ci) {
        if (!ChatGuard.allowChat(this.player, message.getSignedContent(), message)) ci.cancel();
    }
    //?}
}
