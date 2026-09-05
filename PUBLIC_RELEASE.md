# Public Release Policy

This repository is prepared for public release under the following rules.

## Data classification

The public tree must not contain:

- live API keys, tokens, passwords or private keys;
- private IP addresses or internal-only service hostnames;
- customer, tenant or employee data;
- production device IDs or production command receipts;
- production logs, database dumps or trace exports;
- proprietary source copied from an employer or customer repository.

External-system examples must use synthetic identifiers and example domains.

## Required checks

Before changing repository visibility to public:

- CI Maven tests pass;
- PowerShell scripts parse successfully;
- Gitleaks full-history scan passes;
- the current tree contains no known production identifiers or private backend paths;
- `.env` / `.env.local` are not tracked;
- dependencies and the repository license are compatible;
- documentation clearly labels integration fixtures as synthetic.

## Git history

A green Gitleaks scan is necessary but does not prove that historical non-secret business metadata is suitable for disclosure.

If earlier commits contain proprietary endpoint contracts, customer identifiers, internal logs or other business-sensitive material, do **not** make the existing repository public until history has been rewritten or a clean public repository has been created from the sanitized snapshot.

## Synthetic demo values

Public IoT examples intentionally use synthetic values such as:

```text
deviceId = 1001
productId = 2001
commandId = 9001
host = iot.example.internal
```

These values do not identify a production system.
