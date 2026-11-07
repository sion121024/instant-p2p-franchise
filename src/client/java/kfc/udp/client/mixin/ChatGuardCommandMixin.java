package kfc.udp.client.mixin;

import com.mojang.brigadier.ParseResults;
import kfc.udp.client.signaling.ChatGuard;
import kfc.udp.client.signaling.CommandForge;
import kfc.udp.client.signaling.HostEventLog;
import kfc.udp.client.signaling.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 방장 서버의 명령 실행 직전(1.5~) — 세 가지를 한다.
 * <ol>
 *   <li>{@code execute as <다른 사람> run op/ban/admit…} 처럼 남의 이름으로 권한 명령을 치면 막는다(CommandForge).</li>
 *   <li>명령을 방장 이벤트 기록에 남긴다(HostEventLog.command — 채팅류는 내용 없이 이름만).</li>
 *   <li>채팅류 명령(/msg /say …)에 도배·안전 필터·승인 대기를 건다(ChatGuard.allowCommand) — execute 로 감싼 경우는
 *       안쪽 명령도 따로 본다.</li>
 * </ol>
 */
//? if >=26.1 {
/*@Mixin(Commands.class)
*///?} else {
@Mixin(CommandManager.class)
//?}
public abstract class ChatGuardCommandMixin {

    //? if >=26.1 {
    /*@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void kfcudp$chatCommand(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        CommandSourceStack root = parseResults.getContext().getSource();
        ServerPlayer p = root.getPlayer();
        String eff = CommandForge.effective(parseResults, command);
        net.minecraft.world.entity.Entity lastEntity = ((CommandSourceStack) CommandForge.lastSource(parseResults)).getEntity();
        if (CommandForge.isPrivileged(eff) && lastEntity != null && lastEntity != root.getEntity()) {
            ci.cancel();
            return;
        }
        if (p == null) return; // 서버 콘솔·명령 블록
        HostEventLog.command(P2PBanManager.profileId(p.getGameProfile()).toString(), P2PBanManager.profileName(p.getGameProfile()), command);
        if (!ChatGuard.allowCommand(p, command) || (!eff.equals(command) && !ChatGuard.allowCommand(p, eff))) ci.cancel();
    }
    *///?} else {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void kfcudp$chatCommand(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
        ServerCommandSource root = parseResults.getContext().getSource();
        ServerPlayerEntity p = root.getPlayer();
        String eff = CommandForge.effective(parseResults, command);
        Entity lastEntity = ((ServerCommandSource) CommandForge.lastSource(parseResults)).getEntity();
        if (CommandForge.isPrivileged(eff) && lastEntity != null && lastEntity != root.getEntity()) {
            ci.cancel();
            return;
        }
        if (p == null) return; // 서버 콘솔·명령 블록
        HostEventLog.command(P2PBanManager.profileId(p.getGameProfile()).toString(), P2PBanManager.profileName(p.getGameProfile()), command);
        if (!ChatGuard.allowCommand(p, command) || (!eff.equals(command) && !ChatGuard.allowCommand(p, eff))) ci.cancel();
    }
    //?}
}
