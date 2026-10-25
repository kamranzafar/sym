# sym — Speak Your Mind

A small Twitter-like microservice: post a short message over a REST API, give it an image, read it back, and follow
every message on a Kafka stream. This service uses [Halyx API](https://halyx.dev), for cloud portability, and runs unchanged on **AWS**, **Azure** and **Google Cloud**. A Spring
profile picks which.

sym uses [Halyx](https://halyx.dev) as its only cloud API. There's no AWS, Azure or Google Cloud SDK in its code,
and no cloud name, endpoint or ARN either.

| | AWS | Azure | Google Cloud | `local` |
|---|---|---|---|---|
| Messages and the hashtag index | DynamoDB | Cosmos DB | Firestore | in memory |
| Message images | S3 | Blob Storage | Cloud Storage | in memory |
| The `message-posted` stream | Amazon MSK (IAM) | Event Hubs, Kafka endpoint (Entra ID) | Managed Service for Apache Kafka (Google IAM) | in memory |
| Cache (optional `cache` profile) | ElastiCache (IAM) | Azure Managed Redis (Entra ID) | Memorystore (IAM, TLS) | in memory |

## The API

| Request | Does |
|---|---|
| `POST /users/{author}/messages` with `{"text": "…"}` | posts a message (1–280 characters) and returns `201` with its `Location` |
| `GET /users/{author}/messages/{messageId}` | one message, or `404` |
| `GET /users/{author}/messages?limit=20` | an author's messages, newest first |
| `GET /hashtags/{tag}/messages?limit=20` | the messages with `#tag`, newest first |
| `PUT /users/{author}/messages/{messageId}/image` with the image as the body | gives the message an image, replacing any it had; returns the message |
| `GET /users/{author}/messages/{messageId}/image` | the image, with its type, or `404` |
| `DELETE /users/{author}/messages/{messageId}/image` | removes the image; `204` whether or not there was one |

Handles are 1–30 lowercase letters, digits or underscores. Invalid requests get a `400` with a `ProblemDetail`.

An image is a PNG, JPEG, GIF or WebP of at most 1 MB, sent with its type as the `Content-Type`. sym checks that its
bytes start the way that type's files do. A message with an image shows its path as `image`. Anything else gets a
`400`, a `413` when it's too large, or a `415` for another type.

## How it works

```
POST ─▶ MessageService ─┬─▶ messages (document store)
                        └─▶ message-posted (Kafka stream, keyed by author)
                                    │
                                    ▼
                        HashtagIndexer (consumer group sym-hashtag-indexer)
                                    │
                                    ▼
                        hashtags (document store) ◀── GET /hashtags/{tag}/messages
```

- **`MessageService`** stores the message, then sends it to the stream, keyed by author so each author's messages
  stay in order.
- **`HashtagIndexer`** is a consumer of that stream, in its own consumer group. It files each message under its
  hashtags. Any other service can read the same stream in a group of its own.
- **Message ids** are fixed-width milliseconds plus a random suffix, so a timeline reads newest first by sort key,
  with no index.
- **Redeliveries do no harm:** the indexer writes by key, so handling a record twice writes the same entry twice.
- **Images** are in the `images` blob store under `images/<author>/<messageId>`, and the message records the image's
  type. An image is stored before the message points at it, and the message stops pointing at it before it's deleted,
  so a failure leaves at most an image nothing refers to, never a message whose image is missing. On the `cache`
  profile, attaching or removing an image evicts the cached message.

## Run it with no cloud

```bash
mvn package
java -jar target/sym-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

curl -X POST localhost:8080/users/ada/messages -H 'Content-Type: application/json' \
     -d '{"text": "Portable by configuration #halyx"}'
curl localhost:8080/users/ada/messages
curl localhost:8080/hashtags/halyx/messages
curl -X PUT localhost:8080/users/ada/messages/<messageId>/image -H 'Content-Type: image/png' --data-binary @photo.png
curl -o image.png localhost:8080/users/ada/messages/<messageId>/image
```

Add `cache` to the profiles (`local,cache`) to cache message lookups in memory too.

## Run it on a cloud

Pick the cloud's profile, add `cache` if you want it, and set what only your deployment knows. Credentials come
from the cloud's default chain, so nothing else is configured: workload identity on EKS, GKE and AKS, an IAM role, a
managed identity, or your `gcloud` or `az` login.

| Variable | AWS | Azure | Google Cloud |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `aws` | `azure` | `gcp` |
| `SYM_KAFKA_BOOTSTRAP_SERVERS` | MSK's IAM bootstrap servers (port 9098) | `<namespace>.servicebus.windows.net:9093` | the cluster's bootstrap address |
| Provider | `AWS_REGION` (default `eu-west-2`) | `SYM_COSMOS_ENDPOINT`, `SYM_STORAGE_ACCOUNT` | `GOOGLE_CLOUD_PROJECT` |
| With `cache` | `SYM_CACHE_ENDPOINT`, and `SYM_CACHE_USER`, `SYM_CACHE_NAME` (default `sym`) | `SYM_CACHE_ENDPOINT`, `SYM_CACHE_USER` (the identity's object id) | `SYM_CACHE_ENDPOINT` (`rediss://`), and the instance's CA as `halyx.cache-manager.properties.redis.ca-certificate` |

The resources, with sym's default names (each overridable with a `SYM_*` variable, see `application.yml`):

- **Tables:** `sym-messages` and `sym-hashtags`.
  - DynamoDB tables with string `pk` and `sk` keys;
  - Cosmos DB containers partitioned on `/pk`, in database `sym`;
  - Firestore needs nothing.
- **Bucket:** `sym-images`, a blob container in the storage account on Azure.
- **Topic:** `sym-message-posted`, an event hub on Azure.
- **Consumer group:** `sym-hashtag-indexer`.

## Tests

```bash
mvn verify
```

| Test | Runs the API suite |
|---|---|
| `LocalDeploymentTest`, `LocalCacheDeploymentTest` | in memory, without and with the cache |
| `AwsEmulatorIT` | on the `aws` profile against Floci (DynamoDB, S3), Kafka and Valkey |
| `AzureEmulatorIT` | on the `azure` profile against Floci-az (Cosmos DB, Blob Storage), Kafka and Valkey |
| `GcpEmulatorIT` | on the `gcp` profile against Floci-gcp (Firestore, Cloud Storage), Kafka and Valkey |
| `PackagedJarIT` | against the built jar |

`ProfilesTest` checks each cloud profile as written, without connecting. `HashtagIndexerTest` checks that a
redelivered record does no harm.

The emulator tests need Docker; without it they're skipped.

## What it doesn't do

- **No outbox:** a message is stored, then streamed. If the stream is down after the store, the message is kept but
  not streamed, and the caller gets a `503`. Retrying posts it again under a new id.
- **No authentication:** the author comes from the URL. A real service would take it from a login.
- **Hashtag results don't show images:** the index is written when a message is posted, before an image can be
  attached. The message itself, and its author's timeline, do.
- **Images go through sym**, not to the bucket directly with presigned URLs, so each one is at most 1 MB.
- **No deployment pieces:** no Helm chart, IAM policies or infrastructure code.
- **Not yet run on a real cloud:** the emulators stand in for each cloud's services.
