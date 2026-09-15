package com.fan.fanaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FileOperationToolTest {

    @Test
    void readFile() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        String fileName = "Fan项目.txt";
        String result = fileOperationTool.readFile(fileName);
        Assertions.assertNotNull(result);
    }

    @Test
    void writeFile() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        String fileName = "Fan项目.txt";
        String content = "https://example.com 程序员编程学习交流社区";
        String result = fileOperationTool.writeFile(fileName, content);
        Assertions.assertNotNull(result);
    }
}
