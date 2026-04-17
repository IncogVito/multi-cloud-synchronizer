# Fix Agent Instructions

When fixing a bug in this codebase, follow this workflow:

## 1. Write a failing test first

Before touching production code, write a test that:
- Reproduces the exact bug (the test **must fail** before the fix)
- Lives in the appropriate test file under `backend/src/test/java/com/cloudsync/`
- Uses real assertions (no empty `// TODO` bodies)

Run the test to confirm it fails:
```bash
cd backend && ./gradlew test --tests "com.cloudsync.path.to.YourTest"
```

## 2. Implement the fix

Apply the minimal change that makes the failing test pass.  
Do not refactor unrelated code in the same commit.

## 3. Confirm all tests still pass

```bash
cd backend && ./gradlew test
```

## 4. Common bugs and their patterns

### Wrong ObjectMapper type (Micronaut Serde vs Jackson)

**Symptom:** `No bean of type [com.fasterxml.jackson.databind.ObjectMapper] exists`

**Root cause:** The project uses `micronaut-serde-jackson`, which registers
`io.micronaut.serde.ObjectMapper` as the Micronaut bean — **not** Jackson's
`com.fasterxml.jackson.databind.ObjectMapper`.

**Fix:**
```java
// WRONG — Jackson ObjectMapper is not a Micronaut bean
import com.fasterxml.jackson.databind.ObjectMapper;

// CORRECT — Micronaut Serde ObjectMapper is the registered bean
import io.micronaut.serde.ObjectMapper;
```

Any class that needs JSON serialization and is a Micronaut `@Singleton` must use
`io.micronaut.serde.ObjectMapper`.  DTOs serialised by this mapper must be
annotated with `@Serdeable` (not `@JsonInclude` alone).

### Missing @Serdeable on a DTO

**Symptom:** Serialization returns `{}` or throws at runtime.

**Fix:** Add `@Serdeable` from `io.micronaut.serde.annotation.Serdeable` to the
record/class.  Replace Jackson-only annotations (`@JsonInclude`) with Serdeable
equivalents or remove them.

## 5. Test dependencies available

- JUnit 5 (`org.junit.jupiter`)
- Mockito (`org.mockito:mockito-core` + `mockito-junit-jupiter`)
- AssertJ (`org.assertj:assertj-core`)
- Micronaut Test (`io.micronaut.test:micronaut-test-junit5`) for context tests

## 6. Frontend build check

After any frontend change run:
```bash
cd frontend && npx ng build --configuration development
```
Zero errors required before shipping.
