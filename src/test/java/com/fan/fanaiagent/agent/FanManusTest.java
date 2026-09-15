package com.fan.fanaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FanManusTest {

    @Resource
    private FanManus fanManus;

    @Test
    public void run() {
        String userPrompt = """
                我的另一半居住在山西省河曲县，请帮我找到 5 公里内合适的约会地点，
                并结合一些网络图片，制定一份详细的约会计划，
                并以 PDF 格式输出""";
        String answer = fanManus.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}