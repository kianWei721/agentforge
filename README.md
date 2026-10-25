# AgentForge

AgentForge is a Java-based experimental Agent runtime and governance project focused on production-oriented engineering: durable execution, tool governance, retrieval evaluation, safe promotion, rollback, and enterprise-system integration.

> Status: V0.13 — agent turns through the model gateway (per agent); WeCom channel with session isolation verified at ten thousand groups and intent routing (answer, hand off to a person, refuse), on top of retrieval-layer access control (V0.8) and model calls through a standalone model gateway (V0.9). The project is under active development and is not presented as a drop-in production platform.

## Why AgentForge

The project explores the boundary between a general Agent framework and the controls required when an Agent is connected to real enterprise systems.

Key areas:

- durable Conversation / Execution / Trace persistence;
- Tool Calling with risk classification and policy decisions;
- Human-in-the-loop confirmation for high-risk write tools;
- retry / failure classification and repeated-call suppression;
- execution-scoped Retrieval Budget;
- Hybrid RAG with BM25 + dense vector + RRF + reranking + answerability admission;
- isolated Gold Dataset evaluation and production promotion gates;
- explicit runtime smoke verification and rollback state.

## Architecture

```text
HTTP / SSE
  -> Agent Runtime
  -> Conversation + Context Restore
  -> Execution
  -> Model / Tool reasoning
       -> Tool Governance
       -> External IoT Adapter (optional)
       -> Knowledge Retrieval
  -> Assistant Message
  -> Durable Trace
  -> terminal Execution
  -> durable done
```

The codebase is a modular monolith. MySQL stores durable application facts, Redis stores hot runtime state, and Elasticsearch is used as a rebuildable retrieval index.

## Modules

```text
agentforge-core
agentforge-model
agentforge-agent
agentforge-tool
agentforge-conversation
agentforge-runtime
agentforge-channel
agentforge-rag
agentforge-workflow
agentforge-observability
agentforge-infrastructure
agentforge-app
```

## Tool Governance

The runtime records machine-readable policy and governance semantics rather than relying on model wording.

Examples:

```text
riskLevel
readOnly
policyDecision
confirmationStatus
failureCode
retryable
governanceDecision
outboundAttempts
outboundOutcome
```

A restricted demo write tool is disabled by default. Its identifiers, device IDs, backend paths, command IDs, timestamps, and payloads in this repository are synthetic examples only.

## Production RAG

The current retrieval pipeline is:

```text
BM25 + Vector
      ↓
RRF
      ↓
Reranker
      ↓
Answerability Judge
      ↓
Knowledge / No-answer
```

V0.7 includes an isolated evaluation corpus, candidate-stage benchmarking, RRF parameter sweeps, query-strategy comparison, end-to-end promotion checks, targeted stability rechecks, production smoke tests, and rollback tooling.

The accepted runtime promotion target for Slice 1B-4 is:

```text
Query Strategy = ORIGINAL
Query Rewrite   = OFF
RRF k           = 5
```

The repository may still retain historical source defaults until the separate source-baseline codification change is merged. Historical evaluation references to `REWRITE + k=60` describe the legacy control, not the target production configuration.

## Retrieval Access Control

The same knowledge base serves staff and customers with different boundaries. The boundary is
enforced in retrieval and data access, never by prompt wording:

```text
trusted identity header -> server-side PrincipalDirectory
  -> backend pre-filter (ES knn.filter / bool.filter, SQL WHERE before LIMIT)
  -> guard re-checks MySQL (enabled + ACL) before RRF, so rerank / judge LLMs never see unreadable chunks
  -> documents, traces and sessions apply the same policy or owner checks
```

Document visibility is `INTERNAL`, `PUBLIC`, or `CUSTOMER` + `customerId`. Agent tools resolve identity
from the runtime context; the tool schema has no identity field. See
[docs/V0.8-retrieval-access-control.md](docs/V0.8-retrieval-access-control.md) for the threat model,
trade-offs, tests, and known limits.

## Model Gateway Client

Optional. With `agentforge.model-gateway.enabled=true`, knowledge query rewrite and answerability
calls go through the standalone [model-gateway](https://github.com/kianWei721/model-gateway), with no
silent fallback to calling DashScope directly. `agentforge.model.provider=gateway` routes chat there too.

```text
one search -> correlation id kr-<uuid> -> rewrite + judge on route rag-json
  -> standard OpenAI fields only, explicit max_tokens, X-Gateway-Deadline-Ms
  -> retry decided by the gateway's error code, not the HTTP status
  -> modelCallDiagnostics: gateway request id + the model that actually served each call
```

An agent goes through the gateway too when its definition says `modelProvider=gateway` and names
the route as its model (`agent`): tool calls, history and compaction, non-streamed, every call of
a turn under one correlation id `ag-<executionId>`, and no fallback to calling the vendor. In a
real smoke run, 11 of 11 agent turns passed through the gateway as they did direct. Embedding and
rerank still call DashScope directly. See
[docs/V0.9-model-gateway-client.md](docs/V0.9-model-gateway-client.md) and
[docs/V0.13-agent-through-gateway.md](docs/V0.13-agent-through-gateway.md).

## WeCom Channel

Optional. A WeCom smart bot (HTTP callback at `/callbacks/wecom/bot`) answers through a dedicated
agent, with sessions isolated per person and per group:

```text
signed + encrypted callback -> msgid dedup (unique key) -> server-derived session key
  -> single chat runs as the mapped user; a group runs as its bound wecom-group:* user,
     because everyone in the group reads the answer
  -> same session strictly serial, different sessions in parallel -> pulled stream reply
```

Staff groups are refused by default, and the channel agent may only use allow-listed read-only
tools. See [docs/V0.10-wecom-channel.md](docs/V0.10-wecom-channel.md).

At ten thousand groups: 50,000 simulated turns with no answer containing another group's history;
compaction bounded and nothing written to local disk; a message estimated (from measured turn
durations, with a margin) to finish after WeCom stops pulling is refused when it arrives, and a
group's follow-ups run right after its own turns; the agent runtime is replaced every 5000
turns so its per-session caches stay bounded. See
[docs/V0.11-wecom-scale-hardening.md](docs/V0.11-wecom-scale-hardening.md).

Optional intent routing sends each message to one of three places: the channel agent answers it,
a person is needed (fixed reply, recorded as HANDOFF), or it is out of scope (fixed reply).
Phrases decide on arrival; a classifier through the model gateway decides the rest, and anything
unclear or failed goes to the agent. Each decision is recorded with its source, confidence and
gateway request id. On a labelled set through the real gateway: 93.7% on the test split. See
[docs/V0.12-intent-routing.md](docs/V0.12-intent-routing.md).

## External IoT Integration

The HTTP adapter is intentionally configurable. Public defaults use synthetic endpoints:

```text
/api/iot/devices/{deviceId}
/api/iot/devices/{deviceId}/details
/api/iot/devices/{deviceId}/events/history
/api/iot/devices/{deviceId}/properties/latest
/api/iot/device-commands
```

Credentials are read only from local/deployment configuration and must never be committed. The default mode is `demo`.

The public repository does not contain production credentials, private service URLs, customer identifiers, or production device identifiers. Historical integration notes have been replaced by synthetic examples.

## Development

Requirements:

- JDK 17+
- Maven
- MySQL
- Redis
- optional Elasticsearch for Hybrid RAG
- optional DashScope credentials for real-model tests

Initialize local configuration:

```powershell
.\scripts\setup-dev.ps1
```

Configure MySQL / Redis:

```powershell
.\scripts\configure-infra.ps1
```

Run tests:

```powershell
.\scripts\mvn.ps1 clean test
```

Run with the configured model provider:

```powershell
.\scripts\run-dashscope.ps1
```

See `.env.example` for non-secret configuration examples.

## Security

Do not commit:

- API keys or access tokens;
- database / Redis / Elasticsearch passwords;
- internal hostnames or private IP addresses;
- customer or tenant data;
- production device IDs;
- production logs or trace exports.

See [SECURITY.md](SECURITY.md) and [PUBLIC_RELEASE.md](PUBLIC_RELEASE.md).

## Documentation

Architecture and milestone documents live under `docs/`. Documents that describe external-system integration use synthetic values and are intended to demonstrate engineering decisions rather than expose any production system contract.

## License

Apache License 2.0. See [LICENSE](LICENSE).
