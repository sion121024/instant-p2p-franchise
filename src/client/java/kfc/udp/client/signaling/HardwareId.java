package kfc.udp.client.signaling;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;

/**
 * 가벼운 "기기 지문" — 마인크래프트 의존성 0, 순수 자바로만 계산한다.
 * <p>
 * <b>정확히 뭘 막고 뭘 못 막나</b> — 진짜 하드웨어 ID(디스크 시리얼, 메인보드 UUID 등)는 네이티브
 * 호출 없이는 순수 자바에서 못 읽는다. 대신 OS 이름/버전/아키텍처, 논리 코어 수, 주 네트워크
 * 인터페이스의 MAC 주소(있으면)를 섞어 SHA-256 해시 하나로 만든다 — 킥당한 사람이 <b>새
 * 마인크래프트 계정으로 재접속하는 것(계정 우회)은 막지만</b>, PC를 포맷하거나 가상머신을 새로
 * 파거나 네트워크 인터페이스가 안 잡히는 환경(일부 샌드박스)에서 오는 건 못 막는다. "완벽한 기기
 * 식별"이 아니라 "제일 흔한 우회 방법 하나를 막는 저비용 장치"로 봐야 한다.
 * <p>
 * <b>전송되는 건 해시뿐이다</b> — MAC 주소 등 원본 값은 로컬에서 해시로 바뀐 다음에만 네트워크로
 * 나간다(랑데부 접속 URL의 hwid 파라미터로 중계 서버에만 전달 — QuicHost/QuicClient), 서버도 원본 값을 복원할 수 없다.
 * 밴 판정은 방장이 아니라 <b>중계 서버 운영자만</b> 한다(mc-relay-server/hwidban.go).
 */
public final class HardwareId {

    private static volatile String cached;

    private HardwareId() {}

    /** 이 기기의 지문(해시) — 프로세스당 한 번만 계산해 캐시한다. <b>MAC 주소를 못 읽으면 빈 문자열</b>
     * — 그러면 서버는 HWID 검사를 건너뛴다. MAC 없이 OS/코어 수만으로 만든 해시는 같은 사양 PC끼리
     * 전부 똑같아져서, 그중 한 명을 밴하면 <b>무관한 사람 수천 명이 같이 밴되는</b> 사고가 난다. */
    public static synchronized String get() {
        if (cached != null) return cached;
        String mac = primaryMacAddress();
        if (mac == null) {
            cached = "";
            return cached;
        }
        String raw = System.getProperty("os.name", "") + '|'
                + System.getProperty("os.arch", "") + '|'
                + Runtime.getRuntime().availableProcessors() + '|'
                + mac;
        // os.version은 뺐다 — 윈도우 업데이트 한 번에 값이 바뀌어 지문이 흔들린다(밴이 저절로 풀림).
        cached = sha256Hex(raw);
        return cached;
    }

    private static volatile String cachedComponents;

    /**
     * 기기 구성요소별 지문(쉼표로 이은 SHA-256 hex, 최대 8개) — 서버는 이 중 <b>하나라도</b> 밴 목록에
     * 있으면 거부하고 나머지를 같은 밴에 자동으로 추가한다(mc-relay-server/hwidban.go). 그래서 MAC만
     * 바꾸거나 윈도우만 다시 까는 식으로 하나씩 바꿔서는 밴을 못 빠져나간다.
     * <ul>
     *   <li>물리 네트워크 어댑터의 MAC — 어댑터마다 하나(유선·무선 둘 다 있으면 둘 다)</li>
     *   <li>OS 설치 ID — 윈도우 MachineGuid / 리눅스 /etc/machine-id / 맥 IOPlatformUUID</li>
     *   <li>시스템 디스크 볼륨 시리얼(윈도우 C:)</li>
     * </ul>
     * OS 설치 ID·디스크 시리얼은 <b>컴퓨터 이름과 같이</b> 해시한다 — PC방처럼 같은 디스크 이미지를 복제한
     * PC들은 이 값이 전부 같아서, 한 자리를 밴하면 그 PC방 전체가 밴되는 사고를 막기 위해서다.
     * 원본 값은 절대 안 나간다(해시만). 값을 못 읽는 항목은 그냥 빠진다.
     */
    public static synchronized String components() {
        if (cachedComponents != null) return cachedComponents;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String mac : physicalMacs()) out.add(sha256Hex("mac|" + mac));
        String host = hostName();
        String installId = installId();
        if (installId != null && !installId.isBlank()) out.add(sha256Hex("os|" + installId.trim().toLowerCase(java.util.Locale.ROOT) + "|" + host));
        String vol = volumeSerial();
        if (vol != null && !vol.isBlank()) out.add(sha256Hex("vol|" + vol.trim().toUpperCase(java.util.Locale.ROOT) + "|" + host));
        java.util.List<String> list = new java.util.ArrayList<>(out);
        cachedComponents = String.join(",", list.subList(0, Math.min(8, list.size())));
        return cachedComponents;
    }

    /** 랑데부 URL에 붙일 쿼리 조각 — "&hwid=...&hw2=..." (QuicHost/QuicClient 공용). */
    public static String query() {
        // 가맹점 약관에 동의한 경우에만 보낸다. 본점(공식 서버)이나 동의 전에는 아무것도 안 보낸다.
        if (!P2PConfig.franchiseConsented()) return "";
        String c = components();
        return "&hwid=" + get() + (c.isEmpty() ? "" : "&hw2=" + c);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null || n.isBlank()) n = System.getenv("HOSTNAME");
        if (n == null || n.isBlank()) {
            try {
                n = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                n = "";
            }
        }
        return n.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** OS 설치 ID. 못 읽으면 null. */
    private static String installId() {
        try {
            if (isWindows()) {
                String o = run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("MachineGuid\\s+REG_SZ\\s+([0-9a-fA-F-]{36})").matcher(o);
                return m.find() ? m.group(1) : null;
            }
            for (String f : new String[]{"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
                java.nio.file.Path p = java.nio.file.Path.of(f);
                if (java.nio.file.Files.isReadable(p)) return java.nio.file.Files.readString(p).trim();
            }
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                String o = run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"IOPlatformUUID\" = \"([^\"]+)\"").matcher(o);
                return m.find() ? m.group(1) : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 윈도우 C: 볼륨 시리얼(예: 1A2B-3C4D). 다른 OS는 null. */
    private static String volumeSerial() {
        if (!isWindows()) return null;
        try {
            String o = run("cmd", "/c", "vol", "C:");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})").matcher(o);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 짧은 외부 명령 — 최대 3초, 출력 8KB까지. */
    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] buf;
        try (java.io.InputStream in = p.getInputStream()) {
            buf = in.readNBytes(8192);
        }
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        return new String(buf, java.nio.charset.Charset.defaultCharset());
    }

    /** 가상 어댑터 이름 접두어 — VPN·도커·VM 어댑터는 켜고 끌 때마다 생겼다 사라져서 지문을 흔든다. */
    private static final String[] VIRTUAL_PREFIXES = {
            "docker", "veth", "br-", "virbr", "vbox", "vmnet", "vethernet", "tun", "tap", "utun",
            "wg", "zt", "tailscale", "ham", "llw", "awdl", "anpi", "bridge", "lo"
    };

    /** 루프백/가상/비활성/가상어댑터를 빼고 남은 물리 인터페이스 중 <b>이름순 첫 번째</b> — 열거 순서는
     * OS가 보장하지 않으므로 정렬해야 재부팅·어댑터 추가 후에도 같은 값이 나온다. 없으면 null. */
    private static String primaryMacAddress() {
        java.util.TreeMap<String, String> byName = physicalMacMap();
        return byName.isEmpty() ? null : byName.firstEntry().getValue();
    }

    /** 물리 어댑터 MAC 전부(이름순, 최대 4개). */
    private static java.util.List<String> physicalMacs() {
        java.util.List<String> l = new java.util.ArrayList<>(physicalMacMap().values());
        return l.subList(0, Math.min(4, l.size()));
    }

    private static java.util.TreeMap<String, String> physicalMacMap() {
        java.util.TreeMap<String, String> byName = new java.util.TreeMap<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                boolean virtual = false;
                for (String p : VIRTUAL_PREFIXES) if (name.startsWith(p)) { virtual = true; break; }
                if (virtual) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length != 6) continue;
                if ((mac[0] & 0x02) != 0) continue; // 로컬 관리(랜덤/가상) MAC — 기기 고유값이 아니다
                boolean allZero = true;
                for (byte b : mac) if (b != 0) { allZero = false; break; }
                if (allZero) continue;
                StringBuilder sb = new StringBuilder(12);
                for (byte b : mac) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                byName.put(name, sb.toString());
            }
        } catch (SocketException ignored) {
            // 인터페이스 목록 자체를 못 읽는 환경 — 빈 목록(지문 없음)으로.
        }
        return byName;
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256은 모든 JVM에 필수로 들어 있다
        }
    }
}
