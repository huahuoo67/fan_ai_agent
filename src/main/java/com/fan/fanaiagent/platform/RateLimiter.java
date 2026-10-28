package com.fan.fanaiagent.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RateLimiter {
    private final JdbcTemplate db;
    public RateLimiter(JdbcTemplate db) {this.db=db;}
    @Transactional
    public void check(String bucket,int perMinute) {
        db.execute("DELETE FROM platform_rate_event WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '2 minutes'");
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL",Boolean.class,bucket);
        Integer count=db.queryForObject("SELECT COUNT(*) FROM platform_rate_event WHERE bucket=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 minute'",Integer.class,bucket);
        if(count!=null && count>=perMinute) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请求较频繁，请稍后重试");
        db.update("INSERT INTO platform_rate_event(bucket) VALUES(?)",bucket);
    }
}
