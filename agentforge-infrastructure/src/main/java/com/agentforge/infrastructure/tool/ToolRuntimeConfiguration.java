package com.agentforge.infrastructure.tool;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.DefaultToolRegistry;
import com.agentforge.tool.ToolRegistry;
import com.agentforge.tool.device.DeviceStatusQueryPort;
import com.agentforge.tool.device.DeviceStatusTool;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRuntimeConfiguration {

    @Bean
    public DeviceStatusQueryPort deviceStatusQueryPort() {
        return new DemoDeviceStatusQueryAdapter();
    }

    @Bean
    public AgentTool deviceStatusTool(DeviceStatusQueryPort queryPort) {
        return new DeviceStatusTool(queryPort);
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> tools) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }
}
