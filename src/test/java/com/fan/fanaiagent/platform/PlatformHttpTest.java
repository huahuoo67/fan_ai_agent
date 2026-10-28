package com.fan.fanaiagent.platform;

import com.fan.fanaiagent.FanAiAgentApplication;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full HTTP stack, real session cookies and session JDBC, disposable DB, synthetic model. */
@SpringBootTest(classes=FanAiAgentApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=${PLATFORM_TEST_PORT:0}",
    "spring.ai.dashscope.api-key=test-fixture-key","platform.knowledge-sync=false","platform.artifact-root=target/http-artifacts",
    "logging.level.root=WARN","spring.main.banner-mode=off"})
@ActiveProfiles("platform") @DirtiesContext
@Import(PlatformHttpTest.FixtureDatabase.class)
class PlatformHttpTest {
    static EmbeddedPostgres postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();
        properties.add("platform.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
        properties.add("platform.datasource.username",()->"postgres");properties.add("platform.datasource.password",()->"");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean PlatformModel model;
    @MockBean VectorStore vectorStore;
    @TestConfiguration static class FixtureDatabase {
        @Bean(destroyMethod="close") EmbeddedPostgres fixtureDatabase() {return postgres;}
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor databaseOrder() {
            return factory->factory.getBeanDefinition("dataSource").setDependsOn("fixtureDatabase");
        }
    }
    HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    String token,header;
    HttpResponse<String> call(String path,String method,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path));
        if(!method.equals("GET") && token!=null) builder.header(header,token);
        builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    @Test void registrationLoginSessionRotationStreamingAndLogoutWorkOverHttp() throws Exception {
        var csrf=json.readTree(call("/auth/csrf","GET",null).body());token=csrf.path("token").asText();header=csrf.path("headerName").asText();
        assertEquals(401,call("/auth/current","GET",null).statusCode());
        String credentials="{\"account\":\"http_fixture\",\"password\":\"fixture-password-123\",\"username\":\"测试用户\"}";
        assertEquals(200,call("/auth/register","POST",credentials).statusCode());
        assertEquals(200,call("/auth/login","POST",credentials).statusCode());
        csrf=json.readTree(call("/auth/csrf","GET",null).body());token=csrf.path("token").asText();header=csrf.path("headerName").asText();
        var account=call("/auth/current","GET",null);assertEquals(200,account.statusCode());assertEquals("http_fixture",json.readTree(account.body()).path("account").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM spring_session_attributes WHERE attribute_name='PLATFORM_USER_ID'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM spring_session_attributes WHERE attribute_name='SPRING_SECURITY_CONTEXT'",Integer.class));
        var created=call("/chat/conversations","POST",null);assertEquals(200,created.statusCode(),created.body());
        String conv=json.readTree(created.body()).path("id").asText();
        doAnswer(invocation->{
            ((Consumer<String>)invocation.getArgument(4)).accept("清淡约会建议");
            ((BiConsumer<String,Object>)invocation.getArgument(5)).accept("notice",Map.of("message","本轮未联网"));return null;
        }).when(model).answer(any(),any(),any(),any(),any(),any());
        String turn="{\"requestId\":\""+UUID.randomUUID()+"\",\"message\":\"我最近感冒暂时不吃辣\",\"capabilities\":{\"love\":false,\"task\":false,\"web\":false}}";
        var response=call("/chat/conversations/"+conv+"/turns","POST",turn);
        assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("answer_delta"));assertTrue(response.body().contains("清淡约会建议"));assertTrue(response.body().contains("event:done"));
        var history=call("/chat/conversations/"+conv+"/messages","GET",null);assertEquals(200,history.statusCode());
        assertTrue(history.body().contains("COMPLETED"));assertTrue(history.body().contains("本轮未联网"));
        if(Boolean.getBoolean("platform.ui-preview")) {
            System.out.println("Disposable UI fixture ready at http://127.0.0.1:"+port);
            Thread.sleep(180000);
        }
        assertEquals(200,call("/auth/logout","POST",null).statusCode());assertEquals(401,call("/auth/current","GET",null).statusCode());
    }
}
