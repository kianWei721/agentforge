package com.agentforge.rag;

import java.util.Collection;
import java.util.Map;

@FunctionalInterface
public interface KnowledgeAccessScopeLookup {

    /**
     * Current access scope of each requested document that is still enabled.
     *
     * <p>An id missing from the result means the document was deleted or disabled after the
     * search index was built, so its chunks must not be served.
     */
    Map<String, KnowledgeAccessScope> findReadableScopes(Collection<String> documentIds);
}
