# sym: portability review

Reviewed 2026-10-25 against Spring Boot 4.1.1 and Halyx 1.0.2, with the `halyx-ai-review` rules. This replaces the
review of 2026-10-24 (Halyx 1.0.1), and now covers message images in the `images` blob store.

| Cloud | Verdict | Blockers | Risks |
|---|---|---|---|
| AWS | ready | 0 | 0 |
| Azure | ready | 0 | 0 |
| Google Cloud | ready | 0 | 0 |

No leaks were found in the code, configuration, build or tests. "Ready" means that. It doesn't mean sym has run on
a real cloud: see the last section.

## Findings

None.

The previous review's one finding (G7: the cache used Java serialization as a workaround for Halyx 1.0.1's JSON
codec) is resolved: on Halyx 1.0.2 the cache uses `codec: json`, still limited by `trusted-packages`, and `Message`
is no longer `Serializable`.

## What was checked

Halyx surface: blob store `images`; document stores `messages` and `hashtags`; stream cluster `voices`, producer
`message-posted` and consumer `hashtag-indexer`; `halyx.cache-manager` on the `cache` profile. Injected with
`@Qualifier`: `message-posted` and `images` in `MessageService`. One `@HalyxListener`, `hashtag-indexer` on
`HashtagIndexer`. No `unwrap(` in `src/main`.

| Rule | Covered |
|---|---|
| C1 native clients | no `unwrap(` and no AWS, Azure or Google Cloud SDK imports in `src/main` |
| C2 SDK exceptions | none; the image read catches Halyx's `NotFoundException`, which every provider raises for a missing blob, and the controller maps a retryable `CloudException` to 503 and any other to 500 |
| C3 messaging features | not applicable: no queues or topics, only a Kafka stream |
| C4 presigned URLs | none: images are read and written through sym |
| C5 transactional producers | `message-posted` is `idempotent: true`, not transactional, which Event Hubs supports |
| C6 decimals | no `BigDecimal` anywhere |
| C7 large payloads | stream records carry one message of at most 280 characters; images go to the blob store, never into a record |
| C8 routing filters | not applicable: no event subscriptions |
| C9 cloud names in code | none in Java; one comment in `application.yml` describes Event Hubs' bootstrap address format |
| C10 bean names | keys `images`, `messages`, `hashtags`, `voices`, `message-posted`, `hashtag-indexer` and providers `local`, `aws`, `azure`, `gcp`; none equals a bean (`messageService`, `messageController`, `hashtagIndexer`, `messageCaching`, `messageRepository`, `hashtagRepository`, `symApplication`) |
| G1 shared settings | the base document has the bucket, table, topic and group names from `SYM_*` variables, and the Kafka login type; no provider, region, endpoint or cloud property. The storage account is in the `azure` profile |
| G2 profiles | `local`, `aws`, `azure` and `gcp`, plus `cache` with per-cloud documents. No `spring.profiles.default`, deliberately: with no profile, startup fails at once, rather than a cloud deployment that forgot its profile running in memory |
| G3 per-cloud names | not applicable: no subscriptions or event buses |
| G4 ineffective Spring settings | none (`spring.datasource`, `spring.cache`, `spring.data.redis` and `spring.kafka` are absent) |
| G5 credentials | none in any profile a deployment uses; the emulator tests pass theirs, and S3 path-style addressing, as arguments |
| G6 profile without a cloud | `local` sees no `halyx.databases` or `halyx.spring-kafka`; its stream cluster is `memory:9092`, and its blob store is the memory provider's |
| G7 caches | `codec: json` with `trusted-packages: org.kamranzafar.sym`; the cache manager has `namespace-prefix: "sym:"` |
| B1 provider modules | `halyx-aws`, `halyx-azure` and `halyx-gcp`, so one jar runs on all three |
| B2 leftover cloud libraries | none |
| B3 SDK artifacts at test scope | none declared; the runtime classpath has the S3, DynamoDB, Blob Storage, Cosmos DB, Cloud Storage and Firestore clients. The emulator tests create the bucket and containers with the clients the provider modules bring |
| B4 runtime pieces | not applicable: no SQL |
| D1–D4 deployment | not applicable: the repository has no Helm chart, IAM policies, Terraform or image build |
| T1 tests per cloud | one suite (`SymSuite`, 11 tests, 4 of them for images) on `local`, `local,cache`, and on each cloud's profile with the cache against Floci (DynamoDB, S3), Floci-az (Cosmos DB, Blob Storage) and Floci-gcp (Firestore, Cloud Storage), Kafka 4.2.1 and Valkey 9.1; 67 tests in all, every one passing |
| T2 container wiring | no `@ServiceConnection`; the containers' addresses go in as the `SYM_*` variables a deployment sets |
| T3 profiles as written | `ProfilesTest` resolves `aws`, `azure` and `gcp`, with and without `cache`, without connecting: provider types, IAM logins, no credentials, table and bucket names, and the storage account on Azure |
| T4 packaged service | `PackagedJarIT` starts the built jar and posts and reads a message |

Mistakes introduced one at a time, each caught by the test meant for it:
- on 2026-10-24: a wrong table name in a profile; a stream listener that throws; an indexer that isn't idempotent;
  a missing message returning 500; a cache codec that can't hold an `Instant`;
- on 2026-10-25: attaching an image without evicting the cached message (caught on `local,cache` only); and
  accepting an image whose bytes don't match its type.

## What a review can't establish

- **Real clouds.** Nothing has run on AWS, Azure or Google Cloud. The emulators stand in for DynamoDB, Cosmos DB,
  Firestore, S3, Blob Storage and Cloud Storage, Kafka for MSK, Event Hubs and Managed Kafka, and Valkey for the
  managed caches. The IAM logins (MSK IAM, Entra ID for Event Hubs and Azure Managed Redis, Google IAM for Managed
  Kafka and Memorystore) run only on the real services.
- **MSK specifically.** The project's AWS test account can't create MSK, so AWS streams are proven on a Kafka
  container only.
- **Permissions.** The repository has no IAM. A deployment's identity needs:
  - read and write on both tables;
  - on the `sym-images` bucket: read, write and delete objects, and list them, which Halyx's blob store health
    check does (`s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` and `s3:ListBucket`; Storage Blob Data
    Contributor on the container; on Cloud Storage `storage.objects.get`, `create`, `delete` and `list`, where
    replacing an image also needs `delete`, as in `roles/storage.objectUser`);
  - produce to and consume from `sym-message-posted`, as consumer group `sym-hashtag-indexer` (on Event Hubs, the
    Azure Event Hubs Data Sender and Receiver roles);
  - with `cache`, a cache login: an ElastiCache IAM user, an Azure Managed Redis access policy assignment, or
    `roles/memorystore.dbConnectionUser`.
- **`Instant` precision per store.** Halyx maps `Instant` on every store, and all three emulators round-tripped
  `postedAt`. The docs don't state each real store's precision, so compare timestamps by value with care.
