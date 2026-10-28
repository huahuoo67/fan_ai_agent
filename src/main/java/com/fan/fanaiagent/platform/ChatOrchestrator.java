package com.fan.fanaiagent.platform;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@Service
public class ChatOrchestrator {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ChatOrchestrator.class);
    private final PlatformStore store;private final MemoryService memories;private final PlatformModel model;private final RateLimiter rates;
    private final JdbcTemplate db;
    private final ExecutorService workers=new ThreadPoolExecutor(4,8,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    @Value("${platform.requests-per-minute:10}") private int frequency;
    @Value("${platform.timeout-seconds:180}") private int timeout;
    public ChatOrchestrator(PlatformStore store,MemoryService memories,PlatformModel model,RateLimiter rates,JdbcTemplate db) {
        this.store=store;this.memories=memories;this.model=model;this.rates=rates;this.db=db;
    }
    public SseEmitter start(long user,UUID conversation,TurnRequest body,String session) {
        var started=store.begin(user,conversation,body,session);var ctx=started.context();
        var stream=new SseEmitter((timeout+5)*1000L);var seq=new AtomicLong();var disconnected=new AtomicBoolean();
        var partial=new AtomicBoolean();
        BiConsumer<String,Object> emit=(type,data)-> {
            if(data instanceof Map<?,?> payload && ("tool".equals(type) && "failed".equals(payload.get("status")) || Boolean.TRUE.equals(payload.get("partial")))) partial.set(true);
            long sequence=seq.incrementAndGet();
            store.event(ctx.runId(),sequence,type,data);
            if(disconnected.get()) return;
            try {stream.send(SseEmitter.event().id(Long.toString(sequence)).name(type)
                .data(Map.of("runId",ctx.runId(),"sequence",sequence,"data",data)));}
            catch(Exception e) {disconnected.set(true);if(!started.duplicate()) store.stop(user,ctx.runId());}
        };
        if(started.duplicate()) {
            emit.accept("start",Map.of("duplicate",true));emit.accept("done",store.run(user,ctx.runId()));stream.complete();return stream;
        }
        try {rates.check("chat:"+user,frequency);}
        catch(RuntimeException e) {store.finish(ctx,"","FAILED");throw e;}
        store.startAnswer(ctx);
        emit.accept("start",Map.of("capabilities",ctx.capabilities()));
        stream.onCompletion(()->disconnected.set(true));
        stream.onTimeout(()->{disconnected.set(true);store.stop(user,ctx.runId());});
        stream.onError(e->{disconnected.set(true);store.stop(user,ctx.runId());});
        try {
            workers.submit(()-> {
                StringBuilder answer=new StringBuilder();String status="COMPLETED";long[] persisted={0};
                Consumer<String> delta=chunk->{
                    if(chunk==null) return;
                    if(!store.active(ctx)) throw new IllegalStateException("任务已停止");
                    answer.append(chunk);emit.accept("answer_delta",Map.of("text",chunk));
                    if(System.nanoTime()-persisted[0]>500_000_000L) {store.checkpoint(ctx,answer.toString());persisted[0]=System.nanoTime();}
                };
                Thread thread=Thread.currentThread();
                var exceeded=new AtomicBoolean();
                var alarm=timer.schedule(()->{exceeded.set(true);store.stop(user,ctx.runId());thread.interrupt();},timeout,TimeUnit.SECONDS);
                try {
                    var history=store.history(ctx);
                    // A context-dependent request never silently reads isolated relationship history.
                    if(!ctx.capabilities().love() && (body.contextGrants()==null || body.contextGrants().isEmpty()) &&
                        body.message().matches("(?s).*(刚才|之前|上面|前面|上述).*(情况|内容|安排|计划|事情).*" ) && !store.transferable(user,conversation).isEmpty()) {
                        emit.accept("context_required",Map.of("messages",store.transferable(user,conversation)));
                        delta.accept("之前有恋爱作用域的内容。请在确认卡中选择本轮要带入的用户消息，再发送；其余敏感历史仍保持隔离。");
                    } else {
                        List<MemoryFact> candidates=List.of();EffectiveMemory effective;
                        try {candidates=memories.extract(ctx,started.messageId(),body.message());var snapshot=store.snapshot(ctx);effective=new EffectiveMemory(snapshot.facts(),snapshot.notices());}
                        catch(Exception e) {effective=new EffectiveMemory(List.of(),List.of("长期记忆服务暂未完成，本轮仅依据当前陈述。"));emit.accept("notice",Map.of("message","记忆未保存，请稍后重试。"));}
                        // Current explicit constraints apply even when memory extraction is disabled or its storage failed.
                        var current=new ArrayList<>(effective.facts());
                        var drafts=MemoryExtractor.extract(body.message(),store.messageScope(ctx));
                        for(var d:drafts)
                            current.add(new MemoryFact(UUID.randomUUID(),d.subject(),d.key(),d.value(),d.kind(),d.scope(),d.sensitive(),d.expiresAt(),"PENDING",1,started.messageId(),conversation,OffsetDateTime.now(ZoneOffset.UTC)));
                        effective=MemoryResolver.resolve(current,OffsetDateTime.now(ZoneOffset.UTC));
                        try {history=memories.filterHistory(ctx,history,drafts);}
                        catch(Exception e) {history=List.of();emit.accept("notice",Map.of("message","历史事实校验暂未完成，本轮仅使用当前陈述。"));}
                        if(!effective.notices().isEmpty()) emit.accept("notice",Map.of("message",String.join("\n",effective.notices())));
                        model.answer(ctx,body,history,effective,delta,emit);
                        for(var candidate:candidates) if(!"TASK".equals(candidate.kind())) emit.accept("memory_candidate",candidate);
                    }
                    if(!store.active(ctx)) status=exceeded.get()?"TIMED_OUT":"STOPPED";
                } catch(Exception e) {
                    boolean stopped=Thread.currentThread().isInterrupted() || !store.active(ctx);
                    boolean timedOut=exceeded.get() || e.getCause() instanceof TimeoutException;
                    status=timedOut?"TIMED_OUT":stopped?"STOPPED":"FAILED";
                    var failure=AiFailure.describe(e);
                    log.warn("Chat run {} ended: status={}, code={}, exception={}",ctx.runId(),status,failure.code(),e.getClass().getSimpleName());
                    emit.accept("error",Map.of("message",timedOut?"本轮已超时，部分文本与已产生的成果保留。":stopped?"任务已停止，已产生的成果保留。":failure.message(),
                        "code",timedOut?"TIMED_OUT":stopped?"STOPPED":failure.code(),"retryable",!stopped && (timedOut || failure.retryable())));
                } finally {
                    alarm.cancel(false);Thread.interrupted();
                    if("COMPLETED".equals(status) && partial.get()) status="PARTIAL";
                    try {store.finish(ctx,answer.toString(),status);emit.accept("done",Map.of("status",status));}
                    finally {stream.complete();}
                }
            });
        } catch(RejectedExecutionException e) {
            store.finish(ctx,"","FAILED");emit.accept("error",Map.of("message","服务任务较多，请稍后重试。"));emit.accept("done",Map.of("status","FAILED"));stream.complete();
        }
        return stream;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // Single backend deployment. A restart never automatically replays side effects.
        db.update("UPDATE platform_message SET status='INTERRUPTED',context_valid=FALSE WHERE status='STREAMING'");
        db.update("UPDATE platform_run SET status='INTERRUPTED',updated_at=CURRENT_TIMESTAMP WHERE status='RUNNING'");
    }
    @PreDestroy public void close() {workers.shutdownNow();timer.shutdownNow();}
}
