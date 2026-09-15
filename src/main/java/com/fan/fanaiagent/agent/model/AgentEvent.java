package com.fan.fanaiagent.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前后端之间的最小智能体事件协议。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {
    private String type;
    private String content;
    private Integer step;
    private String toolName;
    private String status;
    private String code;
    private Boolean retryable;
    private Boolean success;
}
