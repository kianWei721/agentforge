package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.tool.device.DeviceEventsTool;
import com.agentforge.tool.device.DevicePropertiesTool;
import com.agentforge.tool.device.DeviceStatusTool;
import com.agentforge.tool.knowledge.KnowledgeSearchTool;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AgentDefinitionConfiguration {

    private static final String IOT_DIAGNOSTIC_ID = "iot-diagnostic";
    private static final String IOT_DIAGNOSTIC_V05_DESCRIPTION =
            "V0.5 multi-tool IoT diagnostic Agent";
    private static final String IOT_DIAGNOSTIC_V051_DESCRIPTION =
            "V0.5.1 multi-tool IoT diagnostic Agent with hardened evidence semantics";

    private static final String IOT_DIAGNOSTIC_V05_PROMPT =
            "你是 AgentForge 的 IoT 运维诊断 Agent。"
                    + "针对设备运行是否正常、近期异常、故障原因等问题，自主选择 "
                    + "query_device_status、query_device_properties、query_device_events。"
                    + "根据问题需要组合多个工具，但不要为了凑数量调用无关工具。"
                    + "某个工具失败时，继续利用其他可用证据并明确说明信息缺口。"
                    + "最终回答必须区分工具观测事实和基于事实的推断，不得臆测设备事实。"
                    + "只使用当前 Agent 注册的只读工具，不执行设备控制。";

    private static final String IOT_DIAGNOSTIC_V051_PROMPT =
            "你是 AgentForge 的 IoT 运维诊断 Agent。"
                    + "针对设备运行是否正常、近期异常、故障原因等问题，自主选择 "
                    + "query_device_status、query_device_properties、query_device_events。"
                    + "根据问题需要组合多个工具，但不要为了凑数量调用无关工具。"
                    + "时间判断必须使用 Tool 返回的 queriedAt 作为本次查询时间；"
                    + "lastSeenAt、snapshotUpdatedAt 和属性 observedAt 都是历史时间，绝不能当作当前查询时间。"
                    + "如果 Tool 提供 lastSeenAgeSeconds 或 snapshotAgeSeconds，优先使用 Tool 的确定性计算结果。"
                    + "status=OFFLINE 可以作为当前离线事实，但除非有直接证据，不得推断具体掉线原因。"
                    + "shadowStatus=CONFLICT 只表示影子状态不一致，不得单独推断配置同步被中断、设备突然掉线或云端与设备谁更新。"
                    + "SignalStrength=0、Temperature=0、PeripheralStatus 全为 0 都可能是真实值或占位/历史值，"
                    + "没有额外语义时不得把它们作为故障原因或正常性的确定证据。"
                    + "query_device_events 返回的是物模型事件历史，不是告警专用数据源；"
                    + "events=[] 只能表述为本次没有返回事件记录，不能表述为没有告警或没有异常。"
                    + "某个工具失败时，继续利用其他可用证据并明确说明信息缺口。"
                    + "最终回答必须明确分为工具观测事实、可以支持的推断、证据不足/未知三部分，"
                    + "避免超过证据强度的因果结论。"
                    + "只使用当前 Agent 注册的只读工具，不执行设备控制。";

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

            AgentDefinition current =
                    repository.findById(IOT_DIAGNOSTIC_ID).orElse(null);
            if (current == null) {
                repository.save(
                        new AgentDefinition(
                                IOT_DIAGNOSTIC_ID,
                                "IoT Diagnostic Agent",
                                IOT_DIAGNOSTIC_V051_DESCRIPTION,
                                IOT_DIAGNOSTIC_V051_PROMPT,
                                "dashscope",
                                modelProperties.getDashscope().getModel(),
                                diagnosticTools(),
                                true,
                                1L,
                                now,
                                now));
            } else if (isUnmodifiedV05Seed(current)) {
                repository.save(
                        new AgentDefinition(
                                current.id(),
                                current.name(),
                                IOT_DIAGNOSTIC_V051_DESCRIPTION,
                                IOT_DIAGNOSTIC_V051_PROMPT,
                                current.modelProvider(),
                                current.modelName(),
                                current.toolNames(),
                                current.enabled(),
                                current.version() + 1,
                                current.createdAt(),
                                now));
            }

            if (!repository.existsById("iot-knowledge-diagnostic")) {
                repository.save(
                        new AgentDefinition(
                                "iot-knowledge-diagnostic",
                                "IoT Knowledge Diagnostic Agent",
                                "V0.6.1 IoT diagnostic Agent with knowledge grounding",
                                knowledgeGroundingPrompt(),
                                "dashscope",
                                modelProperties.getDashscope().getModel(),
                                List.of(
                                        DeviceStatusTool.NAME,
                                        DevicePropertiesTool.NAME,
                                        DeviceEventsTool.NAME,
                                        KnowledgeSearchTool.NAME),
                                true,
                                1L,
                                now,
                                now));
            }
        };
    }

    private static boolean isUnmodifiedV05Seed(AgentDefinition current) {
        return current.version() == 1L
                && IOT_DIAGNOSTIC_V05_DESCRIPTION.equals(current.description())
                && IOT_DIAGNOSTIC_V05_PROMPT.equals(current.systemPrompt())
                && diagnosticTools().equals(current.toolNames());
    }

    private static String knowledgeGroundingPrompt() {
        return "你是 AgentForge 的 IoT 知识增强诊断 Agent。"
                + "query_device_status、query_device_properties、query_device_events 返回的是实时业务系统或其历史观测事实；"
                + "search_knowledge 返回的是静态知识库片段，例如设备说明书、故障手册、SOP、平台文档。"
                + "必须严格区分这两类证据：知识库内容不能被描述为设备当前事实，实时 Tool 结果也不能冒充手册规则。"
                + "判断设备当前状态、最后通信、属性值时，以 IoT Tool 为事实来源；"
                + "解释字段含义、排查步骤、推荐动作时，可以检索知识库作为指导。"
                + "如果知识库没有相关命中，明确说知识依据不足，不得用常识伪造成内部手册内容。"
                + "知识建议与实时状态冲突时，实时事实决定设备现在是什么状态，知识只用于解释和排查方法。"
                + "所有基于知识库的具体规则或步骤必须带上 search_knowledge 返回的 citationId，格式保持原样，例如 [KB:documentId#3]。"
                + "知识命中只支持它实际写明的内容，不要扩写成来源没有提供的结论。"
                + "离线根因在没有直接证据时保持 UNKNOWN；知识库只能给排查建议，不能把候选原因升级为已确认原因。"
                + "最终回答优先分为：实时观测事实、知识库依据、综合判断、建议排查步骤、仍然未知的信息。"
                + "只使用当前 Agent 注册的只读工具，不执行设备控制。";
    }

    private static List<String> diagnosticTools() {
        return List.of(
                DeviceStatusTool.NAME,
                DevicePropertiesTool.NAME,
                DeviceEventsTool.NAME);
    }
}
