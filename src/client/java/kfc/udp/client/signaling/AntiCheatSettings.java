package kfc.udp.client.signaling;

import com.google.gson.JsonObject;
import kfc.udp.client.anticheat.AcConfig;

/**
 * 안티치트 설정 — 방 설정 화면의 "안티치트 설정"(AntiCheatScreen)에서 바꾸고, settings.json의
 * {@code "antiCheat"} 항목에 저장된다(P2PConfig.getAntiCheat/setAntiCheat). 마인크래프트 의존성 없음.
 * <p>
 * F3.5부터 검사가 분류별(이동·전투·월드·패킷·무결성)로 바뀌었다. 예전 저장본의 항목(speed/flight/reach/
 * autoClicker/nuker)은 읽을 때 새 분류로 옮긴다.
 */
public final class AntiCheatSettings {

    /** 민감도 — 알림·강퇴 문턱 VL에 곱하는 배율. 느슨할수록 문턱이 높아 오탐이 줄어든다. */
    public enum Sensitivity {
        LOOSE(1.6), NORMAL(1.0), STRICT(0.65);

        public final double thresholdScale;

        Sensitivity(double s) {
            this.thresholdScale = s;
        }

        public Sensitivity next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * 적발 시 처리 — KICK: 되돌리기·취소 + 점수가 높으면 강퇴, MITIGATE: 되돌리기·취소만(강퇴 안 함),
     * WARN: 아무것도 막지 않고 기록·알림만.
     */
    public enum Action {
        KICK, MITIGATE, WARN;

        public Action next() {
            return values()[(ordinal() + 1) % values().length];
        }

        AcConfig.Action toEngine() {
            return switch (this) {
                case KICK -> AcConfig.Action.KICK;
                case MITIGATE -> AcConfig.Action.MITIGATE;
                case WARN -> AcConfig.Action.ALERT;
            };
        }
    }

    public boolean enabled = true;
    public boolean movement = true;
    public boolean combat = true;
    public boolean world = true;
    public boolean packets = true;
    public boolean integrity = true;
    /** 조준 통계(감도 GCD·조준 고정) — 오탐 위험이 있어 알림만 하는 검사. */
    public boolean aimStats = true;
    public boolean chatSpam = true;
    public boolean notifyHost = true;
    public Sensitivity sensitivity = Sensitivity.NORMAL;
    public Action action = Action.KICK;

    public AntiCheatSettings copy() {
        AntiCheatSettings c = new AntiCheatSettings();
        c.enabled = enabled;
        c.movement = movement;
        c.combat = combat;
        c.world = world;
        c.packets = packets;
        c.integrity = integrity;
        c.aimStats = aimStats;
        c.chatSpam = chatSpam;
        c.notifyHost = notifyHost;
        c.sensitivity = sensitivity;
        c.action = action;
        return c;
    }

    /** 엔진 설정으로. */
    public AcConfig toEngineConfig() {
        AcConfig c = new AcConfig();
        c.enabled = enabled;
        c.movement = movement;
        c.combat = combat;
        c.world = world;
        c.packets = packets;
        c.integrity = integrity;
        c.aimStats = aimStats;
        c.thresholdScale = sensitivity.thresholdScale;
        c.action = action.toEngine();
        return c;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 2);
        o.addProperty("enabled", enabled);
        o.addProperty("movement", movement);
        o.addProperty("combat", combat);
        o.addProperty("world", world);
        o.addProperty("packets", packets);
        o.addProperty("integrity", integrity);
        o.addProperty("aimStats", aimStats);
        o.addProperty("chatSpam", chatSpam);
        o.addProperty("notifyHost", notifyHost);
        o.addProperty("sensitivity", sensitivity.name());
        o.addProperty("action", action.name());
        return o;
    }

    /** 저장본에서 읽는다 — 항목이 없거나 값이 이상하면 그 항목만 기본값. 예전(v1) 항목은 새 분류로 옮긴다. */
    public static AntiCheatSettings fromJson(JsonObject o) {
        AntiCheatSettings s = new AntiCheatSettings();
        if (o == null) return s;
        s.enabled = bool(o, "enabled", s.enabled);
        if (!o.has("v")) {
            // F3.4 이전 저장본
            s.movement = bool(o, "speed", true) || bool(o, "flight", true);
            s.combat = bool(o, "reach", true) || bool(o, "autoClicker", true);
            s.world = bool(o, "nuker", true);
        } else {
            s.movement = bool(o, "movement", s.movement);
            s.combat = bool(o, "combat", s.combat);
            s.world = bool(o, "world", s.world);
            s.packets = bool(o, "packets", s.packets);
            s.integrity = bool(o, "integrity", s.integrity);
            s.aimStats = bool(o, "aimStats", s.aimStats);
        }
        s.chatSpam = bool(o, "chatSpam", s.chatSpam);
        s.notifyHost = bool(o, "notifyHost", s.notifyHost);
        try {
            if (o.has("sensitivity")) s.sensitivity = Sensitivity.valueOf(o.get("sensitivity").getAsString());
        } catch (Exception ignored) {
        }
        try {
            if (o.has("action")) s.action = Action.valueOf(o.get("action").getAsString());
        } catch (Exception ignored) {
        }
        return s;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
