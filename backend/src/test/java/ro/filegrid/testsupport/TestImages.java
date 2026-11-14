package ro.filegrid.testsupport;

import org.testcontainers.utility.DockerImageName;

/** Container images used by the integration tests: the same ones as infra/docker-compose.yml. */
public final class TestImages {

    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16-alpine");

    public static final DockerImageName PGVECTOR = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    /** Silo, the MinIO-compatible S3 server (docs/ADR/0001). */
    public static final DockerImageName S3 = DockerImageName.parse(
                    "pgsty/silo:RELEASE.2026-11-12T00-00-00Z"
                            + "@sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329")
            .asCompatibleSubstituteFor("minio/minio");

    public static final DockerImageName FTP = DockerImageName.parse("stilliard/pure-ftpd:latest");

    private TestImages() {
    }
}
