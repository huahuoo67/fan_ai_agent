package com.fan.fanaiagent.platform;

import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

public final class ContextPolicy {
    private ContextPolicy() {}
    public static String scope(String message,Capabilities cap) {
        // Conservative: mixed/ambiguous relationship text remains LOVE even with the switch off.
        return cap.love() || message.matches("(?s).*(恋爱|伴侣|对象|男朋友|女朋友|分手|婚姻|感情|约会|前任|夫妻|暗恋|表白|老公|老婆|丈夫|妻子|亲密|出轨|背叛|吵架|争吵|失恋|私密|隐私|秘密|性生活|性取向|喜欢.{0,4}[他她]|[他她].{0,6}爱我|我爱[他她]|我(妈妈|母亲|爸爸|父亲|朋友|同事|孩子|女儿|儿子)).*" ) ? "LOVE":"GENERAL";
    }
    public static List<Map<String,Object>> trimCompleteTurns(List<Map<String,Object>> rows,int budget) {
        return trimCompleteTurns(rows,budget,Integer.MAX_VALUE);
    }
    // UTF-8 byte count is a deliberately conservative token upper-bound, not a provider tokenizer.
    public static int tokens(String text) {return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    public static String clip(String text,int budget) {
        int end=0,used=0;
        while(end<text.length()) {int point=text.codePointAt(end),size=tokens(new String(Character.toChars(point)));if(used+size>budget) break;used+=size;end+=Character.charCount(point);}
        return text.substring(0,end);
    }
    public static List<Map<String,Object>> trimCompleteTurns(List<Map<String,Object>> rows,int budget,int tokenBudget) {
        LinkedHashMap<Object,List<Map<String,Object>>> turns=new LinkedHashMap<>();
        rows.forEach(r->turns.computeIfAbsent(r.get("run_id"),k->new ArrayList<>()).add(r));
        var groups=new ArrayList<>(turns.values());List<Map<String,Object>> result=new ArrayList<>();int used=0,tokenUsed=0;
        for(int i=groups.size()-1;i>=0;i--) {
            var g=groups.get(i);
            if(g.stream().noneMatch(r->"USER".equals(r.get("role"))) || g.stream().noneMatch(r->"ASSISTANT".equals(r.get("role"))) && g.stream().noneMatch(r->Boolean.TRUE.equals(r.get("granted")))) continue;
            int size=g.stream().mapToInt(r->r.get("content").toString().length()).sum();
            int tokens=g.stream().mapToInt(r->tokens(r.get("content").toString())).sum();
            if(used+size>budget || tokenUsed+tokens>tokenBudget) break;result.addAll(0,g);used+=size;tokenUsed+=tokens;
        }
        return result;
    }
}
