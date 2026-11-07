package kfc.udp.client.signaling;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 이 모드 jar 의 SHA-256(1.5~) — 랑데부 URL 의 {@code b=} 로 실려 간다(QuicBridge.clientInfo). 서버가 배포한 jar
 * 해시 목록과 대조해 변조된 빌드를 알아본다. 개발 환경(jar 가 아니라 폴더에서 실행)이면 "dev".
 * <p>
 * 가맹점 빌드는 원작자 배포본과 해시가 다르다 — 본점 서버가 이 값으로 비공식 빌드를 거르면(unofficial) 본점 접속이
 * 막힐 수 있다. 가맹점 서버에는 우리 배포 jar 의 해시를 등록해 두면 된다(PORT-NOTES).
 */
public final class BuildProof {

    private static volatile String cached;

    private BuildProof() {}

    public static String sha256() {
        String c = cached;
        if (c != null) return c;
        synchronized (BuildProof.class) {
            if (cached == null) cached = compute();
            return cached;
        }
    }

    private static String compute() {
        try {
            ModContainer container = FabricLoader.getInstance().getModContainer("instant-p2p").orElse(null);
            if (container == null) return "dev";
            for (Path p : container.getOrigin().getPaths()) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".jar")) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = Files.newInputStream(p)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                return HexFormat.of().formatHex(md.digest());
            }
        } catch (Exception ignored) {
            // 못 읽으면 개발 환경과 같게 취급한다
        }
        return "dev";
    }
}
