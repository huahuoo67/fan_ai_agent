package com.fan.fanaiagent.platform;

import java.util.*;

public final class EgressPolicy {
    private EgressPolicy() {}
    /** Only public topic tokens leave automatically; free-form text requires explicit review. */
    public static String publicQuery(String input) {
        var words=List.of("北京","上海","广州","深圳","杭州","成都","重庆","西安","武汉","南京","苏州","天津","长沙",
            "天气","气温","降雨","今天","明天","周末","约会","餐厅","清淡","不辣","火锅","咖啡","公园","电影","展览","景点","新闻","交通","高铁");
        // Sensitive narratives still use a minimal public-topic projection. Ordinary
        // public questions are not restricted to a fixed dictionary of place names.
        if("LOVE".equals(ContextPolicy.scope(input,PlatformTypes.Capabilities.NONE)) || input.matches("(?is).*(私人|隐私|秘密|密码|验证码|令牌|token|api.?key|secret|身份证|银行卡|[\\w.+-]+@[\\w.-]+|1[3-9]\\d{9}|我叫|我(的)?(男朋友|女朋友|伴侣|同事|朋友|家人)).*"))
            return String.join(" ",words.stream().filter(input::contains).toList());
        String query=input.replaceAll("(?i)pdf|请帮我|帮我|生成|制作|文档|文件|联网搜索|搜索一下|查一下", " ").replaceAll("\\s+", " ").strip();
        return ContextPolicy.clip(query,200);
    }
    public static boolean validReviewedQuery(String input) {
        return input!=null && !input.isBlank() && input.length()<=200 && !input.matches("(?is).*(密码|验证码|令牌|token|api.?key|secret|身份证|银行卡|[\\w.+-]+@[\\w.-]+|1[3-9]\\d{9}).*");
    }
}
