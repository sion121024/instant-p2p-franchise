package kfc.udp.client.mixin;

import kfc.udp.client.KfcudpClient;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.client.server.IntegratedServer;
*///?} else {
import net.minecraft.server.integrated.IntegratedServer;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P2P 방을 연 동안 통합 서버가 보안 채팅(서명)을 강제하게 한다(1.5~) — 서명 없는 채팅을 받지 않으므로 채팅 신고에
 * 서명 증거(ChatProof)를 실을 수 있고, 남의 이름으로 채팅을 지어낼 수 없다. 서명 강제를 안 하는 방은 공개 방
 * 목록에서 빠진다(서버 notice "unsigned").
 */
@Mixin(MinecraftServer.class)
public abstract class SecureChatMixin {

    //? if >=26.1 {
    /*@Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true)
    *///?} else {
    @Inject(method = "shouldEnforceSecureProfile", at = @At("HEAD"), cancellable = true)
    //?}
    private void kfcudp$enforceSigned(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof IntegratedServer && KfcudpClient.isRoomActive()) cir.setReturnValue(true);
    }
}
