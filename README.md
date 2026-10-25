# Updating 100,000 rows in Aurora DSQL with Spring Boot

A small Spring Boot example that repeatedly updates up to 1,000 rows, committing each batch in its own transaction. The central query is in `TestRepository.renameNextBatch`; `TestService.updateUntilEmpty` calls it until it returns zero.

The author's AWS experiment returned `{"updated":100000}`. This repository also includes local H2 tests for transaction boundaries and recovery. H2 does not reproduce Aurora DSQL limits or optimistic concurrency control.

## Requirements

- JDK 25, matching `pom.xml`.
- Maven 3.9+ or the included Maven wrapper.
- A disposable Aurora DSQL cluster, with access to connect as `admin`.
- AWS CLI v2 with the `dsql` commands if generating the token in your terminal.
- `curl`; the AWS query editor can run the schema and verification SQL.

This is a local experiment. The HTTP server binds to `127.0.0.1` and has no application authentication. Run one update request at a time.

## 1. Create the table

Create a single-Region Aurora DSQL cluster and wait for it to become active. Open its query editor as `admin` and run `schema.sql` once, in a fresh database:

```sql
CREATE TABLE test (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);
```

The application validates the table at startup; it does not create or modify the schema automatically.

## 2. Configure your local connection

In Bash or Zsh, replace the endpoint and Region with your cluster's values:

```bash
export AWS_REGION='us-east-1'
export DSQL_HOST='YOUR_CLUSTER_ID.dsql.us-east-1.on.aws'
export DSQL_USER='admin'
export DSQL_TOKEN="$(aws dsql generate-db-connect-admin-auth-token \
  --region "$AWS_REGION" \
  --hostname "$DSQL_HOST" \
  --expires-in 3600)"

mkdir -p .certs
curl --fail --silent --show-error \
  https://www.amazontrust.com/repository/AmazonRootCA1.pem \
  -o .certs/AmazonRootCA1.pem
export DSQL_SSLROOTCERT="$PWD/.certs/AmazonRootCA1.pem"
```

Alternatively, use the console's **Generate auth token** action and supply that token as `DSQL_TOKEN` in your IDE's local run configuration. Do not commit the token. AWS documents `dsql:DbConnectAdmin` for connecting as `admin`; custom database roles use the non-admin token command and corresponding permissions.

The JDBC configuration uses `sslmode=verify-full` and the Amazon root certificate. See [AWS certificate setup](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/configure-root-certificates.html).

A manually supplied token is suitable for this short experiment. An expired token cannot open a new connection; generate another token and restart the application when necessary. Existing connections are not terminated simply because the token expires. For a long-running application, use automatic token generation for new connections, such as the [Aurora DSQL JDBC connector](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/SECTION_authentication-token.html).

## 3. Start and seed

```bash
./mvnw spring-boot:run
```

In another terminal:

```bash
curl --fail-with-body -i -X POST http://localhost:8080/tests/bulk-generate
```

This starts an asynchronous insertion of 100,000 random UUID/name records and returns HTTP 202. It does not mean insertion has finished. Wait for `Inserted 100000 random test rows.` in the application log. In the query editor, verify:

```sql
SELECT count(*) FROM test;
```

The expected count is 100,000 in a fresh table. Call the generation endpoint only once: each call adds another 100,000 rows. If it fails partway, inspect the log and the count before continuing.

## 4. Update and verify

```bash
curl --fail-with-body -i --max-time 360 \
  -X PATCH http://localhost:8080/tests/bulk-update-batched \
  -H 'Content-Type: application/json' \
  -d '{"name":"separate-transaction-test","batchSize":1000}'
```

Expected body for 100,000 eligible rows:

```json
{"updated":100000}
```

Verify in the query editor:

```sql
SELECT count(*) AS updated_rows
FROM test WHERE name = 'separate-transaction-test';

SELECT count(*) AS remaining_rows
FROM test WHERE name <> 'separate-transaction-test';
```

Expected counts are 100,000 and 0. Repeating the same PATCH should return `{"updated":0}` while no other writer changes the data. `updated` counts changes made by that request, not lifetime job progress.

## 5. Optional: observe the single-transaction limit

With at least 3,001 rows whose names differ from `one-transaction-test`, call:

```bash
curl -i --max-time 360 \
  -X PATCH http://localhost:8080/tests/bulk-update \
  -H 'Content-Type: application/json' \
  -d '{"count":3001,"name":"one-transaction-test"}'
```

On DSQL this deliberately exceeds the documented 3,000-table-row mutation limit. Expect an HTTP error; inspect the underlying database error in the application log. This is an exercise for your cluster, not a recorded error transcript. The endpoint rolls back its transaction. H2 does not enforce this limit.

## Tests

```bash
./mvnw test
```

Tests use H2 and require no AWS credentials. They cover input validation, updating without entity loading, rollback, preservation of earlier batches after a later failure, resuming, repeat requests, and `REQUIRES_NEW` independence from an outer transaction. The test-only H2 constraint is a failure injection mechanism, not DSQL schema guidance.

## Transaction behaviour

- The service loop has no transaction. Each repository call uses Spring's `REQUIRES_NEW`.
- `name <> :name` removes completed rows from the next batch. The schema requires a non-null name.
- 100,000 eligible rows at 1,000 per batch require 100 nonempty update transactions and a final empty call.
- A failure leaves earlier batches committed. Retry with the same target name to process the remaining rows, provided there are no competing changes.
- This example has no automatic OCC retry or durable job status. A client timeout does not prove that the server stopped.
- Concurrent jobs with different target names can undo each other's progress. Use a bounded job population and coordinate workers in a production design.
- The row limit does not replace DSQL's 10 MiB modified-data limit or five-minute transaction limit. A batch size of 1,000 is a choice for this small schema, not a universal safe value.
- Native bulk updates bypass entity lifecycle callbacks and can leave already-managed entities stale. The update path does not load entities.

## Cleanup

Stop the local application. If the disposable cluster is no longer needed, delete it through the AWS console after checking that it holds no data you want to retain. Clear the token from your terminal:

```bash
unset DSQL_TOKEN
```

## References

- [Aurora DSQL limits](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/CHAP_quotas.html)
- [Authentication tokens](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/SECTION_authentication-token.html)
- [Spring transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [DSQL concurrency control](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/working-with-concurrency-control.html)
