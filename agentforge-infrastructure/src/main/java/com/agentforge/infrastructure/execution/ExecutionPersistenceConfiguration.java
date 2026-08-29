package com.agentforge.infrastructure.execution;

import com.agentforge.observability.execution.ExecutionRepository;
import com.agentforge.observability.execution.ExecutionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ExecutionPersistenceConfiguration {

    @Bean
    public ExecutionRepository executionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcExecutionRepository(jdbcTemplate);
    }

    @Bean
    public ExecutionService executionService(ExecutionRepository repository) {
        return new ExecutionService(repository);
    }
}
