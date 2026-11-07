package kfc.udp.client.signaling;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 마인크래프트 기본 친구 목록(26.2~, 소셜 → 친구) 읽기 — 가맹점 F4.0 방 목록 "친구가 있는 방" 표시용.
 * <p>
 * 친구 목록은 바닐라가 주기적으로 받아 두는 {@code Minecraft.remoteFriendListUpdateHandler.getLatestFriendData()} 를
 * 그대로 읽는다(네트워크 요청 없음). 26.2 는 authlib {@code yggdrasil.response}, 26.3 은 {@code services.response}
 * 패키지라 클래스가 달라서 이름으로 찾는다(26.x 는 난독화가 없어 이름이 그대로다). 26.1 이하·Yarn 판은 기능이 없으니
 * 빈 목록. 결과는 5초 동안 기억한다.
 * <p>
 * 방장이 공개 방 정보에 실어 보내는 접속자 해시({@code member_hashes} — 방 코드로 솔팅한 UUID 해시, P2PBanManager.roomHash)와
 * 내 친구 UUID 를 같은 방식으로 해시해 맞춘다. 원문 UUID 는 오가지 않는다.
 */
public final class FriendList {

    public record Friend(UUID id, String name) {}

    private static volatile List<Friend> cached = List.of();
    private static volatile long cachedAt;
    private static volatile boolean unavailable;
    /** 방 코드 → (그 방 member_hashes, 맞은 친구들). */
    private static final Map<String, Object[]> matchCache = Collections.synchronizedMap(new HashMap<>());
    private static volatile List<Friend> matchedAgainst = List.of();

    private FriendList() {}

    public static List<Friend> friends() {
        if (unavailable) return List.of();
        long now = System.currentTimeMillis();
        if (now - cachedAt < 5_000) return cached;
        cachedAt = now;
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Field f = mcClass.getDeclaredField("remoteFriendListUpdateHandler");
            f.setAccessible(true);
            Object handler = f.get(mc);
            if (handler == null) return cached = List.of();
            Object data = handler.getClass().getMethod("getLatestFriendData").invoke(handler);
            if (data == null) return cached = List.of();
            List<?> list = (List<?>) data.getClass().getMethod("friends").invoke(data);
            List<Friend> out = new ArrayList<>();
            Method idM = null, nameM = null;
            for (Object o : list) {
                if (idM == null) {
                    idM = o.getClass().getMethod("profileId");
                    nameM = o.getClass().getMethod("name");
                }
                Object id = idM.invoke(o);
                Object name = nameM.invoke(o);
                if (id instanceof UUID u) out.add(new Friend(u, name instanceof String s ? s : ""));
            }
            return cached = List.copyOf(out);
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
            unavailable = true; // 이 버전엔 친구 기능이 없다
            return cached = List.of();
        } catch (Throwable t) {
            return cached;
        }
    }

    /** 이 방에 있는 내 친구들(방장 포함). member_hashes 가 비었으면(본점 서버·구판 방장) 빈 목록. */
    public static List<Friend> inRoom(String code, String memberHashes) {
        if (memberHashes == null || memberHashes.isEmpty() || code == null) return List.of();
        List<Friend> fr = friends();
        if (fr.isEmpty()) return List.of();
        if (fr != matchedAgainst) {
            matchCache.clear();
            matchedAgainst = fr;
        }
        Object[] c = matchCache.get(code);
        if (c != null && memberHashes.equals(c[0])) {
            @SuppressWarnings("unchecked") List<Friend> l = (List<Friend>) c[1];
            return l;
        }
        java.util.Set<String> hashes = new java.util.HashSet<>(java.util.Arrays.asList(memberHashes.split(",")));
        Map<UUID, Friend> hit = new LinkedHashMap<>();
        for (Friend f : fr) {
            if (hashes.contains(P2PBanManager.roomHash(code, f.id().toString()))) hit.putIfAbsent(f.id(), f);
        }
        List<Friend> out = List.copyOf(hit.values());
        if (matchCache.size() > 500) matchCache.clear();
        matchCache.put(code, new Object[]{memberHashes, out});
        return out;
    }
}
