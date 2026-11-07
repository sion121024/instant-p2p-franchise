package kfc.udp.client.signaling;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 모드 전체가 같이 쓰는 HTTP 클라이언트 하나.
 * <p>
 * HttpClient는 하나 만들 때마다 전용 selector 스레드와 연결 풀을 따로 잡는다. 예전엔 기능마다(버전 확인·역할·
 * 인증·신고·서버 상태·밴 상태·치지직·방 목록) 따로 만들어서, 게임을 켜 두기만 해도 스레드가 여러 개 떠 있었고
 * 방 목록은 요청할 때마다 새로 만들었다. 요청별 시간 제한은 각 요청(HttpRequest.timeout)에 따로 건다.
 */
public final class Http {

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Http() {}
}
