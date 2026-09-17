package com.fan.fanaiagent;

import com.fan.fanaiagent.agent.FanManus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Rename regression checks: no live MCP processes, model calls or database writes. */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.dashscope.api-key=offline-rename-test-key"
})
@AutoConfigureMockMvc
@Import(BackendRenameSmokeTest.OfflineTools.class)
class BackendRenameSmokeTest {

    // Replaces eager knowledge-base initialization, avoiding external API calls.
    // 内存向量存储（loveAppVectorStore）已停用，当前生效的是 PgVector，
    // 因此这里按名字替换 pgVectorVectorStore，避免测试期间真实连库与调用 Embedding。
    @MockBean(name = "pgVectorVectorStore")
    private VectorStore vectorStore;

    @TestConfiguration
    static class OfflineTools {
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void renamedApplicationStartsAndKeepsHttpContract() throws Exception {
        assertNotNull(context.getBean(FanManus.class));
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/ai/manus/chat']").exists())
                .andExpect(jsonPath("$.paths['/ai/love_app/chat/sse']").exists());
    }
}
