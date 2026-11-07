package kfc.udp.client.quic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * TCP 소켓 ↔ QUIC 스트림 양방향 복사.
 * <p>
 * <b>배칭 계층이 없다.</b> 예전 WebRTC 경로에는 {@code BatchPipe}가 있었다 — DataChannel은 메시지 API라
 * 256KB 상한(libwebrtc max-message-size)이 있었고, 그 상한 안에서 처리량을 내려고 배칭·워터마크·
 * writev 묶음이 필요했다. QUIC 스트림은 그냥 바이트 스트림이고 배압은 QUIC 흐름 제어가 처리하므로
 * {@code read} / {@code write} 루프면 끝이다. 계층 하나가 통째로 사라진 것이라 다시 넣지 말 것.
 */
final class QuicPump {

    private static final Logger LOG = LoggerFactory.getLogger("quic-pump");

    /** 64KB — TCP 소켓 버퍼에 있는 만큼을 한 번에 퍼오기에 충분하다. */
    private static final int BUF = 64 * 1024;

    private QuicPump() {}

    /**
     * 두 방향을 각자 스레드에서 돌리고 <b>어느 쪽이든 끝나면 양쪽을 닫는다</b> —
     * 한쪽만 닫으면 반대편 스레드가 영원히 read에 걸려 남는다.
     *
     * @param label   로그용 꼬리표(sid 등). 실제 IP는 넣지 말 것.
     * @param onClose 양쪽이 닫힌 뒤 한 번 실행된다(방향마다 불리므로 멱등이어야 한다). null 허용.
     */
    static void wire(Socket tcp, InputStream quicIn, OutputStream quicOut, String label, Runnable onClose) {
        wire(tcp, quicIn, quicOut, label, onClose, null);
    }

    /**
     * @param upThrottle QUIC → TCP 방향(방장 입장에선 접속자가 올려 보내는 쪽) 속도 제한. null 이면 제한 없음(1.5~).
     *                   접속자 한 명이 터널로 트래픽을 퍼부어 방장 회선·마크 서버를 마비시키지 못하게 방장이 건다.
     */
    static void wire(Socket tcp, InputStream quicIn, OutputStream quicOut, String label, Runnable onClose,
                     Throttle upThrottle) {
        Runnable closeBoth = () -> {
            try { tcp.close(); } catch (IOException ignored) {}
            try { quicOut.close(); } catch (IOException ignored) {}
            try { quicIn.close(); } catch (IOException ignored) {}
            if (onClose != null) onClose.run();
        };
        spawn(label + "-up", () -> copy(tcp.getInputStream(), quicOut, null), closeBoth);
        spawn(label + "-down", () -> copy(quicIn, tcp.getOutputStream(), upThrottle), closeBoth);
    }

    private interface Body { void run() throws IOException; }

    private static void spawn(String name, Body body, Runnable onEnd) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (IOException e) {
                // 정상 종료(상대가 먼저 닫음)와 구분이 안 되므로 DEBUG — 진짜 문제는 상위에서 드러난다.
                LOG.debug("[pump] {} 종료: {}", name, e.getMessage());
            } finally {
                onEnd.run();
            }
        }, "quic-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static void copy(InputStream in, OutputStream out, Throttle throttle) throws IOException {
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (throttle != null) throttle.take(n);
            out.write(buf, 0, n);
            // kwik 의 스트림 flush 는 no-op 이다(StreamOutputStreamImpl: "sends data as soon as
            // possible"). 즉 모아 두는 계층이 없어 지연이 붙지 않는다 — 스트림 규약상 남겨 둔다.
            out.flush();
        }
    }

    /**
     * 토큰 버킷 속도 제한(1.5~) — 초당 {@code rate} 바이트, 한 번에 {@code burst} 바이트까지 몰아 쓸 수 있다.
     * 넘치면 그만큼 잠재워 늦춘다. 1분 안에 잠든 시간의 합이 {@code maxBlockedMs} 를 넘으면(= 계속 퍼붓는 중)
     * {@code onFlood} 를 부르고 스트림을 끊는다. 정상 플레이(청크 업로드 없음)는 제한에 닿지도 않는다.
     */
    static final class Throttle {
        private final double rate;
        private final double burst;
        private final long maxBlockedNanos;
        private final Runnable onFlood;
        private double tokens;
        private long last = System.nanoTime();
        private long windowStart = last;
        private long blockedNanos;

        Throttle(double bytesPerSec, double burstBytes, long maxBlockedMs, Runnable onFlood) {
            this.rate = bytesPerSec;
            this.burst = burstBytes;
            this.tokens = burstBytes;
            this.maxBlockedNanos = maxBlockedMs * 1_000_000L;
            this.onFlood = onFlood;
        }

        void take(int n) throws IOException {
            long wait;
            synchronized (this) {
                long now = System.nanoTime();
                tokens = Math.min(burst, tokens + (now - last) / 1e9 * rate);
                last = now;
                if (now - windowStart > 60_000_000_000L) { // 1분 창
                    windowStart = now;
                    blockedNanos = 0;
                }
                tokens -= n;
                if (tokens >= 0) return;
                wait = (long) (-tokens / rate * 1e9);
                blockedNanos += wait;
                if (blockedNanos > maxBlockedNanos) {
                    if (onFlood != null) onFlood.run();
                    throw new IOException("flood");
                }
            }
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }
}
