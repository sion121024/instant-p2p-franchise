package kfc.udp.client.signaling;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;

import java.util.Locale;
import java.util.Set;

/**
 * {@code /execute as <방장> run op 나} 같은 "남의 이름으로 권한 명령 실행" 막기(1.5~, ChatGuardCommandMixin).
 * <p>
 * 브리가디어는 {@code execute as/at} 으로 실행 주체를 바꿀 수 있다 — 명령 블록이나 OP 를 받은 사람이 방장을 주체로
 * 바꿔 {@code op}·{@code ban}·{@code admit} 등을 치면, 방장 전용 검사(requireHost)가 바뀐 주체(방장)를 보고 통과됐다.
 * 그래서 실제로 실행될 마지막 명령과 그 주체를 꺼내, 권한 명령인데 주체가 처음 친 사람과 다르면 막는다.
 */
public final class CommandForge {

    private static final Set<String> PRIVILEGED = Set.of(
            "op", "deop", "admit", "deny", "unadmit", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist");

    private CommandForge() {}

    /** {@code execute ... run} 뒤에 실제로 실행될 명령 문자열(없으면 그대로). */
    public static String effective(ParseResults<?> pr, String command) {
        CommandContextBuilder<?> last = pr.getContext().getLastChild();
        if (last == pr.getContext() || last.getNodes().isEmpty()) return command;
        int start = last.getNodes().get(0).getRange().getStart();
        String full = pr.getReader().getString();
        return start >= 0 && start < full.length() ? full.substring(start) : command;
    }

    /** 마지막 명령의 실행 주체(CommandSourceStack / ServerCommandSource). */
    public static Object lastSource(ParseResults<?> pr) {
        return pr.getContext().getLastChild().getSource();
    }

    public static boolean isPrivileged(String command) {
        String c = command.strip();
        if (c.startsWith("/")) c = c.substring(1);
        int sp = c.indexOf(' ');
        String head = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (head.startsWith("minecraft:")) head = head.substring("minecraft:".length());
        return PRIVILEGED.contains(head);
    }
}
