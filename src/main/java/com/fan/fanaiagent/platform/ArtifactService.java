package com.fan.fanaiagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@Service
public class ArtifactService {
    private final JdbcTemplate db; private final Path root;private final PlatformStore store;
    @Value("${platform.artifact-max-bytes:10485760}") private long maximum;
    @Value("${platform.user-quota-bytes:52428800}") private long quota;
    public ArtifactService(JdbcTemplate db,PlatformStore store,@Value("${platform.artifact-root:./tmp/platform-artifacts}") String root) throws Exception {
        this.db=db;this.store=store;this.root=Path.of(root).toAbsolutePath().normalize();Files.createDirectories(this.root);
        for(Path p=this.root;p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p)) throw new IllegalStateException("存储根目录及上层应为真实目录");
    }
    Path path(String key) throws Exception {
        Path target=root.resolve(key).normalize();
        if(!target.startsWith(root) || target.equals(root)) throw new IllegalArgumentException("文件路径越界");
        Path p=target;while(p!=null) {if(Files.isSymbolicLink(p)) throw new IllegalArgumentException("文件路径越界");p=p.getParent();}
        return target;
    }
    @Transactional
    public Map<String,Object> save(ExecutionContext ctx,String name,String type,byte[] content) throws Exception {
        if(!ctx.capabilities().task()) throw new IllegalArgumentException("请先开启任务执行");
        if(content.length>maximum) throw new IllegalArgumentException("文件超出大小限制");
        if(name==null || !name.matches("[\\p{L}\\p{N} _().-]{1,100}") || name.contains("..")) throw new IllegalArgumentException("文件名不正确");
        db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,ctx.userId());
        if(!store.active(ctx)) throw new IllegalStateException("任务已停止或身份失效");
        long used=db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM platform_artifact WHERE user_id=? AND NOT deleted",Long.class,ctx.userId());
        if(used+content.length>quota) throw new IllegalArgumentException("个人文件空间已满，请删除旧文件");
        UUID id=UUID.randomUUID();String key=ctx.userId()+"/"+ctx.conversationId()+"/"+id;
        Path target=path(key);Files.createDirectories(target.getParent());path(key);
        Files.write(target,content,StandardOpenOption.CREATE_NEW);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if(status!=STATUS_COMMITTED) try {Files.deleteIfExists(target);} catch(Exception ignored) { }
                }
            });
        try {
            db.update("INSERT INTO platform_artifact(id,user_id,conversation_id,run_id,name,storage_key,media_type,size_bytes,scope) VALUES(?,?,?,?,?,?,?,?,?)",
                id,ctx.userId(),ctx.conversationId(),ctx.runId(),name,key,type,content.length,store.messageScope(ctx));
        } catch(Exception e) {Files.deleteIfExists(target);throw e;}
        return Map.of("id",id,"name",name,"url","/api/artifacts/"+id+"/download","size",content.length);
    }
    public List<Map<String,Object>> list(long user) {
        return db.queryForList("SELECT id,name,media_type,size_bytes,created_at,run_id FROM platform_artifact WHERE user_id=? AND NOT deleted ORDER BY created_at DESC",user);
    }
    public Map<String,Object> owned(long user,UUID id) {
        var rows=db.queryForList("SELECT * FROM platform_artifact WHERE user_id=? AND id=? AND NOT deleted",user,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文件不存在");return rows.getFirst();
    }
    public Path download(long user,UUID id) throws Exception {return path(owned(user,id).get("storage_key").toString());}
    public void delete(long user,UUID id) {owned(user,id);db.update("UPDATE platform_artifact SET deleted=TRUE WHERE user_id=? AND id=?",user,id);}
    @Scheduled(fixedDelay=60000)
    public void cleanup() {
        for(var row:db.queryForList("SELECT id,storage_key FROM platform_artifact WHERE deleted LIMIT 100")) {
            try {Files.deleteIfExists(path(row.get("storage_key").toString()));db.update("DELETE FROM platform_artifact WHERE id=? AND deleted",row.get("id"));}
            catch(Exception ignored) { /* Tombstone remains for next cleanup attempt. */ }
        }
    }
}
