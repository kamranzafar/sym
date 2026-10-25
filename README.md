# sym — Speak Your Mind

A small Twitter-like microservice: post a short message over a REST API, read it back, and follow every message on
a Kafka stream. It runs unchanged on **AWS**, **Azure** and **Google Cloud**, or with no cloud at all. A Spring
profile picks which.

sym uses [Halyx](https://halyx.dev) as its only cloud API. There's no AWS, Azure or Google Cloud SDK in its code,
and no cloud name, endpoint or ARN either.

| | AWS | Azure | Google Cloud | `local` |
|---|---|---|---|---|
| Messages and the hashtag index | DynamoDB | Cosmos DB | Firestore | in memory |
| The `message-posted` stream | Amazon MSK (IAM) | Event Hubs, Kafka endpoint (Entra ID) | Managed Service for Apache Kafka (Google IAM) | in memory |
| Cache (optional `cache` profile) | ElastiCache (IAM) | Azure Managed Redis (Entra ID) | Memorystore (IAM, TLS) | in memory |

## The API

| Request | Does |
|---|---|
| `POST /users/{author}/messages` with `{"text": "…"}` | posts a message (1–280 characters) and returns `201` with its `Location` |
| `GET /users/{author}/messages/{messageId}` | one message, or `404` |
| `GET /users/{author}/messages?limit=20` | an author's messages, newest first |
| `GET /hashtags/{tag}/messages?limit=20` | the messages with `#tag`, newest first |

Handles are 1–30 lowercase letters, digits or underscores. Invalid requests get a `400` with a `ProblemDetail`.

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

## Run it with no cloud

```bash
mvn package
java -jar target/sym-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

curl -X POST localhost:8080/users/ada/messages -H 'Content-Type: application/json' \
     -d '{"text": "Portable by configuration #halyx"}'
curl localhost:8080/users/ada/messages
curl localhost:8080/hashtags/halyx/messages
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
| Provider | `AWS_REGION` (default `eu-west-2`) | `SYM_COSMOS_ENDPOINT` | `GOOGLE_CLOUD_PROJECT` |
| With `cache` | `SYM_CACHE_ENDPOINT`, and `SYM_CACHE_USER`, `SYM_CACHE_NAME` (default `sym`) | `SYM_CACHE_ENDPOINT`, `SYM_CACHE_USER` (the identity's object id) | `SYM_CACHE_ENDPOINT` (`rediss://`), and the instance's CA as `halyx.cache-manager.properties.redis.ca-certificate` |

The resources, with sym's default names (each overridable with a `SYM_*` variable, see `application.yml`):

- **Tables:** `sym-messages` and `sym-hashtags`.
  - DynamoDB tables with string `pk` and `sk` keys;
  - Cosmos DB containers partitioned on `/pk`, in database `sym`;
  - Firestore needs nothing.
- **Topic:** `sym-message-posted`, an event hub on Azure.
- **Consumer group:** `sym-hashtag-indexer`.

## Tests

```bash
mvn verify
```

| Test | Runs the API suite |
|---|---|
| `LocalDeploymentTest`, `LocalCacheDeploymentTest` | in memory, without and with the cache |
| `AwsEmulatorIT` | on the `aws` profile against Floci (DynamoDB), Kafka and Valkey |
| `AzureEmulatorIT` | on the `azure` profile against Floci-az (Cosmos DB), Kafka and Valkey |
| `GcpEmulatorIT` | on the `gcp` profile against Floci-gcp (Firestore), Kafka and Valkey |
| `PackagedJarIT` | against the built jar |

`ProfilesTest` checks each cloud profile as written, without connecting. `HashtagIndexerTest` checks that a
redelivered record does no harm.

The emulator tests need Docker; without it they're skipped.

## What it doesn't do

- **No outbox:** a message is stored, then streamed. If the stream is down after the store, the message is kept but
  not streamed, and the caller gets a `503`. Retrying posts it again under a new id.
- **No authentication:** the author comes from the URL. A real service would take it from a login.
- **No deployment pieces:** no Helm chart, IAM policies or infrastructure code.
- **Not yet run on a real cloud:** the emulators stand in for each cloud's services.
