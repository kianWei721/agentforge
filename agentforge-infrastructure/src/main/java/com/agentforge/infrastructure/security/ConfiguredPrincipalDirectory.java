package com.agentforge.infrastructure.security;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.core.security.PrincipalDirectory;
import java.util.Map;
import java.util.Optional;

public final class ConfiguredPrincipalDirectory implements PrincipalDirectory {

    private final Map<String, AccessPrincipal> principals;

    public ConfiguredPrincipalDirectory(Map<String, AccessPrincipal> principals) {
        this.principals = Map.copyOf(principals);
    }

    @Override
    public Optional<AccessPrincipal> resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(principals.get(userId.trim()));
    }
}
