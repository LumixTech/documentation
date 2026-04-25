---
title: "Secrets Management, KMS, and Envelope Encryption"
description: Secure handling of application secrets, database passwords, KMS-backed envelope encryption, rotation, and access control.
sidebar_position: 7
---

## Introduction

Secrets management is the discipline of storing, distributing, rotating, and auditing sensitive values such as API keys, database passwords, signing keys, encryption keys, and service credentials.

Putting a secret into an environment variable can be convenient, but it is not a complete security model. A production system also needs rotation, access control, auditability, encryption at rest, limited blast radius, and clear ownership.

This document defines how application secrets, database credentials, and field encryption keys should flow through the system.

## Why It Matters

Secrets become high-impact risks because they often unlock many other systems.

If secrets are handled casually:

- a leaked database password can expose tenant data,
- a long-lived API key can survive employee or service lifecycle changes,
- a field encryption key can make encrypted personal data recoverable by the wrong actor,
- rotation becomes an incident response panic instead of a routine operation.

Good secrets management is not only about hiding values. It is about making access intentional, traceable, and replaceable.

## Core Concepts

- Secret: sensitive runtime value used by an application or service.
- KMS: key management service used to create, protect, rotate, and audit cryptographic keys.
- Envelope encryption: data is encrypted with a data encryption key, and that key is encrypted with a master key managed by KMS.
- Data encryption key: short-scope key used to encrypt application data or fields.
- Key encryption key: KMS-managed key used to protect data encryption keys.
- External secret: secret sourced from a dedicated secret manager and synchronized into runtime infrastructure.
- Rotation: planned replacement of a secret or key without breaking service continuity.
- Least privilege: each workload receives only the secrets it needs.

## Problem in Real Product Development

The common shortcut is:

```text
put DB_PASSWORD into ENV and move on
```

This solves only injection into the process. It does not answer:

- Who can read the secret before it reaches the application?
- Can we rotate it without downtime?
- Is access logged?
- Can staging accidentally use production credentials?
- Are field encryption keys separated from application config?
- What happens if a pod, CI job, or developer laptop leaks the value?

As the product grows, secrets also spread across CI/CD, Kubernetes, local development, background workers, scheduled jobs, and third-party integrations. Without a standard flow, the same secret appears in too many places.

## Approach / Design

### Secret Classes

| Class | Examples | Storage Direction | Rotation Priority |
| --- | --- | --- | --- |
| Application config secrets | SMTP password, third-party API token | Secret manager, injected at runtime | Medium |
| Database credentials | app user password, migration user password | Secret manager, separate per environment | High |
| Signing material | JWT signing key, webhook signing key | KMS/HSM or secret manager with strong access control | High |
| Field encryption keys | PII encryption data key, per-tenant DEK | Envelope encryption with KMS | Critical |
| CI/CD credentials | deploy token, registry token | CI secret store with scoped permissions | High |

### Runtime Flow

```text
Secret Manager / KMS
  -> External Secrets Controller or runtime secret injector
  -> Kubernetes Secret or mounted file
  -> Application process
  -> Access logged through platform controls
```

For high-risk keys, prefer mounted files or SDK access over broad environment variable exposure where the runtime platform supports it.

### Field Encryption Key Flow

```text
1. Application needs to encrypt a sensitive field.
2. Application asks KMS or key service for a data encryption key.
3. Plaintext DEK encrypts the field in application memory.
4. KMS-encrypted DEK is stored beside the encrypted field or in key metadata.
5. Plaintext DEK is discarded from memory as soon as practical.
6. Decryption requires access to both encrypted data and KMS decrypt permission.
```

This separates database compromise from key compromise. The database stores ciphertext and encrypted key material, while KMS access remains governed separately.

### Rotation Strategy

Secret rotation should be designed as a lifecycle:

```text
create new version
deploy consumers that can read new version
switch active version
monitor errors
retire old version after grace period
record audit event
```

For database passwords, use dual credentials or staged user replacement where possible:

```text
app_user_v1 active
create app_user_v2
grant required privileges
deploy app using app_user_v2
verify traffic
revoke app_user_v1
drop app_user_v1
```

## Sector Standard / Best Practice

- Store secrets in a dedicated secret manager, not in Git, images, or static config files.
- Use KMS for master keys and envelope encryption for field-level sensitive data.
- Scope secrets by environment, service, tenant sensitivity, and operational role.
- Separate application runtime secrets from migration/admin credentials.
- Rotate secrets on a schedule and immediately after suspected exposure.
- Audit secret read/decrypt operations.
- Keep encrypted data recoverable through a documented key version strategy.
- Use Kubernetes encryption at rest for cluster secret storage and KMS integration where available.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
request_secret(service, secret_name, environment):
  if service is not allowed to access secret_name:
    deny_and_audit(service, secret_name)

  if environment_mismatch(service, secret_name):
    deny_and_audit(service, secret_name)

  value = secret_manager.read(secret_name, version='active')
  audit_secret_access(service, secret_name, version='active')
  return value
```

Envelope encryption flow:

```text
encrypt_sensitive_field(tenant_id, field_value):
  dek = kms.generate_data_key(key_id='field-encryption-key')
  ciphertext = encrypt(field_value, dek.plaintext)

  store({
    tenant_id,
    ciphertext,
    encrypted_dek: dek.ciphertext,
    key_version: dek.key_version
  })

  wipe(dek.plaintext)
```

## Our Notes / Team Decisions

- Secret values must not be treated as safe just because they are injected through environment variables.
- Application secrets, database passwords, and field encryption key flows need separate handling.
- Rotation and access control are part of the design, not later operational tasks.
- Field encryption should use envelope encryption so the database does not become the only security boundary.
- Secret access should be connected to [Audit Log Design](./audit-log-design) for critical reads, rotations, and emergency access.

## External References

- Kubernetes: [Encrypting Confidential Data at Rest](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/)

## Glossary

- Secret: confidential runtime value required by software.
- KMS: managed system for cryptographic key lifecycle and access control.
- Envelope encryption: encryption pattern where data keys are protected by a master key.
- Data encryption key: key used to encrypt data directly.
- Key encryption key: key used to encrypt another key.
- Rotation: controlled replacement of a secret or cryptographic key.
- External secret: secret synchronized from a secret manager into runtime infrastructure.

## Research Keywords

- `secret management kms envelope encryption`
- `key rotation strategy application secrets`
- `external secrets kubernetes secret manager`
- `database password rotation zero downtime`
- `field level encryption envelope encryption`
- `kms data encryption key key encryption key`

## Conclusion

Secrets management is a lifecycle, not a storage location. A production design must cover where secrets live, who can access them, how they rotate, how access is audited, and how encrypted data remains recoverable without widening the blast radius.
