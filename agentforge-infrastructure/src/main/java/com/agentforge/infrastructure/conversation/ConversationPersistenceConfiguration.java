package com.agentforge.infrastructure.conversation;

import com.agentforge.conversation.ConversationRepository;
import com.agentforge.conversation.ConversationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ConversationPersistenceConfiguration {

    @Bean
    public ConversationRepository conversationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcConversationRepository(jdbcTemplate);
    }

    @Bean
    public ConversationService conversationService(ConversationRepository repository) {
        return new ConversationService(repository);
    }
}
