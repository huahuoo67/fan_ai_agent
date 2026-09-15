package com.fan.fanaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PDFGenerationToolTest {

    @Test
    void generatePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "Fan智能体示例.pdf";
        String content = "Fan AI 智能体示例文档";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}