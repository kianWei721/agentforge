package com.agentforge.infrastructure.confirmation;

import com.agentforge.infrastructure.config.ToolConfirmationProperties;
import com.agentforge.runtime.confirmation.ToolConfirmationRepository;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ToolConfirmationPersistenceConfiguration {

    @Bean
    public ToolConfirmationRepository toolConfirmationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcToolConfirmationRepository(jdbcTemplate);
    }

    @Bean
    public ToolConfirmationService toolConfirmationService(
            ToolConfirmationRepository repository,
            ToolConfirmationProperties properties) {
        properties.validate();
        return new ToolConfirmationService(repository, properties.getTtl(), Clock.systemUTC());
    }
}
