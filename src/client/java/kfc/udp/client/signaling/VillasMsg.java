package kfc.udp.client.signaling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VILLASframework signaling 서버 메시지 빌드/파싱 유틸.
 * <p>
 * 서버(cmd/server, pkg/msg.go) 스키마:
 * <pre>
 *   {"control":{"peer_id":N,"peers":[{"name":..,"id":..,"remote":..,..}]}}
 *   {"delta":{"full":bool,"joined":[{"name":..,..}],"left":["name",..]}} ← mc-signaling 전용 확장,
 *       공개 방 목록 lobby에서만 옴(control 대신). full=true면 joined가 "지금 전원"이니 기존 걸
 *       버리고 통째로 갈아끼워야 한다 — PublicRoomBrowser 클래스 주석 참고.
 *   {"description":{"spd":"...","type":"offer|answer"}}   ← 필드명이 "spd" (서버 오타 그대로)
 *   {"candidate":{"spd":"candidate:...","mid":"0"}}
 *   {"servers":[{"url":..,"user":..,"pass":..,"realm":..,"expires":..}]}
 *   {"room_update":{"code":..,"title":..,..}}                            ← mc-signaling 전용 확장
 * </pre>
 * 주의: 서버 수신 한도 4096바이트(maxMessageSize), 접속 직후 클라이언트가
 * signals 메시지({} 가능)를 1회 먼저 보내야 하며, 릴레이 메시지에는 발신자 정보가 없다.
 */
public final class VillasMsg {  // QUIC 전송도 같은 시그널링을 쓴다 — WebRTC 전용이 아니다

    /** 접속 직후 보내는 signals 초기 메시지 */
    public static String hello() { return "{}"; }

    public static String description(String type, String sdp) {
        return "{\"description\":{\"spd\":\"" + escape(sdp) + "\",\"type\":\"" + type + "\"}}";
    }

    public static String candidate(String candidate, String mid) {
        return "{\"candidate\":{\"spd\":\"" + escape(candidate) + "\",\"mid\":\"" + escape(mid) + "\"}}";
    }

    /** mc-signaling 전용 확장 — 공개 방 정보 하나(PublicRoomAnnouncer 클래스 주석 참고). 서버는
     * 내용을 해석하지 않고 같은 로비의 다른 peer에게 그대로 중계한다. 보낸 peer 이름이 릴레이에
     * 안 실리므로 code를 본문에 직접 담는다. */
    static String roomUpdate(String code, String title, String nickname, String channel, boolean channelAnd,
                              int currentPlayers, int maxPlayers, String version, String hostUuid,
                              String bannedHashes, long hostRttMs, long openedAtMs, String memberHashes) {
        return "{\"room_update\":{"
                + "\"code\":\"" + escape(code) + "\","
                + "\"title\":\"" + escape(title) + "\","
                + "\"nickname\":\"" + escape(nickname) + "\","
                + "\"channel\":\"" + escape(channel) + "\","
                + "\"channel_and\":" + channelAnd + ","
                + "\"current\":" + currentPlayers + ","
                + "\"max\":" + maxPlayers + ","
                + "\"version\":\"" + escape(version) + "\","
                + "\"host_uuid\":\"" + escape(hostUuid) + "\","
                + "\"banned_hashes\":\"" + escape(bannedHashes) + "\","
                // F4.0: 지금 방에 있는 사람(방장 포함)을 방 코드로 솔팅한 해시 — 방 목록에서 "친구가 있는 방" 표시용
                + "\"member_hashes\":\"" + escape(memberHashes) + "\","
                + "\"host_rtt_ms\":" + hostRttMs + ","
                + "\"opened_at_ms\":" + openedAtMs
                + "}}";
    }

    // ── 파싱 — 1.5 에서 손으로 짠 문자열 찾기를 Gson 트리로 바꿨다 ───────────────────────────
    // 예전 방식은 "키 문자열이 처음 나오는 자리"를 찾았다 — 그래서 방 제목·닉네임 같은 <b>값 안에</b> "\"description\""
    // 같은 글자가 들어 있으면 엉뚱한 자리를 읽었고(제목으로 메시지를 위조), 따옴표 이스케이프도 일부만 풀었다.
    // 지금은 JSON 으로 제대로 읽고, 키는 구조 안에서만 찾는다. 같은 메시지를 여러 번 묻는 경우가 많아(field 를
    // 연달아 부름) 마지막 한 건의 파싱 결과를 기억해 둔다.

    private static String lastSrc;
    private static JsonElement lastTree;

    private static synchronized JsonElement tree(String json) {
        if (json == null) return null;
        if (json.equals(lastSrc)) return lastTree;
        JsonElement t;
        try {
            t = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            t = null; // JSON 이 아니면 아무 키도 없는 것으로
        }
        lastSrc = json;
        lastTree = t;
        return t;
    }

    /** 키를 찾는다 — 자기 키 먼저, 없으면 값 속으로 깊이 우선(예전 문자열 찾기와 같은 "처음 나오는 것" 순서). */
    private static JsonElement find(JsonElement e, String key) {
        if (e == null) return null;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has(key)) return o.get(key);
            for (Map.Entry<String, JsonElement> m : o.entrySet()) {
                JsonElement r = find(m.getValue(), key);
                if (r != null) return r;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                JsonElement r = find(x, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 맨 바깥 객체에 이 키가 있는지(종류 판별용 — {"delta":..} / {"room_update":..} 등). */
    public static boolean has(String json, String key) {
        JsonElement t = tree(json);
        return t != null && t.isJsonObject() && t.getAsJsonObject().has(key);
    }

    /** control.peers 배열 → [name, remote(연결 안 됐으면 null)] 목록 */
    public static List<String[]> peers(String json) {
        return peerObjects(json, "peers");
    }

    /** delta.joined 배열 → [name, remote] 목록 — peers()와 같은 모양(Peer 객체 배열)이라 키만 다르다. */
    static List<String[]> joined(String json) {
        return peerObjects(json, "joined");
    }

    /** delta.left 배열 → 이름 목록(문자열 배열, 객체가 아니다). */
    static List<String> left(String json) {
        List<String> out = new ArrayList<>();
        JsonElement arr = find(tree(json), "left");
        if (arr == null || !arr.isJsonArray()) return out;
        for (JsonElement e : arr.getAsJsonArray()) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    /** delta.full — true면 joined가 "지금 전원"(기존 걸 버리고 통째로 갈아끼워야 함)이라는 뜻. */
    static boolean isFullDelta(String json) {
        String delta = object(json, "delta");
        return delta != null && "true".equals(field(delta, "full"));
    }

    /** {key: [ {..}, {..} ]} 형태의 오브젝트 배열 → [name, remote] 목록. peers/joined가 공유. */
    private static List<String[]> peerObjects(String json, String key) {
        return objectsOf(json, key, "name", "remote");
    }

    /** servers 배열 → [url, user, pass] 목록 */
    static List<String[]> servers(String json) {
        return objectsOf(json, "servers", "url", "user", "pass");
    }

    private static List<String[]> objectsOf(String json, String key, String... fields) {
        List<String[]> out = new ArrayList<>();
        JsonElement arr = find(tree(json), key);
        if (arr == null || !arr.isJsonArray()) return out;
        for (JsonElement e : arr.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String[] row = new String[fields.length];
            for (int i = 0; i < fields.length; i++) row[i] = primitive(o.get(fields[i]));
            out.add(row);
        }
        return out;
    }

    /** {"key":{...}} 내부 오브젝트(JSON 문자열), 없거나 객체가 아니면 null. */
    public static String object(String json, String key) {
        JsonElement e = find(tree(json), key);
        return e != null && e.isJsonObject() ? e.toString() : null;
    }

    /** 문자열/숫자/불리언 필드 값(문자열로), 없거나 null·객체·배열이면 null. */
    public static String field(String json, String key) {
        return primitive(find(tree(json), key));
    }

    private static String primitive(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r\n", "\\r\\n").replace("\n", "\\n").replace("\r", "\\r");
    }

    private VillasMsg() {}
}