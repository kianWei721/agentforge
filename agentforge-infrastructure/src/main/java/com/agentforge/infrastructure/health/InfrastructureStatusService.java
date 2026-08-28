package com.agentforge.infrastructure.health;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InfrastructureStatusService {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public InfrastructureStatusService(
            JdbcTemplate jdbcTemplate,
            RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public InfrastructureStatus check() {
        return new InfrastructureStatus(checkMysql(), checkRedis());
    }

    private boolean checkMysql() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean checkRedis() {
        RedisConnection connection = null;
        try {
            connection = redisConnectionFactory.getConnection();
            String pong = connection.ping();
            return pong != null && "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup for a health probe.
                }
            }
        }
    }
}
