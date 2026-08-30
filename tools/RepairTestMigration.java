import org.flywaydb.core.Flyway;
import java.net.URI;

/** Explicit maintenance command; never called by application startup or normal tests. */
class RepairTestMigration {
    public static void main(String[] args) {
        String url=System.getenv("HOSPITAL_TEST_DB_URL");
        if (url==null) throw new IllegalArgumentException("Explicit HOSPITAL_TEST_DB_URL required");
        URI uri=URI.create(url.substring(5));
        if (!java.util.Set.of("localhost","127.0.0.1").contains(uri.getHost()) ||
            !uri.getPath().endsWith("_test") || url.equals(System.getenv("HOSPITAL_DB_URL"))) {
            throw new IllegalArgumentException("Only an explicit local isolated *_test database may be repaired");
        }
        Flyway.configure().dataSource(url,System.getenv("HOSPITAL_DB_USERNAME"),System.getenv("HOSPITAL_DB_PASSWORD"))
            .locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().repair();
        System.out.println("Repaired test migration metadata only; user data was not removed. Re-run the resumable migration.");
    }
}
