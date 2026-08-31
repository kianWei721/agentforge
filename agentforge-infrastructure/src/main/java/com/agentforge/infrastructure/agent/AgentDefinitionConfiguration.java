package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.tool.device.DeviceEventsTool;
import com.agentforge.tool.device.DevicePropertiesTool;
import com.agentforge.tool.device.DeviceStatusTool;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AgentDefinitionConfiguration {

    @Bean
    public AgentDefinitionRepository agentDefinitionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAgentDefinitionRepository(jdbcTemplate);
    }

    @Bean
    public AgentDefinitionService agentDefinitionService(AgentDefinitionRepository repository) {
        return new AgentDefinitionService(repository);
    }

    @Bean
    public ApplicationRunner defaultAgentSeeder(
            AgentDefinitionRepository repository,
            AgentRuntimeProperties agentProperties,
            ModelProviderProperties modelProperties) {
        return args -> {
            if (!agentProperties.isEnabled()) {
                return;
            }

            Instant now = Instant.now();
            if (!repository.existsById("default")) {
                repository.save(
                        new AgentDefinition(
                                "default",
                                agentProperties.getName(),
                                "V0.3 default Agent",
                                agentProperties.getSystemPrompt(),
                                "dashscope",
                                modelProperties.getDashscope().getModel(),
                                List.of(DeviceStatusTool.NAME),
                                true,
                                1L,
                                now,
                                now));
            }

            if (!repository.existsById("iot-diagnostic")) {
                repository.save(
                        new AgentDefinition(
                                "iot-diagnostic",
                                "IoT Diagnostic Agent",
                                "V0.5 multi-tool IoT diagnostic Agent",
                                "你是 AgentForge 的 IoT 运维诊断 Agent。"
                                        + "针对设备运行是否正常、近期异常、故障原因等问题，自主选择 "
                                        + "query_device_status、query_device_properties、query_device_events。"
                                        + "根据问题需要组合多个工具，但不要为了凑数量调用无关工具。"
                                        + "某个工具失败时，继续利用其他可用证据并明确说明信息缺口。"
                                        + "最终回答必须区分工具观测事实和基于事实的推断，不得臆测设备事实。"
                                        + "只使用当前 Agent 注册的只读工具，不执行设备控制。",
                                "dashscope",
                                modelProperties.getDashscope().getModel(),
                                List.of(
                                        DeviceStatusTool.NAME,
                                        DevicePropertiesTool.NAME,
                                        DeviceEventsTool.NAME),
                                true,
                                1L,
                                now,
                                now));
            }
        };
    }
}
