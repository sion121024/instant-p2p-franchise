package kfc.udp.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java(JRE) 취약점(CVE-2026-91403) 확인 — 가맹점 F4.0 부터 <b>버전 문자열이 아니라 실제로 패치됐는지</b>를 본다.
 * <p>
 * 이 취약점은 eMudhra 의 손상된 중간 인증서 8개를 JSSE(TLS)가 믿는 문제다. 그 중간 인증서들은
 * {@code emSign ECC Root CA - G3} 와 {@code AAA Certificate Services}(Sectigo/Comodo 교차 서명) 루트에 묶여 있다.
 * 배포판마다 고친 방식·버전 번호가 달라서(OpenJDK 27.0.1.0.2 는 blocked.certs 로 막고, 그 뒤 판은 루트를 아예 뺐다.
 * Temurin 처럼 처음부터 그 루트가 없는 배포판도 있다) 버전 번호로는 판정이 틀린다. 그래서 지금 이 JVM 이
 * <b>실제로 쓰는 신뢰 저장소</b>를 들여다본다:
 * <ol>
 *   <li>위 두 루트가 신뢰 목록에 없으면 → 패치됨(또는 애초에 영향 없음) = OK.</li>
 *   <li>루트가 있어도 {@code lib/security/blocked.certs} 가 차단 목록 갱신판(SHA-256 해시 {@link #BLOCKLIST_PATCHED_MIN}개
 *       이상 — 갱신 전 39줄, 갱신 후 87줄)이면 → 패치됨 = OK.</li>
 *   <li>둘 다 아니면 패치 안 됨(VULNERABLE). <b>묻지 않고 막거나 고치지 않는다</b> — 경고창에서 「자동으로 해결」 /
 *       「해결하지 않고 계속」을 고르게 한다. 해결을 고르면 그 자리에서 JVM 기본 TLS 신뢰 목록에서 문제 루트 4개를 뺀다
 *       ({@link #fixNow}, 패치된 JDK 와 같은 조치). 그 뒤로는 다음 실행부터 시작할 때 자동으로 적용한다
 *       (config/instant-p2p/jre-autofix). 고르기 전에 P2P 를 누르면 같은 창이 다시 뜬다.</li>
 *   <li>신뢰 저장소를 아예 못 읽으면 예전 버전 표로 판정한다(아래 AFFECTED).</li>
 * </ol>
 * 경고창의 「위험을 알고 계속」은 그대로 — 이번 실행 동안만 거절을 푼다. 마인크래프트 클래스를 쓰지 않는다.
 */
public final class JreGuard {

    public enum Level { OK, WARN, VULNERABLE }

    /** 실제 상태(경고창·로그 표시용). */
    public enum Patch { ROOTS_ABSENT, BLOCKLIST, MITIGATED, UNPATCHED, UNKNOWN }

    /** 손상된 중간 인증서가 묶인 루트 — 이게 신뢰 목록에 있으면 영향권. */
    private static final String[] AFFECTED_ROOTS = {
            "CN=emSign ECC Root CA - G3",
            "CN=AAA Certificate Services,O=Comodo CA Limited",
    };
    /** 완화할 때 빼는 루트 — 패치된 OpenJDK 가 cacerts 에서 지운 4개(JDK-8395690)와 같다. */
    private static final String[] REMOVE_ROOTS = {
            "CN=emSign ECC Root CA - G3",
            "CN=emSign Root CA - G1",
            "CN=emSign Root CA - G2",
            "CN=AAA Certificate Services,O=Comodo CA Limited",
    };
    /** 차단 목록 갱신판으로 볼 최소 해시 줄 수(갱신 전 39 → 갱신 후 87). */
    static final int BLOCKLIST_PATCHED_MIN = 70;

    /**
     * 신뢰 저장소를 못 읽을 때만 쓰는 예전 버전 표: 기능 줄 → 취약한 마지막 버전(이 버전 <b>이하</b>가 취약).
     */
    private static final Map<Integer, int[]> AFFECTED = new TreeMap<>(Map.of(
            8, new int[]{8, 0, 511},
            11, new int[]{11, 0, 33},
            17, new int[]{17, 0, 21},
            21, new int[]{21, 0, 13},
            25, new int[]{25, 0, 5},
            27, new int[]{27, 0, 1}));
    private static final int WARN_ONLY_FEATURE = 26;
    private static final int NEWEST_CHECKED_FEATURE = 27;

    public static final String DOWNLOAD_URL = "https://adoptium.net/temurin/releases/";

    private static volatile boolean proceedAtOwnRisk;
    /** 패치 안 된 JVM 에서 기본 SSLContext 로 깔아 두는 교체형 TrustManager — 동의 전에는 원래 것 그대로 위임한다. */
    private static volatile SwitchTrustManager switchTm;
    private static final java.nio.file.Path AUTOFIX = java.nio.file.Path.of("config", "instant-p2p", "jre-autofix");
    private static volatile Level cached;
    private static volatile Patch patch;

    private JreGuard() {}

    /**
     * 가장 먼저(KfcudpClient.onInitializeClient 맨 앞) 한 번 부른다 — 판정만 한다. 패치 안 된 JVM 이면 동작은 그대로인
     * 교체형 TrustManager 를 기본 TLS 에 깔아 두어(HTTP 클라이언트들이 만들어지기 전에), 나중에 사용자가 「자동으로 해결」을
     * 고르면 이미 만들어진 클라이언트까지 그 자리에서 고쳐지게 한다. 예전에 해결을 골랐으면(jre-autofix) 바로 적용한다.
     */
    public static synchronized void init() {
        if (cached != null) return;
        Patch p;
        try {
            p = inspect();
        } catch (Throwable t) {
            p = Patch.UNKNOWN;
        }
        if (p == Patch.UNPATCHED) {
            installSwitch();
            if (java.nio.file.Files.exists(AUTOFIX) && applyFix()) p = Patch.MITIGATED;
        }
        patch = p;
        cached = switch (p) {
            case ROOTS_ABSENT, BLOCKLIST, MITIGATED -> Level.OK;
            case UNPATCHED -> Level.VULNERABLE;
            case UNKNOWN -> {
                try {
                    yield classify(currentVersion());
                } catch (RuntimeException e) {
                    yield Level.OK;
                }
            }
        };
        org.slf4j.LoggerFactory.getLogger("instant-p2p-jre").info("[jre] {} — CVE-2026-91403 판정: {} → {}", currentText(), p, cached);
    }

    /**
     * 경고창의 「자동으로 해결」 — 지금 바로 고치고, 다음 실행부터 자동으로 적용하게 기억한다. 성공하면 true(P2P 정상).
     * 실패해도 P2P 를 막지 않는다(사용자가 이미 골랐으므로).
     */
    public static synchronized boolean fixNow() {
        proceedAtOwnRisk = true;
        if (patch == Patch.MITIGATED || cached == Level.OK) return true;
        if (switchTm == null) installSwitch();
        boolean ok = applyFix();
        if (ok) {
            patch = Patch.MITIGATED;
            cached = Level.OK;
            try {
                java.nio.file.Files.createDirectories(AUTOFIX.getParent());
                java.nio.file.Files.writeString(AUTOFIX, "1\n");
            } catch (java.io.IOException ignored) {
            }
        }
        return ok;
    }

    /** 경고창의 「해결하지 않고 계속」 — 이번 실행 동안 묻지 않는다. */
    public static void continueWithoutFix() {
        proceedAtOwnRisk = true;
    }

    public static Patch patch() {
        if (patch == null) init();
        return patch;
    }

    /** 이 JVM 이 실제로 쓰는 신뢰 목록(기본 TrustManager)을 본다. */
    static Patch inspect() throws Exception {
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        boolean affected = false;
        boolean any = false;
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (!(tm instanceof javax.net.ssl.X509TrustManager x)) continue;
            for (java.security.cert.X509Certificate c : x.getAcceptedIssuers()) {
                any = true;
                if (matches(c, AFFECTED_ROOTS)) affected = true;
            }
        }
        if (!any) return Patch.UNKNOWN; // 신뢰 목록이 비어 보이면(특수 공급자) 판정하지 않는다
        if (!affected) return Patch.ROOTS_ABSENT;
        return blocklistHashes() >= BLOCKLIST_PATCHED_MIN ? Patch.BLOCKLIST : Patch.UNPATCHED;
    }

    /** 인증서 주체가 spec 의 모든 속성(예: "CN=…,O=…")을 가졌는지. 속성 순서는 상관없다. */
    private static boolean matches(java.security.cert.X509Certificate c, String[] subjects) {
        String dn = c.getSubjectX500Principal().getName(javax.security.auth.x500.X500Principal.RFC2253);
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(dn.split(",")));
        for (String s : subjects) {
            if (have.containsAll(java.util.Arrays.asList(s.split(",")))) return true;
        }
        return false;
    }

    /** {@code java.home/lib/security/blocked.certs} 의 SHA-256 해시 줄 수(없으면 0). */
    static int blocklistHashes() {
        try {
            java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("java.home"), "lib", "security", "blocked.certs");
            if (!java.nio.file.Files.isRegularFile(f)) return 0;
            int n = 0;
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String t = line.trim();
                if (t.length() == 64 && t.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 기본 TLS 에 교체형 TrustManager 를 깐다(위임 대상은 처음엔 원래 기본 TrustManager — 동작 변화 없음). */
    private static void installSwitch() {
        try {
            javax.net.ssl.X509ExtendedTrustManager orig = defaultTrustManager(null);
            if (orig == null) return;
            SwitchTrustManager tm = new SwitchTrustManager(orig);
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(defaultKeyManagers(), new javax.net.ssl.TrustManager[]{tm}, null);
            javax.net.ssl.SSLContext.setDefault(ctx);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            switchTm = tm;
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("instant-p2p-jre").warn("[jre] TLS 준비 실패: {}", t.toString());
        }
    }

    /** 문제 루트를 뺀 신뢰 목록으로 위임 대상을 바꾼다. 성공하면 true. */
    static boolean applyFix() {
        SwitchTrustManager tm = switchTm;
        if (tm == null) return false;
        try {
            java.security.KeyStore src = loadDefaultTrustStore();
            if (src == null) return false;
            java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            int kept = 0, removed = 0;
            for (java.util.Enumeration<String> e = src.aliases(); e.hasMoreElements(); ) {
                String alias = e.nextElement();
                java.security.cert.Certificate c = src.getCertificate(alias);
                if (!(c instanceof java.security.cert.X509Certificate x)) continue;
                if (matches(x, REMOVE_ROOTS)) { removed++; continue; }
                ks.setCertificateEntry(alias, x);
                kept++;
            }
            if (kept == 0 || removed == 0) return false;
            javax.net.ssl.X509ExtendedTrustManager filtered = defaultTrustManager(ks);
            if (filtered == null) return false;
            tm.delegate = filtered;
            org.slf4j.LoggerFactory.getLogger("instant-p2p-jre").warn(
                    "[jre] 패치 안 된 Java — 문제 루트 인증서 {}개를 기본 TLS 신뢰 목록에서 뺐다(사용자 동의)", removed);
            return true;
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("instant-p2p-jre").warn("[jre] 해결 실패: {}", t.toString());
            return false;
        }
    }

    private static javax.net.ssl.X509ExtendedTrustManager defaultTrustManager(java.security.KeyStore ks) throws Exception {
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (javax.net.ssl.TrustManager t : tmf.getTrustManagers()) {
            if (t instanceof javax.net.ssl.X509ExtendedTrustManager x) return x;
        }
        return null;
    }

    /** JDK 기본과 같게 javax.net.ssl.keyStore 가 있으면 그 클라이언트 인증서를 쓴다(없으면 null = 없음). */
    private static javax.net.ssl.KeyManager[] defaultKeyManagers() {
        try {
            String path = System.getProperty("javax.net.ssl.keyStore");
            if (path == null || path.isEmpty() || "NONE".equals(path)) return null;
            String type = System.getProperty("javax.net.ssl.keyStoreType", java.security.KeyStore.getDefaultType());
            String pass = System.getProperty("javax.net.ssl.keyStorePassword");
            char[] pw = pass != null ? pass.toCharArray() : null;
            java.security.KeyStore ks = java.security.KeyStore.getInstance(type);
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))) {
                ks.load(in, pw);
            }
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            return kmf.getKeyManagers();
        } catch (Exception e) {
            return null;
        }
    }

    /** 위임 대상을 실행 중에 바꿀 수 있는 TrustManager. */
    static final class SwitchTrustManager extends javax.net.ssl.X509ExtendedTrustManager {
        volatile javax.net.ssl.X509ExtendedTrustManager delegate;

        SwitchTrustManager(javax.net.ssl.X509ExtendedTrustManager d) { this.delegate = d; }

        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) throws java.security.cert.CertificateException { delegate.checkClientTrusted(c, a); }
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) throws java.security.cert.CertificateException { delegate.checkServerTrusted(c, a); }
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) throws java.security.cert.CertificateException { delegate.checkClientTrusted(c, a, s); }
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) throws java.security.cert.CertificateException { delegate.checkServerTrusted(c, a, s); }
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) throws java.security.cert.CertificateException { delegate.checkClientTrusted(c, a, e); }
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) throws java.security.cert.CertificateException { delegate.checkServerTrusted(c, a, e); }
    }

    /** JDK 와 같은 순서로 기본 신뢰 저장소를 찾는다: javax.net.ssl.trustStore → jssecacerts → cacerts. */
    private static java.security.KeyStore loadDefaultTrustStore() throws Exception {
        String prop = System.getProperty("javax.net.ssl.trustStore");
        String type = System.getProperty("javax.net.ssl.trustStoreType", java.security.KeyStore.getDefaultType());
        String pass = System.getProperty("javax.net.ssl.trustStorePassword");
        java.nio.file.Path sec = java.nio.file.Path.of(System.getProperty("java.home"), "lib", "security");
        java.nio.file.Path file = null;
        if (prop != null && !prop.isEmpty() && !"NONE".equals(prop)) file = java.nio.file.Path.of(prop);
        else if (java.nio.file.Files.isRegularFile(sec.resolve("jssecacerts"))) file = sec.resolve("jssecacerts");
        else if (java.nio.file.Files.isRegularFile(sec.resolve("cacerts"))) file = sec.resolve("cacerts");
        java.security.KeyStore ks = java.security.KeyStore.getInstance(type);
        if (file == null) {
            if (!type.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) return null;
            ks.load(null, null); // Windows-ROOT 처럼 파일이 없는 저장소
            return ks;
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            ks.load(in, pass != null ? pass.toCharArray() : null);
        }
        return ks;
    }

    /** 지금 실행 중인 자바의 판정. 예외를 던지지 않는다. */
    public static Level level() {
        if (cached == null) init();
        return cached;
    }

    /** P2P 방 열기·접속을 지금 거절해야 하는지 — 취약하고, 이번 실행에서 「위험을 알고 계속」을 고르지 않았으면 true. */
    public static boolean blocksP2p() {
        return level() == Level.VULNERABLE && !proceedAtOwnRisk;
    }

    /** 경고창에서 「위험을 알고 계속」을 고른 경우 — 이번 실행 동안만. */
    public static void proceedAtOwnRisk() {
        proceedAtOwnRisk = true;
    }

    public static boolean isProceedingAtOwnRisk() {
        return proceedAtOwnRisk;
    }

    /** 표시용 현재 버전(예: "21.0.12+7"). */
    public static String currentText() {
        try {
            return Runtime.version().toString();
        } catch (RuntimeException e) {
            return System.getProperty("java.version", "?");
        }
    }

    /** 표시용 "이 버전보다 새 것이 필요" 기준 — 표에 없는 줄이면 "25.0.5"(가장 가까운 지원 줄)를 권한다. */
    public static String affectedText() {
        List<Integer> v = currentVersion();
        int[] a = AFFECTED.get(v.isEmpty() ? 0 : v.get(0));
        if (a == null) a = AFFECTED.get(25);
        return a[0] == 8 ? "8u" + a[2] : a[0] + "." + a[1] + "." + a[2];
    }

    static Level classify(List<Integer> v) {
        if (v.isEmpty()) return Level.OK;
        int feature = v.get(0);
        if (feature > NEWEST_CHECKED_FEATURE) return Level.OK;
        if (feature == WARN_ONLY_FEATURE) return Level.WARN;
        int[] affected = AFFECTED.get(feature);
        if (affected == null) return Level.VULNERABLE; // 지원이 끝난 줄(22~24 등) — 고친 판이 나오지 않는다
        return compare(v, affected) <= 0 ? Level.VULNERABLE : Level.OK;
    }

    /**
     * 지금 자바 버전을 [기능, 중간, 업데이트, ...] 로. 자바 8 은 "1.8.0_511" 꼴이라 [8, 0, 511] 로 바꾼다
     * (마인크래프트 1.21 은 자바 21 이상이라 실제로는 오지 않는다).
     */
    private static List<Integer> currentVersion() {
        try {
            return new ArrayList<>(Runtime.version().version());
        } catch (RuntimeException | LinkageError e) {
            String s = System.getProperty("java.version", "");
            List<Integer> out = new ArrayList<>();
            if (s.startsWith("1.8")) {
                out.add(8);
                out.add(0);
                int u = s.indexOf('_');
                out.add(u >= 0 ? parseLeadingInt(s.substring(u + 1)) : 0);
            }
            return out;
        }
    }

    private static int parseLeadingInt(String s) {
        int n = 0;
        for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)); i++) n = n * 10 + (s.charAt(i) - '0');
        return n;
    }

    static int compare(List<Integer> a, int[] b) {
        for (int i = 0; i < Math.max(a.size(), b.length); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
