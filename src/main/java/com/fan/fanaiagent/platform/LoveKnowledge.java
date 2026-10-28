package com.fan.fanaiagent.platform;

import org.springframework.ai.vectorstore.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.*;

@Service
public class LoveKnowledge {
    private final VectorStore vectors;
    public LoveKnowledge(@Qualifier("pgVectorVectorStore") VectorStore vectors) {this.vectors=vectors;}
    public String retrieve(String message) {
        if(!message.matches("(?s).*(恋爱|约会|情话|伴侣|对象|分手|表白|夫妻|婚姻|感情).*")) return "";
        boolean example=message.matches("(?s).*(情话|文案|语录|写一句).*" );
        var docs=vectors.similaritySearch(SearchRequest.builder().query(message).topK(3).similarityThreshold(.5)
            .filterExpression("content_type == '"+(example?"example":"knowledge")+"'").build());
        StringBuilder result=new StringBuilder();
        if(docs!=null) for(var doc:docs) {
            // Lexical cross-check avoids treating nearest-but-unrelated vectors as relevant evidence.
            String text=doc.getText();
            if(text==null || !relevant(message,text)) continue;
            result.append("\n[公共参考，类型=").append(doc.getMetadata().get("content_type")).append("，来源=")
                .append(doc.getMetadata().get("filename")).append("]\n").append(text,0,Math.min(4000,text.length()));
        }
        return result.toString();
    }
    static boolean relevant(String query,String text) {
        for(String topic:List.of("约会","情话","分手","表白","结婚","婚姻","恋爱","沟通","吵架","单身"))
            if(query.contains(topic) && text.contains(topic)) return true;
        return false;
    }
}
