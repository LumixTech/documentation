---
title: "S3-Compatible Object Storage, Pre-Signed URLs, and Lifecycle Design"
description: Object storage architecture for upload, download, deletion, multipart upload, versioning, and lifecycle cost control.
sidebar_position: 1
---

## Introduction

Object storage is the natural fit for user-uploaded files, attachments, media, exports, reports, and generated documents. It gives the application a durable storage layer without turning the relational database into a binary file store.

The design is not only "upload file to S3." A production file flow must decide who can upload, how long URLs stay valid, how metadata is stored, how deletion works, what happens with versions, and when objects move or expire through lifecycle rules.

## Why It Matters

File handling becomes expensive and risky when it is designed late:

- large uploads can overload application servers,
- public buckets can expose private files,
- missing lifecycle rules can create silent storage cost growth,
- deleted objects can remain recoverable through versions if versioning is enabled,
- incomplete multipart uploads can accumulate cost,
- metadata in the database can drift from object storage reality.

Pre-signed URLs let clients upload or download directly through object storage while the application remains the policy decision point.

## Core Concepts

- Object storage: key-based storage for binary objects and metadata.
- S3-compatible storage: API-compatible object storage using S3-style operations.
- Pre-signed URL: time-limited URL signed by authorized credentials for a specific operation.
- Object key: logical path of the object in the bucket.
- Multipart upload: upload strategy for large files split into parts.
- Versioning: storage mode that preserves multiple object versions.
- Delete marker: versioned-bucket marker representing a logical delete.
- Lifecycle rule: policy that transitions, expires, or cleans up objects.
- Metadata row: database record that represents object ownership, state, and business context.

## Problem in Real Product Development

The application usually needs business rules that object storage does not know:

- Which tenant owns the file?
- Which user can upload or download it?
- Is this file attached to a message, payment, report, or profile?
- Is antivirus scanning required before use?
- Should the object be deleted immediately, soft-deleted, retained, or archived?
- What is the maximum allowed size and MIME type?

If the client talks to the bucket without application-issued policy, authorization moves out of the product and becomes difficult to reason about.

## Approach / Design

### Upload Flow

```text
1. Client asks API to start upload.
2. API validates tenant_id, user permission, file type, size, and target domain.
3. API creates FILE_OBJECTS metadata row with status = pending_upload.
4. API generates pre-signed PUT or multipart URLs with short expiration.
5. Client uploads directly to object storage.
6. Client notifies API that upload is complete.
7. API verifies object exists and size/hash match expected values.
8. API marks metadata as uploaded or queued_for_scan.
```

Recommended metadata fields:

- file_id
- tenant_id
- owner_user_id
- domain_type
- domain_id
- bucket
- object_key
- version_id
- content_type
- size_bytes
- checksum
- status
- retention_until
- created_at
- deleted_at

### Download Flow

```text
1. Client asks API for download access.
2. API checks tenant_id and domain permission.
3. API checks file status and retention/deletion state.
4. API creates short-lived pre-signed GET URL.
5. Client downloads directly from object storage.
6. API records audit event for sensitive file access.
```

### Delete Flow

Deletion must be explicit because object storage deletion behavior differs when versioning is enabled.

```text
delete_file(file_id):
  metadata = load_metadata(file_id)
  authorize(metadata.tenant_id, metadata.domain_type, 'delete')

  mark_metadata_deleted(file_id)

  if hard_delete_allowed(metadata):
    delete_object(bucket, object_key, version_id_if_known)
  else:
    keep_object_until_retention_expires()

  write_audit_log(action='file.delete', target=file_id)
```

### Lifecycle Policy Direction

| Object class | Lifecycle rule |
| --- | --- |
| Temporary uploads | Expire pending objects after short window |
| Incomplete multipart uploads | Abort after defined number of days |
| Attachments | Retain according to business policy |
| Exports | Expire aggressively after download window |
| Soft-deleted objects | Expire after retention period |
| Old versions | Expire noncurrent versions after recovery window |

## Sector Standard / Best Practice

- Keep buckets private and expose access through pre-signed URLs or controlled proxy endpoints.
- Use short expiration windows for pre-signed URLs.
- Validate file metadata before issuing upload URLs.
- Store business metadata in the database; store bytes in object storage.
- Use lifecycle rules for temporary files, old versions, and incomplete multipart uploads.
- Treat versioning as a recovery feature with cost and deletion implications.
- Use object keys that include environment and tenant-safe partitioning without exposing sensitive names.
- Audit access to sensitive files.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
create_upload_url(request):
  validate_user_can_upload(request.tenant_id, request.domain)
  validate_content_type(request.content_type)
  validate_size(request.size_bytes)

  file = create_file_metadata(status='pending_upload')
  object_key = build_object_key(file.tenant_id, file.file_id)

  url = object_storage.sign_put(
    bucket='private-files',
    key=object_key,
    expires_in='10m',
    content_type=request.content_type
  )

  return { file_id: file.id, upload_url: url }
```

## Our Notes / Team Decisions

- File upload and deletion lifecycles must be designed before production usage grows.
- Pre-signed URLs are preferred so application servers do not stream large files by default.
- Metadata state must remain authoritative for business decisions.
- Lifecycle rules are part of cost control and data governance.
- Sensitive file access should be connected to [Audit Log Design](../security-compliance/audit-log-design).

## External References

- AWS S3: [Sharing objects with presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ShareObjectPreSignedURL.html)
- AWS S3: [Lifecycle configuration elements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/intro-lifecycle-rules.html)

## Glossary

- Pre-signed URL: time-limited signed URL for a specific storage operation.
- Object key: unique key that identifies an object inside a bucket.
- Multipart upload: upload mechanism that splits large objects into parts.
- Lifecycle rule: object storage policy for transition, expiration, or cleanup.
- Versioning: object storage feature that keeps previous object versions.
- Delete marker: marker used in versioned buckets to represent deletion.

## Research Keywords

- `s3 presigned url lifecycle versioning object storage`
- `multipart upload pre-signed url design`
- `s3 lifecycle incomplete multipart uploads`
- `object storage metadata table design`
- `s3 versioning delete marker lifecycle`

## Conclusion

Object storage design should combine application authorization, metadata ownership, direct client transfer, and lifecycle governance. Pre-signed URLs solve the transfer problem, but metadata, deletion, retention, and cost controls make the design production-ready.
