# sym: portability review

Reviewed 2026-10-24 against Spring Boot 4.1.1 and Halyx 1.0.1, with the `halyx-ai-review` rules.

| Cloud | Verdict | Blockers | Risks |
|---|---|---|---|
| AWS | ready | 0 | 0 |
| Azure | ready | 0 | 0 |
| Google Cloud | ready | 0 | 0 |

No leaks were found in the code, configuration, build or tests. "Ready" means that. It doesn't mean sym has run on
a real cloud: see the last section.

## Findings

### hygiene: the cache stores values with Java serialization rather than JSON  (rule G7)
**Resolved 2026-10-25:** on Halyx 1.0.2, which picks Jackson 3 on Boot 4, sym uses `codec: json` and `Message` is no
longer `Serializable`.

`src/main/resources/application.yml`, `halyx.cache-manager.codec: jdk`. This is safe as written:
`trusted-packages: org.kamranzafar.sym` limits what is read back. But the cached values are opaque bytes in Redis,
and every cached type must be `Serializable`.

It's a workaround. Halyx 1.0.1's `json` codec picks Jackson 2 on Spring Boot 4 when a cloud SDK brings Jackson 2,
using a mapper without `java.time` support, so caching a `Message` fails on its `Instant`. The
`cache codec that can't hold Instant` mutation showed this: `LocalCacheDeploymentTest` fails on `json`.

**Fix:** once a Halyx release picks Jackson 3 on Boot 4, switch to `codec: json` and drop `Serializable` from
`Message`.

## What was checked

| Rule | Covered |
|---|---|
| C1 native clients | no `unwrap(` and no AWS, Azure or Google Cloud SDK import in `src/main` |
| C2 SDK exceptions | none; `MessageController` maps Halyx's `CloudException` to 503 |
| C3 messaging features | not applicable: no topics or subscriptions |
| C4 presigned URLs | not applicable: no blob stores |
| C5 stream transactions | `message-posted` is `idempotent: true`, not transactional, so it runs on Event Hubs |
| C6 decimals | no `BigDecimal` anywhere |
| C7 payload size | the stream record is the message's 4 fields; at most 280 code points, about 1.3 KB at worst |
| C8 event filtering | not applicable: no event buses; the stream consumer reads one topic |
| C9 cloud names in code | none in Java; one comment in `application.yml` describes Event Hubs' bootstrap address format |
| C10 bean names | keys `messages`, `hashtags`, `voices`, `message-posted`, `hashtag-indexer` and providers `local`, `aws`, `azure`, `gcp`; none equals a bean (`messageService`, `messageController`, `hashtagIndexer`, `messageCaching`, `messageRepository`, `hashtagRepository`, `symApplication`) |
| G1 shared settings | the base document has table names, topic and group names from `SYM_*` variables, and the Kafka login type; no provider, region, endpoint or cloud property |
| G2 profiles | `local`, `aws`, `azure` and `gcp`, plus `cache` with per-cloud documents. No `spring.profiles.default`, deliberately: with no profile, startup fails at once ("halyx.document-stores.hashtags.provider is required"), rather than a cloud deployment that forgot its profile running in memory |
| G3 per-cloud names | not applicable: no subscriptions or event buses |
| G4 ineffective Spring settings | none (`spring.datasource`, `spring.cache`, `spring.data.redis` and `spring.kafka` are absent) |
| G5 credentials | none in any profile a deployment uses; the emulator tests pass theirs as arguments |
| G6 profile without a cloud | `local` sees no `halyx.databases` or `halyx.spring-kafka`; its stream cluster is `memory:9092` |
| G7 caches | the codec has `trusted-packages`; the cache manager has `namespace-prefix: "sym:"` (the finding above) |
| B1 provider modules | `halyx-aws`, `halyx-azure` and `halyx-gcp`, so one jar runs on all three |
| B2 leftover cloud libraries | none |
| B3 SDK artifacts at test scope | none; the emulator tests use the DynamoDB and Cosmos DB clients the provider modules bring |
| B4 runtime pieces | not applicable: no SQL |
| D1–D4 deployment | not applicable: the repository has no Helm chart, IAM policies, Terraform or image build |
| T1 tests per cloud | one suite (`SymSuite`, 7 tests) on `local`, `local,cache`, and on each cloud's profile with the cache against Floci, Floci-az and Floci-gcp, Kafka 4.2.1 and Valkey 9.1; 47 tests in all, every one passing |
| T2 container wiring | no `@ServiceConnection`; the containers' addresses go in as the `SYM_*` variables a deployment sets |
| T3 profiles as written | `ProfilesTest` resolves `aws`, `azure` and `gcp`, with and without `cache`, without connecting: provider types, IAM logins, no credentials, table names |
| T4 packaged service | `PackagedJarIT` starts the built jar and posts and reads a message |

Five mistakes were introduced one at a time, and each was caught by the test meant for it:
- a wrong table name in a profile;
- a stream listener that throws;
- an indexer that isn't idempotent;
- a missing message returning 500;
- a cache codec that can't hold an `Instant`.

## What a review can't establish

- **Real clouds.** Nothing has run on AWS, Azure or Google Cloud. The emulators stand in for DynamoDB, Cosmos DB and
  Firestore, Kafka for MSK, Event Hubs and Managed Kafka, and Valkey for the managed caches. The IAM logins (MSK
  IAM, Entra ID for Event Hubs and Azure Managed Redis, Google IAM for Managed Kafka and Memorystore) run only on
  the real services.
- **MSK specifically.** The project's AWS test account can't create MSK, so AWS streams are proven on a Kafka
  container only.
- **Permissions.** The repository has no IAM. A deployment's identity needs:
  - read and write on both tables;
  - produce to and consume from `sym-message-posted`, as consumer group `sym-hashtag-indexer` (on Event Hubs, the
    Azure Event Hubs Data Sender and Receiver roles);
  - with `cache`, a cache login: an ElastiCache IAM user, an Azure Managed Redis access policy assignment, or
    `roles/memorystore.dbConnectionUser`.
- **`Instant` precision per store.** Halyx maps `Instant` on every store, and all three emulators round-tripped
  `postedAt`. The docs don't state each real store's precision, so compare timestamps by value with care.
