package com.agentforge.infrastructure.security;

import com.agentforge.core.security.PrincipalDirectory;
import com.agentforge.infrastructure.config.PrincipalDirectoryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfiguration {

    @Bean
    public PrincipalDirectory principalDirectory(PrincipalDirectoryProperties properties) {
        return new ConfiguredPrincipalDirectory(properties.toPrincipals());
    }
}
