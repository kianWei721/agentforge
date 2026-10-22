package com.agentforge.rag;

/** Document audience. Chunks inherit it from their document. */
public enum KnowledgeVisibility {
    /** Staff only. Also the migration default for documents created before ACLs existed. */
    INTERNAL,
    /** Every registered principal, staff and customers alike. */
    PUBLIC,
    /** Staff plus the one customer named by the document's customerId. */
    CUSTOMER
}
