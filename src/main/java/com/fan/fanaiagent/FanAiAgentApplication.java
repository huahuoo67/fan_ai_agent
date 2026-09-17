package com.fan.fanaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication//(exclude = {
//         为了便于大家开发调试和部署，取消数据库自动配置，需要使用 PgVector 时把 DataSourceAutoConfiguration.class 删除
//         DataSourceAutoConfiguration.class
// })
public class FanAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FanAiAgentApplication.class, args);
    }

}
