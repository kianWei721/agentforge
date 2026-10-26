package com.agentforge.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.infrastructure.model.AgentAnswerEvaluationTest.Case;
import com.agentforge.infrastructure.model.AgentAnswerEvaluationTest.Outcome;
import com.agentforge.infrastructure.model.AgentAnswerEvaluationTest.Turn;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.rag.LexicalKnowledgeRetriever;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The answer-quality set and its grader, without a model: runs in every build. */
class AgentAnswerEvaluationDataTest {

    @Test
    void theSetIsWellFormed() throws Exception {
        List<Case> cases = AgentAnswerEvaluationTest.cases();
        assertEquals(40, cases.size());
        Set<String> ids = new HashSet<>();
        for (Case c : cases) {
            assertTrue(ids.add(c.id()), "duplicate " + c.id());
            assertTrue(Set.of("dev", "test").contains(c.split()), c.id());
            assertFalse(c.turns().isEmpty(), c.id());
            for (Turn t : c.turns()) {
                assertFalse(t.message().isBlank(), c.id());
                t.must().forEach(group -> assertFalse(group.isEmpty(), c.id()));
                if (t.notFound()) {
                    assertTrue(t.must().isEmpty(), c.id() + ": an unanswerable turn requires no facts");
                }
            }
        }
        Map<String, Long> categories = cases.stream().collect(Collectors.groupingBy(Case::category, Collectors.counting()));
        assertEquals(
                Map.of("answerable", 17L, "cross_document", 4L, "not_in_knowledge", 6L, "access_control", 6L,
                        "follow_up", 4L, "chit_chat", 2L, "injection", 1L),
                categories);
    }

    /** Whatever the agent searches for, the group never gets another customer's or staff chunks. */
    @Test
    void retrievalAsTheGroupNeverReturnsOtherCustomersOrInternalDocuments() throws Exception {
        LexicalKnowledgeRetriever retriever =
                new LexicalKnowledgeRetriever(AgentAnswerEvaluationTest.InMemoryKnowledge.load());
        AccessPrincipal group = AccessPrincipal.customer(AgentAnswerEvaluationTest.GROUP, AgentAnswerEvaluationTest.CUSTOMER);
        for (String query : List.of("内部报价底线", "退款审批 财务总监", "贝塔 欠费 李会计", "销售总监审批 一般纳税人 450")) {
            List<String> ids = retriever.search(query, 8, group).stream().map(KnowledgeSearchHit::documentId).toList();
            assertTrue(ids.stream().noneMatch(id -> id.startsWith("i") || id.equals("c03")), query + " -> " + ids);
        }
        // The group's own documents are there.
        assertTrue(retriever.search("专属会计", 8, group).stream().anyMatch(hit -> hit.documentId().equals("c01")));
        // Staff see everything.
        assertTrue(retriever.search("内部报价底线", 8, AccessPrincipal.staff("kefu-1")).stream()
                .anyMatch(hit -> hit.documentId().equals("i01")));
    }

    @Test
    void theGraderChecksFactsForbiddenWordsSearchesAndUnansweredQuestions() {
        Turn answerable = new Turn("q", true, List.of(List.of("1 个工作日", "一个工作日")), List.of("李会计"), false);
        assertEquals(List.of(), failures(answerable, List.of("开票"), "开具后**1个工作日**内发到群里"));
        assertEquals(List.of("no search", "missing 1 个工作日"), failures(answerable, List.of(), "很快"));
        assertEquals(List.of("forbidden 李会计"), failures(answerable, List.of("q"), "一个工作日，李会计负责"));

        Turn unanswerable = new Turn("q", true, List.of(), List.of("100%"), true);
        assertEquals(List.of(), failures(unanswerable, List.of("q"), "知识库里没有找到相关内容，建议联系客服同事。"));
        assertEquals(List.of("forbidden 100%", "not said to be unanswered"),
                failures(unanswerable, List.of("q"), "加计扣除比例是 100%。"));

        Turn chat = new Turn("你好", false, List.of(), List.of(), false);
        assertEquals(List.of("searched"), failures(chat, List.of("你好"), "你好！"));
    }

    @Test
    void wilsonIntervalMatchesTheKnownValue() {
        double[] ci = AgentAnswerEvaluationTest.wilson(194, 207);
        assertEquals(0.896, ci[0], 0.001);
        assertEquals(0.963, ci[1], 0.001);
    }

    private static List<String> failures(Turn turn, List<String> searches, String answer) {
        return AgentAnswerEvaluationTest.failures(turn, new Outcome(1, "c", 0, searches, List.of(), answer, null, 1));
    }
}
