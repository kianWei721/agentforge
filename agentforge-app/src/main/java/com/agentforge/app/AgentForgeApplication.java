package com.agentforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.agentforge")
public class AgentForgeApplication {
    public static void main(String[] args) { SpringApplication.run(AgentForgeApplication.class, args); }
}
