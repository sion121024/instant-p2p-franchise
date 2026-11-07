package kfc.udp.client.signaling;

import com.google.gson.JsonObject;

/**
 * 안티치트 적발을 방 기록(hostlog 해시 체인, 1.5~)에 남긴다 — 가맹점 서버가 30일 보관하고 관리자 페이지
 * "방 기록"에서 볼 수 있다. 방이 열려 있지 않으면(로비 연결 없음) QuicBridge가 조용히 버린다.
 */
final class HostEventLogBridge {

    private HostEventLogBridge() {}

    static void anticheat(String name, String uuid, String check, String kind, double vl, String detail) {
        try {
            JsonObject ev = new JsonObject();
            ev.addProperty("k", "ac");
            ev.addProperty("kind", kind);
            ev.addProperty("name", name == null ? "" : name);
            ev.addProperty("uuid", uuid == null ? "" : uuid);
            ev.addProperty("check", check);
            if (vl >= 0) ev.addProperty("vl", Math.round(vl * 10) / 10.0);
            if (detail != null) ev.addProperty("detail", detail.length() > 120 ? detail.substring(0, 120) : detail);
            kfc.udp.client.quic.QuicBridge.hostLog(ev.toString());
        } catch (RuntimeException ignored) {
        }
    }
}
