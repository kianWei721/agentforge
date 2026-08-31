package com.agentforge.infrastructure.rag;

import com.agentforge.rag.KnowledgeChunker;
import com.agentforge.rag.KnowledgeIngestionService;
import com.agentforge.rag.KnowledgeRepository;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.LexicalKnowledgeRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class KnowledgeRuntimeConfiguration {

    @Bean
    public KnowledgeRepository knowledgeRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeChunker knowledgeChunker() {
        return new KnowledgeChunker(800, 120);
    }

    @Bean
    public KnowledgeIngestionService knowledgeIngestionService(
            KnowledgeRepository repository, KnowledgeChunker chunker) {
        return new KnowledgeIngestionService(repository, chunker);
    }

    @Bean
    public KnowledgeRetriever knowledgeRetriever(KnowledgeRepository repository) {
        return new LexicalKnowledgeRetriever(repository);
    }
}
