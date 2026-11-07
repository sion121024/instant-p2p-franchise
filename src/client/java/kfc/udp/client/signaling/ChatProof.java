package kfc.udp.client.signaling;

import com.mojang.authlib.GameProfile;
//? if >=26.1 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.world.entity.player.ProfilePublicKey;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.network.encryption.PublicPlayerSession;
import net.minecraft.network.message.SignedMessage;
//?}

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 서명된 채팅의 증거(1.5~) — 신고에 "이 사람이 정말 이 말을 했다"는 증거를 싣는다.
 * <p>
 * 마인크래프트 보안 채팅은 메시지마다 보낸 사람의 개인키로 서명하고, 그 공개키는 모장이 서명해 준다. 그래서 서명된
 * 본문·서명·공개키·모장 서명·키 만료 시각을 그대로 넘기면 서버가 모장 공개키만으로 진위를 확인할 수 있다 — 신고자가
 * 채팅을 지어내도, 방장이 기록을 고쳐도 소용없다. 서명이 없는 채팅은 {@link #UNSIGNED}(보안 채팅을 끈 방).
 */
public final class ChatProof {

    /** 서명이 없는 채팅 — 방이 보안 채팅을 강제하지 않는다는 뜻이라 따로 알린다(ReportClient.observeUnsigned). */
    public static final Proof UNSIGNED = new Proof(null, null, null, null, 0L);

    /** 서명된 바이트(signed)·서명(sig)·공개키(key)·모장 서명(ksig) 은 base64, kexp 는 키 만료(ms). */
    public record Proof(String signed, String sig, String key, String ksig, long kexp) {}

    private ChatProof() {}

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** @return 증거, 서명이 없으면 {@link #UNSIGNED}, 시스템 메시지거나 확인할 수 없으면 null */
    public static Proof capture(Object signedMessage, Object sender) {
        try {
            return capture0(signedMessage, sender);
        } catch (Throwable t) {
            return null;
        }
    }

    //? if >=26.1 {
    /*private static Proof capture0(Object signedMessage, Object senderObj) throws Exception {
        if (!(signedMessage instanceof PlayerChatMessage m) || !(senderObj instanceof GameProfile gp)) return null;
        if (m.isSystem()) return null;
        if (!m.hasSignature()) return UNSIGNED;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;
        PlayerInfo info = mc.getConnection().getPlayerInfo(P2PBanManager.profileId(gp));
        RemoteChatSession session = info == null ? null : info.getChatSession();
        // 서명한 세션이 지금 그 사람의 세션과 같아야 공개키가 맞다
        if (session == null || !session.sessionId().equals(m.link().sessionId())) return null;
        ProfilePublicKey.Data d = session.profilePublicKey().data();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlayerChatMessage.updateSignature(bs -> out.write(bs, 0, bs.length), m.link(), m.signedBody());
        return new Proof(b64(out.toByteArray()), b64(m.signature().bytes()), b64(d.key().getEncoded()),
                b64(d.keySignature()), d.expiresAt().toEpochMilli());
    }
    *///?} else {
    private static Proof capture0(Object signedMessage, Object senderObj) throws Exception {
        if (!(signedMessage instanceof SignedMessage m) || !(senderObj instanceof GameProfile gp)) return null;
        if (m.isSenderMissing()) return null;
        if (!m.hasSignature()) return UNSIGNED;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return null;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(P2PBanManager.profileId(gp));
        PublicPlayerSession session = entry == null ? null : entry.getSession();
        // 서명한 세션이 지금 그 사람의 세션과 같아야 공개키가 맞다
        if (session == null || !session.sessionId().equals(m.link().sessionId())) return null;
        PlayerPublicKey.PublicKeyData d = session.publicKeyData().data();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SignedMessage.update(bs -> out.write(bs, 0, bs.length), m.link(), m.signedBody());
        return new Proof(b64(out.toByteArray()), b64(m.signature().data()), b64(d.key().getEncoded()),
                b64(d.keySignature()), d.expiresAt().toEpochMilli());
    }
    //?}
}
