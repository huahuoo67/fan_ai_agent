package com.fan.fanaiagent.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@Service
public class MemoryService {
    private final JdbcTemplate db;
    private static final String SELECT="SELECT f.*,m.conversation_id FROM platform_memory f JOIN platform_message m ON m.id=f.source_message_id ";
    public MemoryService(JdbcTemplate db) {this.db=db;}
    private List<MemoryFact> query(String suffix,Object...args) {
        return db.query(SELECT+suffix,(r,n)->new MemoryFact(r.getObject("id",UUID.class),r.getString("subject"),r.getString("fact_key"),
            r.getString("fact_value"),r.getString("kind"),r.getString("scope"),r.getBoolean("sensitive"),
            r.getObject("expires_at",OffsetDateTime.class),r.getString("status"),r.getInt("version"),r.getLong("source_message_id"),
            r.getObject("conversation_id",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getString("source_type")),args);
    }
    public List<MemoryFact> list(long user) {return query("WHERE f.user_id=? AND f.status IN ('ACTIVE','PENDING') ORDER BY f.created_at DESC",user);}
    @Transactional
    public List<MemoryFact> extract(ExecutionContext ctx,long messageId,String text) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT memory_enabled FROM app_user WHERE id=?",Boolean.class,ctx.userId()))) return List.of();
        var source=db.queryForList("SELECT m.scope,m.content FROM platform_message m JOIN platform_run r ON r.id=m.run_id WHERE m.id=? AND m.run_id=? AND r.user_id=? AND m.role='USER' AND m.context_valid=TRUE",messageId,ctx.runId(),ctx.userId());
        if(source.isEmpty() || !text.equals(source.getFirst().get("content"))) throw new IllegalArgumentException("记忆来源需为本轮用户原话");
        for(var draft:MemoryExtractor.extract(text,source.getFirst().get("scope").toString())) {
            if("TASK".equals(draft.kind())) continue; // current requirements live only in this turn
            var old=db.queryForList("SELECT id FROM platform_memory WHERE user_id=? AND subject=? AND fact_key=? AND kind=? AND scope=? AND status='ACTIVE' ORDER BY created_at DESC LIMIT 1",
                ctx.userId(),draft.subject(),draft.key(),draft.kind(),draft.scope());
            db.update("INSERT INTO platform_memory(id,user_id,source_message_id,subject,fact_key,fact_value,kind,scope,sensitive,expires_at,replaces_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),ctx.userId(),messageId,draft.subject(),draft.key(),draft.value(),draft.kind(),draft.scope(),draft.sensitive(),draft.expiresAt(),old.isEmpty()?null:old.getFirst().get("id"));
        }
        return query("WHERE f.user_id=? AND f.source_message_id=?",ctx.userId(),messageId);
    }
    public List<MemoryFact> eligible(ExecutionContext ctx) {
        return query("""
            WHERE f.user_id=? AND (f.scope<>'LOVE' OR ? OR EXISTS
              (SELECT 1 FROM platform_context_grant g WHERE g.message_id=f.source_message_id AND g.target_run_id=? AND g.user_id=?)) AND
              (f.status='ACTIVE' OR (f.status='PENDING' AND m.conversation_id=? AND m.context_valid=TRUE))
              AND (f.kind<>'TASK' OR m.run_id=?) ORDER BY f.created_at
            """,ctx.userId(),ctx.capabilities().love(),ctx.runId(),ctx.userId(),ctx.conversationId(),ctx.runId());
    }
    public EffectiveMemory effective(ExecutionContext ctx) {return MemoryResolver.resolve(eligible(ctx),OffsetDateTime.now(ZoneOffset.UTC));}
    public List<Map<String,Object>> filterHistory(ExecutionContext ctx,List<Map<String,Object>> history,List<MemoryExtractor.Draft> current) {
        var retractions=query("WHERE f.user_id=? AND f.kind='RETRACT' AND (f.scope<>'LOVE' OR ?) AND (f.status='ACTIVE' OR (f.status='PENDING' AND m.conversation_id=?))",
            ctx.userId(),ctx.capabilities().love(),ctx.conversationId());
        var invalidRuns=new HashSet<Object>();
        for(var message:history) if("USER".equals(message.get("role"))) {
            var drafts=MemoryExtractor.extract(message.get("content").toString(),message.get("scope").toString());
            for(var draft:drafts) {
                boolean invalid=current.stream().anyMatch(r->retracts(r.subject(),r.key(),r.kind(),r.value(),draft));
                invalid|=retractions.stream().anyMatch(r->r.sourceMessageId()>((Number)message.get("id")).longValue() && retracts(r.subject(),r.key(),r.kind(),r.value(),draft));
                if(invalid) invalidRuns.add(message.get("run_id"));
            }
        }
        return history.stream().filter(m->!invalidRuns.contains(m.get("run_id"))).toList();
    }
    private static boolean retracts(String subject,String key,String kind,String value,MemoryExtractor.Draft old) {
        return "RETRACT".equals(kind) && subject.equals(old.subject()) && key.equals(old.key()) &&
            (!value.matches(".*(恢复|解除|康复|好了).*" ) || "TEMPORARY".equals(old.kind()));
    }
    @Transactional
    public MemoryFact confirm(long user,UUID id,int version) {
        db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,user);
        var rows=query("WHERE f.user_id=? AND f.id=? AND m.role='USER' AND m.context_valid=TRUE",user,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆不存在");
        var fact=rows.getFirst();
        if(fact.version()!=version || !"PENDING".equals(fact.status()) || "TASK".equals(fact.kind()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"记忆状态已变化，请刷新；本次要求仅用于当前轮次");
        UUID expected=db.queryForObject("SELECT replaces_id FROM platform_memory WHERE id=?",UUID.class,id);
        var active=query("WHERE f.user_id=? AND f.subject=? AND f.fact_key=? AND f.kind=? AND f.scope=? AND f.status='ACTIVE' ORDER BY f.created_at DESC",user,fact.subject(),fact.key(),fact.kind(),fact.scope());
        UUID actual=active.isEmpty()?null:active.getFirst().id();
        if(!Objects.equals(expected,actual)) throw new ResponseStatusException(HttpStatus.CONFLICT,"同类记忆已更新，请重新确认");
        if(expected!=null) db.update("UPDATE platform_memory SET status='SUPERSEDED',version=version+1 WHERE id=? AND user_id=?",expected,user);
        if("RETRACT".equals(fact.kind())) {
            boolean recovery=fact.value().matches(".*(恢复|解除|康复|好了).*" );
            db.update("UPDATE platform_memory SET status='REVOKED',version=version+1 WHERE user_id=? AND subject=? AND fact_key=? AND scope=? AND status='ACTIVE'"+(recovery?" AND kind='TEMPORARY'":""),
                user,fact.subject(),fact.key(),fact.scope());
        }
        db.update("UPDATE platform_memory SET status='ACTIVE',version=version+1,confirmed_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",id,user);
        return query("WHERE f.user_id=? AND f.id=?",user,id).getFirst();
    }
    @Transactional
    public void revoke(long user,UUID id,int version) {
        if(db.update("UPDATE platform_memory SET status='REVOKED',version=version+1 WHERE id=? AND user_id=? AND version=? AND status IN ('ACTIVE','PENDING')",id,user,version)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"记忆已变化或不存在，请刷新");
    }
    @Transactional
    public MemoryFact edit(long user,UUID id,int version,String value,String scope) {
        if(value==null || value.isBlank() || value.length()>1000 || !Set.of("COMMON","LOVE","GENERAL").contains(scope) ||
            value.matches("(?is).*(密码|验证码|令牌|token|api.?key|secret).*")) throw new IllegalArgumentException();
        db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,user);
        var old=query("WHERE f.id=? AND f.user_id=?",id,user);
        if(old.isEmpty() || old.getFirst().version()!=version || !Set.of("ACTIVE","PENDING").contains(old.getFirst().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"记忆已变化，请刷新");
        var f=old.getFirst();
        if("TASK".equals(f.kind())) throw new ResponseStatusException(HttpStatus.CONFLICT,"本次要求仅用于当前轮次");
        if("RETRACT".equals(f.kind())) throw new ResponseStatusException(HttpStatus.CONFLICT,"纠错与解除记录请通过新的明确陈述更新");
        if(f.key().startsWith("diet.") && !(f.key().equals("diet.spicy")?value.contains("辣"):value.contains("香菜")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请保留这条饮食事实的类别，其他偏好可在聊天中新增");
        if("LOVE".equals(ContextPolicy.scope(value,Capabilities.NONE)) && !"LOVE".equals(scope))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"私密内容请保留在恋爱作用域");
        if("PENDING".equals(f.status())) {
            UUID expected=db.queryForObject("SELECT replaces_id FROM platform_memory WHERE id=?",UUID.class,id);
            var current=query("WHERE f.user_id=? AND f.subject=? AND f.fact_key=? AND f.kind=? AND f.scope=? AND f.status='ACTIVE'",user,f.subject(),f.key(),f.kind(),f.scope());
            UUID actual=current.isEmpty()?null:current.getFirst().id();
            if(!Objects.equals(expected,actual)) throw new ResponseStatusException(HttpStatus.CONFLICT,"同类记忆已更新，请刷新后编辑");
            if(expected!=null) db.update("UPDATE platform_memory SET status='SUPERSEDED',version=version+1 WHERE id=? AND user_id=?",expected,user);
        }
        if(!scope.equals(f.scope()) && !query("WHERE f.user_id=? AND f.subject=? AND f.fact_key=? AND f.kind=? AND f.scope=? AND f.status='ACTIVE'",user,f.subject(),f.key(),f.kind(),scope).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"目标作用域已有同类记忆，请先处理冲突");
        // Changing visibility is a separate explicit user action; health/partner facts stay restricted.
        if(!"LOVE".equals(scope) && "LOVE".equals(f.scope()) && (f.sensitive() || !"SELF".equals(f.subject())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"情感私密记忆保留在恋爱作用域");
        UUID next=UUID.randomUUID();
        db.update("UPDATE platform_memory SET status='SUPERSEDED',version=version+1 WHERE id=?",id);
        db.update("INSERT INTO platform_memory(id,user_id,source_message_id,subject,fact_key,fact_value,kind,scope,sensitive,expires_at,status,confirmed_at,replaces_id,source_type) VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,?,'USER_EDIT')",
            next,user,f.sourceMessageId(),f.subject(),f.key(),value,f.kind(),scope,f.sensitive(),f.expiresAt(),id);
        return query("WHERE f.id=? AND f.user_id=?",next,user).getFirst();
    }
}
