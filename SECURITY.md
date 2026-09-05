# Security Policy

## Reporting a vulnerability

Please do not open a public issue for suspected vulnerabilities, exposed credentials, or private infrastructure details.

Use GitHub Security Advisories / private vulnerability reporting for this repository when available. If private reporting is unavailable, contact the repository owner through GitHub before sharing sensitive details.

Include only the minimum information needed to reproduce the issue. Never paste live API keys, access tokens, passwords, customer data, production device identifiers, or private network addresses into an issue.

## Secrets

AgentForge treats credentials as deployment-time configuration. Files such as `.env`, `.env.local`, local application overrides, private keys, keystores, logs and trace exports are excluded from version control.

If a credential is accidentally committed, rotate or revoke it immediately. Removing the value from the latest revision is not sufficient because Git history may retain it.
