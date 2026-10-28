package com.fan.fanaiagent.platform;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice(basePackages="com.fan.fanaiagent.platform")
public class PlatformErrors {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PlatformErrors.class);
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getStatusCode().value(),"message",e.getReason()==null?"请求未完成":e.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> bad(Exception e) {return ResponseEntity.badRequest().body(Map.of("code",400,"message","请求参数不正确"));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> error(Exception e) {
        log.warn("Platform request failed: {}",e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code",500,"message","服务暂时异常，请稍后重试"));
    }
}
