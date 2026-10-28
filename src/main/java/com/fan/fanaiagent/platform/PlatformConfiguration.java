package com.fan.fanaiagent.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;

@Configuration
@EnableScheduling
public class PlatformConfiguration {
    // Separate prefix prevents a developer's old spring.datasource settings from opening the old database.
    @Bean @Primary @ConfigurationProperties("platform.datasource")
    DataSourceProperties platformDataSourceProperties() {
        var p=new DataSourceProperties();
        p.setUrl(System.getenv().getOrDefault("PLATFORM_DB_URL","jdbc:postgresql://localhost:5432/fan_ai_agent_v2"));
        p.setUsername(System.getenv().getOrDefault("PLATFORM_DB_USERNAME","fan_ai_agent"));
        p.setPassword(System.getenv().getOrDefault("PLATFORM_DB_PASSWORD",""));return p;
    }
    @Bean @Primary DataSource dataSource(DataSourceProperties platformDataSourceProperties) {
        return platformDataSourceProperties.initializeDataSourceBuilder().build();
    }
}
