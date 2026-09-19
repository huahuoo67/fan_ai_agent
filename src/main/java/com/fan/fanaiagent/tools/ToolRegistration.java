package com.fan.fanaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools(ToolCallbackProvider toolCallbackProvider) {
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();
        ToolCallback[] localTools = ToolCallbacks.from(
                fileOperationTool,
                webSearchTool,
                webScrapingTool,
                resourceDownloadTool,
                pdfGenerationTool,
                terminateTool
        );
        ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
        ToolCallback[] tools = new ToolCallback[localTools.length + mcpTools.length];
        System.arraycopy(localTools, 0, tools, 0, localTools.length);
        System.arraycopy(mcpTools, 0, tools, localTools.length, mcpTools.length);
        return tools;
    }
}
