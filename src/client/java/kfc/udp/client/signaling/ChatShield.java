package kfc.udp.client.signaling;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 공개 방 채팅 안전 필터(1.5~) — 아동 대상 성적 접근·마약/불법촬영물 거래·"무료 니트로" 류 사기 링크를 <b>단어 조합</b>으로
 * 잡아 그 메시지를 다른 사람에게 전달하지 않는다(ChatGuard). 단어 하나로는 막지 않는다 — 예를 들어 "미성년"만으로는 안
 * 걸리고, 미성년 + 성적 단어 + 권유 표현이 다 있어야 걸린다. 금지·신고·뉴스 같은 말이 섞이면 이야기하는 것으로 보고
 * 통과시킨다(EXCLUDE).
 * <p>
 * 비교는 소문자로 바꾸고 글자·숫자만 남긴 뒤 한다(띄어쓰기·특수문자로 피해 가지 못하게).
 */
public final class ChatShield {

    public static final String CHILD = "child";
    public static final String ILLEGAL = "illegal";

    private static final String[] MINOR = n("초딩", "중딩", "미성년", "초등학생", "중학생");
    private static final String[] EXPLICIT = n("섹스", "야동", "몸캠", "성관계", "원나잇", "조건만남");
    private static final String[] SOLICIT = n("할래", "하실분", "하자", "해줄", "보여줘", "보여줄", "보내줘", "보내줄", "해볼",
            "만나", "만날", "하고싶", "해도되", "해도돼", "할사람", "구함", "구해");
    private static final String[] DRUGS = n("필로폰", "히로뽕", "엑스터시", "케타민", "펜타닐", "메스암페타민", "엘에스디", "합성대마", "대마초");
    private static final String[] TRADE = n("팝니다", "판매", "구합니다", "삽니다", "던지기", "직거래", "입금", "텔레그램", "오픈채팅");
    private static final String[] CSAM = n("아청물", "아동음란물", "아동포르노", "불법촬영물");
    private static final String[] SHARE = n("팝니다", "판매", "공유", "링크", "구합니다", "삽니다", "입금", "텔레그램", "오픈채팅");
    private static final String[] FREE = n("무료", "공짜", "free");
    private static final String[] PRIZE = n("니트로", "nitro", "로벅스", "robux");
    private static final String[] LINK = n("http", "www", "discordgift", "bitly", "tinyurl");
    private static final String[] EXCLUDE = n("금지", "하지마", "하지말", "안돼", "안됩니다", "신고", "경찰", "뉴스", "드라마", "영화",
            "교육", "조심", "사기", "피싱", "위험", "나쁜", "범죄", "처벌", "잡혔", "잡혀", "의심");
    /** "13살", "15세" 같은 미성년 나이(0~18). */
    private static final Pattern MINOR_AGE = Pattern.compile("(^|[^0-9])(?:[0-9]|1[0-8])(?:살|세)");

    private ChatShield() {}

    /** 걸리면 {@link #CHILD} / {@link #ILLEGAL}, 아니면 null. */
    public static String check(String text) {
        if (text == null) return null;
        String t = norm(text);
        if (t.isEmpty() || containsAny(t, EXCLUDE)) return null;
        if ((containsAny(t, MINOR) || minorAge(t)) && containsAny(t, EXPLICIT) && containsAny(t, SOLICIT)) return CHILD;
        if (containsAny(t, DRUGS) && containsAny(t, TRADE)) return ILLEGAL;
        if (containsAny(t, CSAM) && containsAny(t, SHARE)) return ILLEGAL;
        if (containsAny(t, FREE) && containsAny(t, PRIZE) && containsAny(t, LINK)) return ILLEGAL;
        return null;
    }

    /** "세트·세계·세금" 같은 단어의 "세"를 나이로 잘못 읽지 않게 먼저 지운다. */
    private static boolean minorAge(String t) {
        String s = t.replace("세트", "").replace("세계", "").replace("세기", "").replace("세대", "")
                .replace("세금", "").replace("세상", "").replace("세력", "").replace("세탁", "");
        return MINOR_AGE.matcher(s).find();
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetter(cp) || Character.isDigit(cp)) b.appendCodePoint(cp);
        });
        return b.toString();
    }

    private static String[] n(String... words) {
        String[] out = new String[words.length];
        for (int i = 0; i < words.length; i++) out[i] = norm(words[i]);
        return out;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }
}
