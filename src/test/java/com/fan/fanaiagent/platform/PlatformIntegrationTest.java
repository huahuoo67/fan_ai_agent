package com.fan.fanaiagent.platform;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

/** Real disposable PostgreSQL, no existing DB, embeddings, model or external tools. */
@WebMvcTest(controllers={AccountController.class,ChatController.class},properties={"platform.timeout-seconds=180","platform.artifact-root=target/test-artifacts"})
@Import({PlatformSecurity.class,AccountService.class,RateLimiter.class,PlatformStore.class,MemoryService.class,
    ArtifactService.class,ChatOrchestrator.class,PlatformErrors.class,PlatformIntegrationTest.Database.class})
class PlatformIntegrationTest {
    @TestConfiguration @EnableTransactionManagement(proxyTargetClass=true)
    static class Database {
        @Bean(destroyMethod="close") EmbeddedPostgres postgres() throws Exception {return EmbeddedPostgres.builder().setPort(0).start();}
        @Bean DataSource dataSource(EmbeddedPostgres postgres) {return postgres.getPostgresDatabase();}
        @Bean(initMethod="migrate") Flyway flyway(DataSource dataSource) {return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();}
        @Bean @DependsOn("flyway") JdbcTemplate jdbc(DataSource dataSource) {return new JdbcTemplate(dataSource);}
        @Bean PlatformTransactionManager transactions(DataSource dataSource) {return new DataSourceTransactionManager(dataSource);}
    }
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired PlatformStore store;
    @Autowired MemoryService memories;
    @Autowired ArtifactService artifacts;
    @Autowired JdbcTemplate db;
    @MockBean PlatformModel model;
    private long a,b;
    @BeforeEach void users() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        a=Long.parseLong(accounts.register(new Register("a_"+suffix,"test-password-123","用户A")).id());
        b=Long.parseLong(accounts.register(new Register("b_"+suffix,"test-password-123","用户B")).id());
    }
    MockHttpSession session(long user) {
        var session=new MockHttpSession();session.setAttribute(PlatformSecurity.USER_ID,user);return session;
    }
    PlatformStore.Started start(long user,UUID conv,String text,Capabilities cap) {
        String session=UUID.randomUUID().toString();
        db.update("INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),session,System.currentTimeMillis(),System.currentTimeMillis(),7200,System.currentTimeMillis()+7200000);
        return store.begin(user,conv,new TurnRequest(UUID.randomUUID(),text,cap,null,null,null),session);
    }
    @Test void loginAndOwnershipEnforcedAtHttpBoundary() throws Exception {
        mvc.perform(get("/chat/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/chat/conversations").session(session(a))).andExpect(status().isForbidden());
        UUID conv=(UUID)store.create(a).get("id");
        mvc.perform(get("/chat/conversations/"+conv+"/messages").session(session(b))).andExpect(status().isNotFound());
        mvc.perform(delete("/chat/conversations/"+conv).session(session(b)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/admin/users").session(session(a))).andExpect(status().isForbidden());
    }
    @Test void passwordHashNeverLeaksAndDisabledSessionStopsWorking() throws Exception {
        mvc.perform(get("/auth/current").session(session(a))).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(Long.toString(a))).andExpect(jsonPath("$.password_hash").doesNotExist());
        String hash=db.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,a);assertTrue(hash.startsWith("$2"));
        db.update("UPDATE app_user SET enabled=FALSE WHERE id=?",a);
        mvc.perform(get("/chat/conversations").session(session(a))).andExpect(status().isUnauthorized());
    }
    @Test void idempotencyAndConcurrencyAreTransactional() {
        UUID conv=(UUID)store.create(a).get("id"),request=UUID.randomUUID();
        var input=new TurnRequest(request,"你好",Capabilities.NONE,null,null,null);
        var one=store.begin(a,conv,input,"session");var two=store.begin(a,conv,input,"session");
        assertFalse(one.duplicate());assertTrue(two.duplicate());assertEquals(one.context().runId(),two.context().runId());
        assertThrows(Exception.class,()->store.begin(a,conv,new TurnRequest(request,"不同内容",Capabilities.NONE,null,null,null),"session"));
        assertThrows(Exception.class,()->start(a,conv,"第二轮",Capabilities.NONE));
        assertThrows(Exception.class,()->store.stop(b,one.context().runId()));
    }
    @Test void pendingMemoryStaysInSourceConversationAndConfirmedMemoryCrossesConversations() {
        UUID first=(UUID)store.create(a).get("id"),second=(UUID)store.create(a).get("id");
        var source=start(a,first,"我喜欢吃辣",Capabilities.NONE);
        var candidate=memories.extract(source.context(),source.messageId(),"我喜欢吃辣").getFirst();
        var target=start(a,second,"推荐吃什么",Capabilities.NONE);
        assertTrue(memories.effective(target.context()).facts().isEmpty());
        memories.confirm(a,candidate.id(),1);
        assertEquals("喜欢吃辣",memories.effective(target.context()).facts().getFirst().value());
        assertTrue(store.snapshot(target.context()).facts().isEmpty()); // confirmation affects only later openings
        assertThrows(Exception.class,()->memories.confirm(b,candidate.id(),1));
    }
    @Test void temporaryOverrideAndStaleConfirmationsWorkInRealDatabase() {
        UUID conv=(UUID)store.create(a).get("id");var turn=start(a,conv,"我喜欢吃辣",Capabilities.NONE);
        var baseline=memories.extract(turn.context(),turn.messageId(),"我喜欢吃辣").getFirst();memories.confirm(a,baseline.id(),1);
        store.startAnswer(turn.context());store.finish(turn.context(),"收到","COMPLETED");
        var temporary=start(a,conv,"我最近感冒暂时不吃辣",Capabilities.NONE);
        var candidates=memories.extract(temporary.context(),temporary.messageId(),"我最近感冒暂时不吃辣");
        memories.confirm(a,candidates.getLast().id(),1);
        var result=memories.effective(temporary.context());assertEquals("不吃辣",result.facts().getFirst().value());
        assertTrue(memories.list(a).stream().anyMatch(f->"PREFERENCE".equals(f.kind()) && "ACTIVE".equals(f.status())));
        assertThrows(Exception.class,()->memories.confirm(a,baseline.id(),1));
    }
    @Test void loveHistoryAndItsAnswerStayRestrictedThenExplicitGrantWorksOnce() {
        UUID conv=(UUID)store.create(a).get("id");var first=start(a,conv,"我和女朋友分手了",Capabilities.NONE);
        store.startAnswer(first.context());store.finish(first.context(),"关于这段私密关系的回答","COMPLETED");
        assertEquals("LOVE",db.queryForObject("SELECT scope FROM platform_message WHERE run_id=? AND role='ASSISTANT'",String.class,first.context().runId()));
        var second=start(a,conv,"天气如何",Capabilities.NONE);
        assertTrue(store.history(second.context()).isEmpty());store.startAnswer(second.context());store.finish(second.context(),"天气需要联网","COMPLETED");
        UUID grant=store.grant(a,conv,first.messageId());
        var input=new TurnRequest(UUID.randomUUID(),"根据刚才情况做计划",Capabilities.NONE,List.of(grant),null,null);
        var granted=store.begin(a,conv,input,"session");
        assertTrue(store.history(granted.context()).stream().anyMatch(m->m.get("content").equals("我和女朋友分手了")));
        assertFalse(store.history(granted.context()).stream().anyMatch(m->m.get("content").equals("关于这段私密关系的回答")));
        store.startAnswer(granted.context());store.finish(granted.context(),"基于已带入关系信息的计划","COMPLETED");
        var future=start(a,conv,"解释普通知识",Capabilities.NONE);
        assertFalse(store.history(future.context()).stream().anyMatch(m->m.get("content").equals("基于已带入关系信息的计划")));
    }
    @Test void deletingConversationRemovesSourceMemoriesAndTombstonesFiles() throws Exception {
        UUID conv=(UUID)store.create(a).get("id");var turn=start(a,conv,"我喜欢吃辣",new Capabilities(false,true,false));
        var candidate=memories.extract(turn.context(),turn.messageId(),"我喜欢吃辣").getFirst();memories.confirm(a,candidate.id(),1);
        var artifact=artifacts.save(turn.context(),"plan.txt","text/plain","text".getBytes());UUID file=(UUID)artifact.get("id");
        mvc.perform(get("/artifacts/"+file+"/download").session(session(b))).andExpect(status().isNotFound());
        mvc.perform(post("/chat/runs/"+turn.context().runId()+"/stop").session(session(b)).with(csrf())).andExpect(status().isNotFound());
        assertThrows(Exception.class,()->artifacts.owned(b,file));
        assertThrows(Exception.class,()->artifacts.path("../../outside"));
        store.startAnswer(turn.context());store.finish(turn.context(),"done","COMPLETED");store.delete(a,conv);
        assertTrue(memories.list(a).isEmpty());assertTrue(artifacts.list(a).isEmpty());
        assertTrue(db.queryForObject("SELECT deleted FROM platform_artifact WHERE id=?",Boolean.class,file));artifacts.cleanup();
    }
    @Test void sessionExpirationAndCancellationStopToolPermission() {
        UUID conv=(UUID)store.create(a).get("id");var turn=start(a,conv,"你好",Capabilities.NONE);
        assertTrue(store.active(turn.context()));store.stop(a,turn.context().runId());assertFalse(store.active(turn.context()));
        UUID next=(UUID)store.create(a).get("id");var expired=start(a,next,"你好",Capabilities.NONE);
        db.update("UPDATE spring_session SET expiry_time=0 WHERE session_id=(SELECT session_id FROM platform_run WHERE id=?)",expired.context().runId());
        assertFalse(store.active(expired.context()));
    }
    @Test void concurrentCandidatesNeverBothReplaceTheSameVersion() throws Exception {
        UUID conv=(UUID)store.create(a).get("id");var turn=start(a,conv,"我喜欢吃辣",Capabilities.NONE);
        var first=memories.extract(turn.context(),turn.messageId(),"我喜欢吃辣").getFirst();
        store.startAnswer(turn.context());store.finish(turn.context(),"收到","COMPLETED");
        var next=start(a,conv,"我现在不喜欢吃辣",Capabilities.NONE);
        memories.extract(next.context(),next.messageId(),"我现在不喜欢吃辣");
        UUID second=db.queryForObject("SELECT id FROM platform_memory WHERE user_id=? AND fact_value='不吃辣'",UUID.class,a);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var calls=List.of(first.id(),second).stream().map(id->pool.submit(()-> {
                gate.await();try {memories.confirm(a,id,1);return true;}catch(org.springframework.web.server.ResponseStatusException e) {assertEquals(409,e.getStatusCode().value());return false;}
            })).toList();gate.countDown();int succeeded=0;for(var call:calls) if(call.get()) succeeded++;
            assertEquals(1,succeeded);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM platform_memory WHERE user_id=? AND status='ACTIVE'",Integer.class,a));
    }
    @Test void currentCorrectionFiltersItsContradictoryRawHistory() {
        UUID conv=(UUID)store.create(a).get("id");var first=start(a,conv,"我喜欢吃辣",Capabilities.NONE);
        memories.extract(first.context(),first.messageId(),"我喜欢吃辣");store.startAnswer(first.context());store.finish(first.context(),"旧描述","COMPLETED");
        var second=start(a,conv,"你说我喜欢辣，但我从来没说过",Capabilities.NONE);
        assertTrue(memories.filterHistory(second.context(),store.history(second.context()),MemoryExtractor.extract("你说我喜欢辣，但我从来没说过","GENERAL")).isEmpty());
    }
    @Test void chinesePdfIsARealPrivateArtifactAndStopPreventsLateWrites() throws Exception {
        UUID conv=(UUID)store.create(a).get("id");var turn=start(a,conv,"生成计划文件",new Capabilities(false,true,false));
        var tools=new ScopedTools(turn.context(),store,artifacts,org.mockito.Mockito.mock(PublicHttp.class),"","",8,(type,data)->{});
        tools.createPdf("约会计划.pdf","约会安排：选择清淡不辣的晚餐，之后在公园散步。");
        var list=artifacts.list(a);assertEquals(1,list.size());
        byte[] bytes=java.nio.file.Files.readAllBytes(artifacts.download(a,(UUID)list.getFirst().get("id")));
        assertTrue(new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF"));
        store.stop(a,turn.context().runId());
        assertThrows(IllegalStateException.class,()->artifacts.save(turn.context(),"late.txt","text/plain",new byte[]{1}));
    }
    @Test void temporaryRecoveryRestoresStablePreferenceFromOpeningSnapshot() {
        UUID conv=(UUID)store.create(a).get("id");var first=start(a,conv,"我喜欢吃辣",Capabilities.NONE);
        memories.confirm(a,memories.extract(first.context(),first.messageId(),"我喜欢吃辣").getFirst().id(),1);
        store.startAnswer(first.context());store.finish(first.context(),"收到","COMPLETED");
        var second=start(a,conv,"我最近感冒暂时不吃辣",Capabilities.NONE);
        memories.confirm(a,memories.extract(second.context(),second.messageId(),"我最近感冒暂时不吃辣").getFirst().id(),1);
        store.startAnswer(second.context());store.finish(second.context(),"收到","COMPLETED");
        var third=start(a,conv,"我感冒好了，现在恢复吃辣",Capabilities.NONE);
        var raw=new ArrayList<>(store.snapshot(third.context()).facts());
        raw.addAll(memories.extract(third.context(),third.messageId(),"我感冒好了，现在恢复吃辣"));
        assertEquals("喜欢吃辣",MemoryResolver.resolve(raw,java.time.OffsetDateTime.now()).facts().getFirst().value());
        assertThrows(IllegalArgumentException.class,()->memories.extract(third.context(),third.messageId(),"助手伪造的个人事实"));
    }
    @Test void explicitMemoryEditHasItsOwnSourceAndKeepsOriginalMessageIntact() {
        UUID conv=(UUID)store.create(a).get("id");var turn=start(a,conv,"我喜欢吃辣",Capabilities.NONE);
        var old=memories.confirm(a,memories.extract(turn.context(),turn.messageId(),"我喜欢吃辣").getFirst().id(),1);
        var updated=memories.edit(a,old.id(),old.version(),"不吃辣","GENERAL");
        assertEquals("USER_EDIT",updated.sourceType());assertNotEquals(old.id(),updated.id());
        assertEquals("我喜欢吃辣",db.queryForObject("SELECT content FROM platform_message WHERE id=?",String.class,turn.messageId()));
        assertEquals("不吃辣",memories.effective(turn.context()).facts().getFirst().value());
        assertThrows(Exception.class,()->memories.edit(a,old.id(),old.version(),"喜欢辣","GENERAL"));
    }
    @Test void disablingLoveHidesNarrativeButKeepsConfirmedMinimalDietRestriction() {
        UUID conv=(UUID)store.create(a).get("id");var first=start(a,conv,"我喜欢吃辣",new Capabilities(true,false,false));
        memories.confirm(a,memories.extract(first.context(),first.messageId(),"我喜欢吃辣").getFirst().id(),1);
        store.startAnswer(first.context());store.finish(first.context(),"收到","COMPLETED");
        var second=start(a,conv,"我最近感冒暂时不吃辣",new Capabilities(true,false,false));
        memories.confirm(a,memories.extract(second.context(),second.messageId(),"我最近感冒暂时不吃辣").getFirst().id(),1);
        store.startAnswer(second.context());store.finish(second.context(),"收到","COMPLETED");
        var next=start(a,conv,"推荐晚饭",Capabilities.NONE);
        assertTrue(store.history(next.context()).isEmpty());
        assertEquals("不吃辣",memories.effective(next.context()).facts().getFirst().value());
    }
    @Test void explicitlySelectedPrivateFileDoesNotLeakIntoLaterOrdinaryHistory() throws Exception {
        UUID conv=(UUID)store.create(a).get("id");var first=start(a,conv,"生成关系资料",new Capabilities(true,true,false));
        var saved=artifacts.save(first.context(),"private.txt","text/plain","私密关系背景".getBytes(java.nio.charset.StandardCharsets.UTF_8));String file=saved.get("id").toString();
        store.startAnswer(first.context());store.finish(first.context(),"已生成","COMPLETED");
        var read=start(a,conv,"读取指定文件 "+file,new Capabilities(false,true,false));store.startAnswer(read.context());
        var tool=new ScopedTools(read.context(),store,artifacts,org.mockito.Mockito.mock(PublicHttp.class),"","",8,(t,d)->{},"读取指定文件 "+file);
        assertEquals("私密关系背景",tool.readOwnFile(file));store.finish(read.context(),"文件中的私密关系回答","COMPLETED");
        var next=start(a,conv,"解释Java集合",Capabilities.NONE);
        assertTrue(store.history(next.context()).isEmpty());
    }
}
