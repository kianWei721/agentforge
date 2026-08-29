package com.agentforge.infrastructure.conversation;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationSession;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcConversationRepositoryTest {

    private JdbcConversationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:conversation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_message");
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_session");

        jdbcTemplate.execute(
                """
                CREATE TABLE af_session (
                    id VARCHAR(64) PRIMARY KEY,
                    agent_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_key VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    next_sequence BIGINT NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    last_message_at TIMESTAMP(6),
                    UNIQUE (agent_id, user_id, session_key)
                )
                """);

        jdbcTemplate.execute(
                """
                CREATE TABLE af_message (
                    id VARCHAR(64) PRIMARY KEY,
                    session_id VARCHAR(64) NOT NULL,
                    sequence_no BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content CLOB NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    UNIQUE (session_id, sequence_no),
                    FOREIGN KEY (session_id) REFERENCES af_session(id) ON DELETE CASCADE
                )
                """);

        repository = new JdbcConversationRepository(jdbcTemplate);
    }

    @Test
    void shouldReuseNaturalSessionIdentityAndPersistOrderedMessages() {
        ConversationSession first =
                repository.getOrCreateSession("default", "user-1", "session-1");
        ConversationSession second =
                repository.getOrCreateSession("default", "user-1", "session-1");

        assertEquals(first.id(), second.id());

        ConversationMessage user =
                repository.appendMessage(
                        first.id(), ConversationRole.USER, "hello", "api");
        ConversationMessage assistant =
                repository.appendMessage(
                        first.id(), ConversationRole.ASSISTANT, "hi", "agentscope-harness");

        assertEquals(1L, user.sequenceNo());
        assertEquals(2L, assistant.sequenceNo());

        List<ConversationMessage> messages = repository.findMessages(first.id());
        assertEquals(2, messages.size());
        assertEquals(ConversationRole.USER, messages.get(0).role());
        assertEquals("hello", messages.get(0).content());
        assertEquals(ConversationRole.ASSISTANT, messages.get(1).role());
        assertEquals("hi", messages.get(1).content());

        ConversationSession reloaded =
                repository.findSession("default", "user-1", "session-1").orElseThrow();
        assertNotNull(reloaded.lastMessageAt());
    }

    @Test
    void shouldIsolateSameSessionKeyAcrossUsersAndAgents() {
        ConversationSession a =
                repository.getOrCreateSession("agent-a", "user-1", "same");
        ConversationSession b =
                repository.getOrCreateSession("agent-a", "user-2", "same");
        ConversationSession c =
                repository.getOrCreateSession("agent-b", "user-1", "same");

        assertNotEquals(a.id(), b.id());
        assertNotEquals(a.id(), c.id());
        assertEquals(2, repository.findSessions("agent-a", "user-1").size() + 1);
    }
}
