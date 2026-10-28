package com.fan.fanaiagent.platform;

import java.time.*;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

public final class MemoryResolver {
    private MemoryResolver() {}
    public static EffectiveMemory resolve(List<MemoryFact> input,OffsetDateTime now) {
        Map<String,List<MemoryFact>> groups=new LinkedHashMap<>();
        input.forEach(f->groups.computeIfAbsent(f.subject()+"|"+f.key(),k->new ArrayList<>()).add(f));
        List<MemoryFact> selected=new ArrayList<>();List<String> notices=new ArrayList<>();
        for(var group:groups.values()) {
            group.sort(Comparator.comparing(MemoryFact::createdAt).thenComparing(MemoryFact::id));
            MemoryFact retraction=group.stream().filter(f->"RETRACT".equals(f.kind())).reduce((a,b)->b).orElse(null);
            if(retraction!=null) {
                boolean recovery=retraction.value().matches(".*(恢复|解除|康复|好了).*" );
                group.removeIf(f->!f.createdAt().isAfter(retraction.createdAt()) && (!recovery || "TEMPORARY".equals(f.kind()) || "RETRACT".equals(f.kind())));
            }
            group.removeIf(f->"RETRACT".equals(f.kind()) || f.expiresAt()!=null && f.expiresAt().isBefore(now) && !f.sensitive());
            if(group.isEmpty()) continue;
            var restrictions=group.stream().filter(f->"TEMPORARY".equals(f.kind()) && isRestriction(f)).toList();
            MemoryFact winner;
            if(!restrictions.isEmpty()) winner=restrictions.getLast();
            else winner=group.stream().max(Comparator.comparingInt((MemoryFact f)->switch(f.kind()){case "TASK"->3;case "TEMPORARY"->2;default->1;})
                    .thenComparing(MemoryFact::createdAt)).orElseThrow();
            String layer=winner.kind();var time=winner.createdAt();
            var peers=group.stream().filter(f->f.kind().equals(layer) && f.createdAt().equals(time)).toList();
            if(peers.stream().map(MemoryFact::value).distinct().count()>1) {
                var prudent=peers.stream().filter(MemoryResolver::isRestriction).findFirst();
                if(prudent.isEmpty()) {notices.add(winner.subject()+"的同类信息仍有冲突，请确认本次采用哪一项；本轮先不假定该偏好。");continue;}
                winner=prudent.get();notices.add(winner.subject()+"的同等可信信息有冲突；本轮先按限制处理，请确认该限制是否仍适用。");
            }
            selected.add(winner);
            if("TEMPORARY".equals(winner.kind()) && winner.sensitive() && (winner.expiresAt()==null || winner.expiresAt().isBefore(now)))
                notices.add(winner.subject()+"的临时限制仍需复核，不推定已解除："+winner.value());
            String w=winner.value();
            if(group.stream().anyMatch(f->!f.value().equals(w))) notices.add("本轮采用「"+w+"」；较一般的偏好暂不用于本次推荐，必要时确认限制是否解除。");
        }
        return new EffectiveMemory(List.copyOf(selected),List.copyOf(notices));
    }
    private static boolean isRestriction(MemoryFact f) {return f.value().matches(".*(不|避免|忌|限制).*" );}
}
