package com.agentforge.infrastructure.tool;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.infrastructure.config.IoTLureLampControlProperties;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.DefaultToolRegistry;
import com.agentforge.tool.ToolRegistry;
import com.agentforge.tool.device.DeviceStatusQueryPort;
import com.agentforge.tool.device.DeviceStatusTool;
import com.agentforge.tool.device.LureLampControlPort;
import com.agentforge.tool.device.LureLampControlTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRuntimeConfiguration {

    @Bean
    public DeviceStatusQueryPort deviceStatusQueryPort(
            IoTDeviceStatusProperties properties) {
        if (!properties.isHttpMode()) {
            return new DemoDeviceStatusQueryAdapter();
        }

        properties.validateForHttp();
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new HttpDeviceStatusQueryAdapter(
                httpClient,
                new ObjectMapper(),
                properties);
    }

    @Bean
    public AgentTool deviceStatusTool(DeviceStatusQueryPort queryPort) {
        return new DeviceStatusTool(queryPort);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.iot.lure-lamp-control",
            name = "enabled",
            havingValue = "true")
    public LureLampControlPort lureLampControlPort(
            IoTDeviceStatusProperties sharedHttpProperties,
            IoTLureLampControlProperties controlProperties) {
        controlProperties.validate(sharedHttpProperties);
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(sharedHttpProperties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new HttpLureLampControlAdapter(
                httpClient,
                new ObjectMapper(),
                sharedHttpProperties,
                controlProperties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.iot.lure-lamp-control",
            name = "enabled",
            havingValue = "true")
    public AgentTool lureLampControlTool(
            DeviceStatusQueryPort deviceStatusQueryPort,
            LureLampControlPort controlPort,
            IoTLureLampControlProperties properties) {
        return new LureLampControlTool(
                deviceStatusQueryPort,
                controlPort,
                properties.getVerifyAttempts(),
                properties.getVerifyInterval());
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> tools) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }
}
