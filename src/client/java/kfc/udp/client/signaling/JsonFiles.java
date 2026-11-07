package kfc.udp.client.signaling;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 설정 폴더의 JSON 목록 파일(밴·화이트리스트·OP·승인 명단 등) 읽기/쓰기 공용(1.5~).
 * <p>
 * <b>쓰기는 임시 파일에 쓰고 바꿔치기한다</b> — 예전엔 원본 파일을 바로 열어 썼기 때문에, 쓰는 도중 게임이 꺼지면
 * 반쯤 쓴 파일이 남고 다음 실행 때 통째로 못 읽어 밴 목록이 사라졌다. <b>읽다 깨진 파일은 {@code .corrupt} 로 옆에
 * 치워 둔다</b> — 그대로 두면 다음 저장이 빈 목록으로 덮어써 복구할 길이 없어진다.
 */
final class JsonFiles {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p");

    private JsonFiles() {}

    /** 파일이 없거나 깨졌으면 null(깨진 파일은 .corrupt 로 옮긴다). */
    static JsonArray readArray(Path path) {
        if (!Files.exists(path)) return null;
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (Exception e) {
            LOG.warn("[instant-p2p] {} is unreadable ({}); moved aside as .corrupt", path.getFileName(), e.getMessage());
            try {
                Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // 못 옮겨도 읽기 실패로 끝낸다
            }
            return null;
        }
    }

    static void writeText(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[instant-p2p] save failed for {}: {}", path.getFileName(), e.getMessage());
        }
    }

    static void writeAtomic(Path path, JsonArray arr) {
        writeText(path, new GsonBuilder().setPrettyPrinting().create().toJson(arr));
    }
}
