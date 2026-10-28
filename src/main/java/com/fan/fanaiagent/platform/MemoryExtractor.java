package com.fan.fanaiagent.platform;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Conservative, deterministic extraction. Unrecognized prose is not silently profiled. */
public final class MemoryExtractor {
    public record Draft(String subject,String key,String value,String kind,String scope,boolean sensitive,OffsetDateTime expiresAt) {}
    private MemoryExtractor() {}
    public static List<Draft> extract(String text,String messageScope) {
        if(text.matches("(?is).*(密码|验证码|令牌|token|secret|api.?key|身份证|银行卡).*")) return List.of();
        if(text.matches("(?is).*(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._-]{12,}|eyJ[a-z0-9_-]+\\.[a-z0-9_-]+\\.[a-z0-9_-]+).*")) return List.of();
        if(text.matches("(?s).*(假如|假设|比如|例如|帮我写|写一句|写一段|引用|“|”|\").*") && !text.startsWith("记住：")) return List.of();
        List<Draft> facts=new ArrayList<>();
        for(String raw:text.split("[。；;！!？?\\n]")) {
            String s=raw.strip();if(s.isEmpty()) continue;
            String subject=subject(s);
            String dietScope="SELF".equals(subject)?"COMMON":"LOVE";
            if(s.matches(".*(你说|你刚才说|你提到).*(我没说|我没有说|没说过|并没有).*")) {
                if(s.contains("辣")) facts.add(new Draft(subject,"diet.spicy","纠正：先前的吃辣描述并非我的陈述","RETRACT",dietScope,false,null));
                if(s.contains("香菜")) facts.add(new Draft(subject,"diet.coriander","纠正：先前的香菜描述并非我的陈述","RETRACT",dietScope,false,null));
                continue;
            }
            if(s.matches(".*(并不是|不是我|没说过|从未说|不代表|可能|也许).*")) continue;
            if("SELF".equals(subject) && !s.matches(".*(我|记住|最近|这顿|这次|今天).*")) continue;
            boolean health=s.matches(".*(感冒|生病|过敏|医生|胃疼|忌口).*" );
            String scope=!"SELF".equals(subject)?"LOVE":messageScope;
            String kind=s.matches(".*(这顿|这次|今晚|今天这).*" )?"TASK":s.matches(".*(最近|暂时|这段时间|感冒|生病|过敏|忌口|未来\\d+天).*" )?"TEMPORARY":"PREFERENCE";
            OffsetDateTime expiry=null;
            Matcher days=Pattern.compile("(?:未来|接下来|持续)([1-9][0-9]{0,2})天").matcher(s);
            if(days.find()) expiry=OffsetDateTime.now(ZoneOffset.UTC).plusDays(Integer.parseInt(days.group(1)));
            if(s.contains("辣") && s.matches(".*(喜欢|爱吃|无辣不欢|不吃|不要|忌|不喜欢|避免|恢复|解除|好了|康复).*")) {
                boolean no=s.matches(".*(不喜欢.{0,3}辣|不爱.{0,3}辣|不吃辣|不吃.{0,2}辣|不要辣|忌辣|避免.{0,3}辣).*" );
                // An explicit recovery statement retracts the temporary layer, not the stable preference.
                if(s.matches(".*(好了|康复|解除|恢复).*(吃辣|忌口|限制).*")) kind="RETRACT";
                facts.add(new Draft(subject,"diet.spicy","RETRACT".equals(kind)?"用户明确解除近期吃辣限制":no?"不吃辣":"喜欢吃辣",kind,dietScope,health,expiry));
            } else if(s.contains("香菜") && s.matches(".*(喜欢|不喜欢|爱吃|不吃|讨厌).*")) {
                facts.add(new Draft(subject,"diet.coriander",s.matches(".*(不喜欢|不吃|不爱|讨厌).*" )?"不吃香菜":"喜欢香菜",kind,dietScope,false,expiry));
            } else if(s.matches(".*(叫我|称呼我为|我叫)[^，,]{1,30}")) {
                String value=s.replaceFirst("^.*?(叫我|称呼我为|我叫)","").strip();
                facts.add(new Draft("SELF","profile.name",value,"PREFERENCE","COMMON",false,null));
            } else if(s.matches(".*(回答|回复).*(简短|简洁|详细|中文|英文).*")) {
                facts.add(new Draft("SELF","response.style",s,"PREFERENCE","COMMON",false,null));
            } else if(s.matches(".*我(现在|已经|目前)?(单身|已婚|结婚了|有对象|有男朋友|有女朋友).*")) {
                facts.add(new Draft("SELF","relationship.status",s,"PREFERENCE","LOVE",true,null));
            } else if(s.startsWith("记住：") || s.startsWith("记住:")) {
                String value=s.substring(3).strip();
                if(!value.isBlank() && value.length()<=500) facts.add(new Draft(subject,"explicit."+PlatformStore.hash(value).substring(0,16),value,kind,scope,health,expiry));
            }
        }
        return facts.stream().limit(8).toList();
    }
    private static String subject(String text) {
        if(text.matches(".*(我对象|我女朋友|我男朋友|我伴侣|我老婆|我老公).*")) return "PARTNER";
        Matcher relation=Pattern.compile("我(妈妈|母亲|爸爸|父亲|朋友|同事|孩子|女儿|儿子)").matcher(text);
        return relation.find()?"THIRD:"+relation.group(1):"SELF";
    }
}
