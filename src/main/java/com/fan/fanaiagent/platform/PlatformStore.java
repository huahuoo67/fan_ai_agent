package com.fan.fanaiagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@Service
public class PlatformStore {
    private final JdbcTemplate db;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final MemoryService memories;
    @Value("${platform.max-concurrent:2}") private int concurrency;
    @Value("${platform.context-characters:24000}") private int contextCharacters;
    @Value("${platform.history-token-budget:12000}") private int historyTokens;
    @Value("${platform.timeout-seconds:180}") private int timeout;
    public PlatformStore(JdbcTemplate db,com.fasterxml.jackson.databind.ObjectMapper json,MemoryService memories) {this.db=db;this.json=json;this.memories=memories;}
    public record Started(ExecutionContext context, long messageId, boolean duplicate) {}
    private static ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.NOT_FOUND,"资源不存在");}
    public Map<String,Object> owned(long user,UUID id) {
        var rows=db.queryForList("SELECT id,title,last_love AS love,last_task AS task,last_web AS web,updated_at FROM platform_conversation WHERE id=? AND user_id=?",id,user);
        if(rows.isEmpty()) throw missing();return rows.getFirst();
    }
    public List<Map<String,Object>> conversations(long user) {
        return db.queryForList("SELECT id,title,last_love AS love,last_task AS task,last_web AS web,updated_at FROM platform_conversation WHERE user_id=? ORDER BY updated_at DESC LIMIT 200",user);
    }
    public Map<String,Object> create(long user) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO platform_conversation(id,user_id) VALUES(?,?)",id,user);return owned(user,id);
    }
    public List<Map<String,Object>> messages(long user,UUID id) {
        owned(user,id);
        var result=db.queryForList("SELECT id::text AS id,run_id,role,content,scope,love,task,web,status,created_at FROM platform_message WHERE conversation_id=? ORDER BY id",id);
        var events=db.queryForList("SELECT e.run_id,e.type,e.payload::text AS payload FROM platform_run_event e JOIN platform_run r ON r.id=e.run_id WHERE r.conversation_id=? AND r.user_id=? ORDER BY e.sequence",id,user);
        for(var message:result) if("ASSISTANT".equals(message.get("role"))) {
            var items=new ArrayList<Map<String,Object>>();
            for(var e:events) if(e.get("run_id").equals(message.get("run_id"))) try {
                items.add(Map.of("type",e.get("type"),"data",json.readValue(e.get("payload").toString(),Object.class)));
            } catch(Exception ignored) { }
            message.put("events",items);
        }
        return result;
    }
    public void event(UUID run,long sequence,String type,Object payload) {
        if(!Set.of("tool","sources","artifact","memory_candidate","notice","error","context_required").contains(type)) return;
        try {db.update("INSERT INTO platform_run_event(run_id,sequence,type,payload) VALUES(?,?,?,?::jsonb)",run,sequence,type,json.writeValueAsString(payload));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalArgumentException("事件格式不正确");}
    }
    public Map<String,Object> run(long user,UUID id) {
        var rows=db.queryForList("SELECT id,conversation_id,status,cancel_requested,created_at,updated_at FROM platform_run WHERE id=? AND user_id=?",id,user);
        if(rows.isEmpty()) throw missing();return rows.getFirst();
    }
    @Transactional
    public Started begin(long user,UUID conversation,TurnRequest body,String session) {
        owned(user,conversation);
        db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,user);
        String fingerprint=hash(conversation+"|"+body.message()+"|"+body.capabilities()+"|"+body.contextGrants()+"|"+body.reviewedSearchQuery()+"|"+body.taskInput());
        var old=db.queryForList("SELECT id,request_hash FROM platform_run WHERE user_id=? AND request_id=?",user,body.requestId());
        if(!old.isEmpty()) {
            if(!fingerprint.equals(old.getFirst().get("request_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"请求标识已用于不同内容");
            return new Started(new ExecutionContext(user,conversation,(UUID)old.getFirst().get("id"),body.capabilities()),0,true);
        }
        int active=db.queryForObject("SELECT COUNT(*) FROM platform_run WHERE user_id=? AND status='RUNNING'",Integer.class,user);
        if(active>=concurrency) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已有任务正在执行，请稍后再试");
        int same=db.queryForObject("SELECT COUNT(*) FROM platform_run WHERE conversation_id=? AND status='RUNNING'",Integer.class,conversation);
        if(same>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"当前会话正在执行");
        UUID run=UUID.randomUUID();var cap=body.capabilities();
        db.update("INSERT INTO platform_run(id,user_id,conversation_id,request_id,request_hash,session_id,love,task,web) VALUES(?,?,?,?,?,?,?,?,?)",
            run,user,conversation,body.requestId(),fingerprint,session,cap.love(),cap.task(),cap.web());
        if(body.contextGrants()!=null) for(UUID grant:body.contextGrants()) {
            int count=db.update("UPDATE platform_context_grant SET used=TRUE,target_run_id=? WHERE id=? AND user_id=? AND conversation_id=? AND used=FALSE AND created_at>CURRENT_TIMESTAMP-INTERVAL '10 minutes'",
                run,grant,user,conversation);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"上下文确认已过期，请重新选择");
        }
        // One-turn grants do not declassify the resulting answer for later general turns.
        String scope=body.contextGrants()!=null && !body.contextGrants().isEmpty()?"LOVE":ContextPolicy.scope(body.message(),cap);
        long message=db.queryForObject("INSERT INTO platform_message(conversation_id,run_id,role,content,scope,love,task,web) VALUES(?,?,'USER',?,?,?,?,?) RETURNING id",
            Long.class,conversation,run,body.message(),scope,cap.love(),cap.task(),cap.web());
        db.update("UPDATE platform_conversation SET title=CASE WHEN title='新对话' THEN ? ELSE title END,last_love=?,last_task=?,last_web=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            body.message().strip().substring(0,Math.min(24,body.message().strip().length())),cap.love(),cap.task(),cap.web(),conversation);
        var ctx=new ExecutionContext(user,conversation,run,cap);
        MemorySnapshot snapshot;
        db.execute("SAVEPOINT memory_snapshot");
        try {snapshot=new MemorySnapshot(memories.eligible(ctx),List.of());}
        catch(Exception e) {db.execute("ROLLBACK TO SAVEPOINT memory_snapshot");snapshot=new MemorySnapshot(List.of(),List.of("记忆快照暂未获取，本轮仅依据当前陈述。"));}
        db.execute("RELEASE SAVEPOINT memory_snapshot");
        try {db.update("UPDATE platform_run SET memory_snapshot=?::jsonb WHERE id=?",json.writeValueAsString(snapshot),run);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalArgumentException("记忆快照格式不正确");}
        return new Started(ctx,message,false);
    }
    public MemorySnapshot snapshot(ExecutionContext ctx) {
        String value=db.queryForObject("SELECT memory_snapshot::text FROM platform_run WHERE id=? AND user_id=?",String.class,ctx.runId(),ctx.userId());
        try {return json.readValue(value,MemorySnapshot.class);}
        catch(Exception e) {return new MemorySnapshot(List.of(),List.of("记忆快照暂未获取，本轮仅依据当前陈述。"));}
    }
    public String messageScope(ExecutionContext ctx) {return db.queryForObject("SELECT scope FROM platform_message WHERE run_id=? AND role='USER'",String.class,ctx.runId());}
    public void restrict(ExecutionContext ctx) {db.update("UPDATE platform_message SET scope='LOVE' WHERE run_id=? AND conversation_id=?",ctx.runId(),ctx.conversationId());}
    public List<Map<String,Object>> history(ExecutionContext ctx) {
        // A selected LOVE message grants that user's text, never unrelated assistant guesses.
        var rows=db.queryForList("""
            SELECT m.id,m.run_id,m.role,m.content,m.scope FROM platform_message m
            WHERE m.conversation_id=? AND m.status='COMPLETED' AND m.context_valid=TRUE AND m.run_id<>?
              AND m.role IN ('USER','ASSISTANT')
              AND NOT EXISTS (SELECT 1 FROM platform_memory f WHERE f.source_message_id=m.id AND f.status IN ('REVOKED','SUPERSEDED'))
              AND (m.scope<>'LOVE' OR ? OR (m.role='USER' AND EXISTS
                (SELECT 1 FROM platform_context_grant g WHERE g.message_id=m.id AND g.target_run_id=? AND g.user_id=?)))
            ORDER BY m.id DESC LIMIT 30
            """,ctx.conversationId(),ctx.runId(),ctx.capabilities().love(),ctx.runId(),ctx.userId());
        Collections.reverse(rows);
        var result=new ArrayList<>(ContextPolicy.trimCompleteTurns(rows,contextCharacters,historyTokens));
        var granted=db.queryForList("SELECT m.id,m.run_id,m.role,m.content,m.scope FROM platform_message m JOIN platform_context_grant g ON g.message_id=m.id WHERE g.user_id=? AND g.target_run_id=? AND m.context_valid=TRUE AND m.role='USER' ORDER BY m.id",
            ctx.userId(),ctx.runId());
        int grantedCharacters=0;
        for(var message:granted) if(result.stream().noneMatch(r->r.get("id").equals(message.get("id")))) {
            grantedCharacters+=message.get("content").toString().length();
            if(grantedCharacters>12000) break;
            message.put("granted",true);result.add(message);
        }
        return result;
    }
    public List<Map<String,Object>> transferable(long user,UUID conv) {
        owned(user,conv);return db.queryForList("SELECT id::text AS id,content FROM platform_message WHERE conversation_id=? AND role='USER' AND scope='LOVE' AND context_valid=TRUE AND status='COMPLETED' ORDER BY id DESC LIMIT 20",conv);
    }
    public UUID grant(long user,UUID conv,long message) {
        owned(user,conv);
        if(db.queryForObject("SELECT COUNT(*) FROM platform_message WHERE id=? AND conversation_id=? AND role='USER' AND context_valid=TRUE AND scope='LOVE'",Integer.class,message,conv)!=1) throw missing();
        UUID id=UUID.randomUUID();db.update("INSERT INTO platform_context_grant(id,user_id,conversation_id,message_id) VALUES(?,?,?,?)",id,user,conv,message);return id;
    }
    public boolean active(ExecutionContext ctx) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM platform_run r JOIN app_user u ON u.id=r.user_id
              JOIN spring_session s ON s.session_id=r.session_id
              WHERE r.id=? AND r.user_id=? AND r.conversation_id=? AND r.love=? AND r.task=? AND r.web=?
                AND r.status='RUNNING' AND NOT r.cancel_requested AND u.enabled
                AND s.expiry_time>? AND r.created_at>CURRENT_TIMESTAMP-(? * INTERVAL '1 second'))
            """,Boolean.class,ctx.runId(),ctx.userId(),ctx.conversationId(),ctx.capabilities().love(),ctx.capabilities().task(),ctx.capabilities().web(),System.currentTimeMillis(),timeout));
    }
    public void checkpoint(ExecutionContext ctx,String answer) {
        db.update("UPDATE platform_message SET content=? WHERE run_id=? AND role='ASSISTANT' AND status='STREAMING'",answer,ctx.runId());
    }
    public void startAnswer(ExecutionContext ctx) {
        var cap=ctx.capabilities();
        db.update("INSERT INTO platform_message(conversation_id,run_id,role,content,scope,love,task,web,status) SELECT ?,?,'ASSISTANT','',scope,?,?,?,'STREAMING' FROM platform_message WHERE run_id=? AND role='USER'",
            ctx.conversationId(),ctx.runId(),cap.love(),cap.task(),cap.web(),ctx.runId());
    }
    @Transactional
    public void finish(ExecutionContext ctx,String answer,String status) {
        db.update("UPDATE platform_message SET content=?,status=?,context_valid=? WHERE run_id=? AND role='ASSISTANT'",answer,status,"COMPLETED".equals(status),ctx.runId());
        db.update("UPDATE platform_run SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",status,ctx.runId());
    }
    public void stop(long user,UUID run) {run(user,run);db.update("UPDATE platform_run SET cancel_requested=TRUE WHERE id=? AND user_id=?",run,user);}
    public void cancel(long user) {db.update("UPDATE platform_run SET cancel_requested=TRUE WHERE user_id=? AND status='RUNNING'",user);}
    @Transactional
    public void delete(long user,UUID conversation) {
        owned(user,conversation);
        if(db.queryForObject("SELECT COUNT(*) FROM platform_run WHERE conversation_id=? AND status='RUNNING'",Integer.class,conversation)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请先停止当前任务再删除会话");
        db.update("UPDATE platform_artifact SET deleted=TRUE WHERE user_id=? AND conversation_id=?",user,conversation);
        db.update("DELETE FROM platform_conversation WHERE id=? AND user_id=?",conversation,user);
    }
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
}
