package com.agentforge.infrastructure.session;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

public class SpringRedisAgentStateStore implements AgentStateStore {

    private static final String ANONYMOUS_USER = "__anon__";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public SpringRedisAgentStateStore(
            StringRedisTemplate redisTemplate, String keyPrefix, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String redisKey = stateKey(userId, sessionId, key);
        String markerKey = markerKey(userId, sessionId);
        String indexKey = userIndexKey(userId);

        String json = JsonUtils.getJsonCodec().toJson(value);
        redisTemplate.opsForValue().set(redisKey, json, ttl);
        redisTemplate.opsForSet().add(markerKey, redisKey);
        redisTemplate.expire(markerKey, ttl);
        redisTemplate.opsForSet().add(indexKey, requireSessionId(sessionId));
    }

    @Override
    public void save(
            String userId, String sessionId, String key, List<? extends State> values) {
        String redisKey = listKey(userId, sessionId, key);
        String markerKey = markerKey(userId, sessionId);
        String indexKey = userIndexKey(userId);

        redisTemplate.delete(redisKey);
        if (values != null && !values.isEmpty()) {
            List<String> jsonValues =
                    values.stream().map(value -> JsonUtils.getJsonCodec().toJson(value)).toList();
            redisTemplate.opsForList().rightPushAll(redisKey, jsonValues);
            redisTemplate.expire(redisKey, ttl);
        } else {
            redisTemplate.opsForValue().set(redisKey, "[]", ttl);
        }

        redisTemplate.opsForSet().add(markerKey, redisKey);
        redisTemplate.expire(markerKey, ttl);
        redisTemplate.opsForSet().add(indexKey, requireSessionId(sessionId));
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(stateKey(userId, sessionId, key));
        if (json == null) {
            return Optional.empty();
        }
        touch(userId, sessionId);
        return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String redisKey = listKey(userId, sessionId, key);
        List<String> jsonValues = redisTemplate.opsForList().range(redisKey, 0, -1);
        if (jsonValues == null || jsonValues.isEmpty()) {
            return List.of();
        }

        touch(userId, sessionId);
        List<T> result = new ArrayList<>(jsonValues.size());
        for (String json : jsonValues) {
            result.add(JsonUtils.getJsonCodec().fromJson(json, itemType));
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(markerKey(userId, sessionId)));
    }

    @Override
    public void delete(String userId, String sessionId) {
        String markerKey = markerKey(userId, sessionId);
        Set<String> trackedKeys = redisTemplate.opsForSet().members(markerKey);
        if (trackedKeys != null && !trackedKeys.isEmpty()) {
            redisTemplate.delete(trackedKeys);
        }
        redisTemplate.delete(markerKey);
        redisTemplate.opsForSet().remove(userIndexKey(userId), requireSessionId(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        String stateKey = stateKey(userId, sessionId, key);
        String listKey = listKey(userId, sessionId, key);
        redisTemplate.delete(List.of(stateKey, listKey));
        redisTemplate.opsForSet().remove(markerKey(userId, sessionId), stateKey, listKey);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String indexKey = userIndexKey(userId);
        Set<String> candidates = redisTemplate.opsForSet().members(indexKey);
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }

        Set<String> live = new HashSet<>();
        Set<String> stale = new HashSet<>();
        for (String sessionId : candidates) {
            if (exists(userId, sessionId)) {
                live.add(sessionId);
            } else {
                stale.add(sessionId);
            }
        }
        if (!stale.isEmpty()) {
            redisTemplate.opsForSet().remove(indexKey, stale.toArray());
        }
        return live;
    }

    private void touch(String userId, String sessionId) {
        String markerKey = markerKey(userId, sessionId);
        redisTemplate.expire(markerKey, ttl);
        Set<String> trackedKeys = redisTemplate.opsForSet().members(markerKey);
        if (trackedKeys != null) {
            trackedKeys.forEach(key -> redisTemplate.expire(key, ttl));
        }
    }

    private String stateKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + ":state:" + encode(requireKey(key));
    }

    private String listKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + ":list:" + encode(requireKey(key));
    }

    private String markerKey(String userId, String sessionId) {
        return sessionPrefix(userId, sessionId) + ":_keys";
    }

    private String userIndexKey(String userId) {
        return keyPrefix + "index:" + encode(normalizeUser(userId));
    }

    private String sessionPrefix(String userId, String sessionId) {
        return keyPrefix
                + "u:"
                + encode(normalizeUser(userId))
                + ":s:"
                + requireSessionId(sessionId);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANONYMOUS_USER : userId.trim();
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId.trim();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("state key must not be blank");
        }
        return key.trim();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
