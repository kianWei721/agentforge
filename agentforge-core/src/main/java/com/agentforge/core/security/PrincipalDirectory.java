package com.agentforge.core.security;

import java.util.Optional;

/** Maps an authenticated user id to its server-side principal. Unknown users resolve to empty. */
public interface PrincipalDirectory {

    Optional<AccessPrincipal> resolve(String userId);
}
