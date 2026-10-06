package com.devlabs.aulaflix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * AIStor Free, the storage production runs, so that its refusals surface in the tests. It starts with the license
 * from {@code secrets/minio.license}, then runs {@code storage/init.sh}, as storage-init does in Compose, with
 * credentials of its own for each run.
 */
public class AistorContainer extends GenericContainer<AistorContainer> {

    private static final String IMAGE = "quay.io/minio/aistor/minio:RELEASE.2026-09-19T17-05-25Z";
    private static final int S3_PORT = 9000;
    private static final Path LICENSE = Path.of("secrets/minio.license");
    private static final String SECRETS = "/run/secrets/";

    private final Map<String, String> secrets = new LinkedHashMap<>();

    public AistorContainer() {
        super(IMAGE);
        requireTheLicense();
        secrets.put("storage.root-user", "root" + randomHex(8));
        secrets.put("storage.root-password", randomHex(24));
        secrets.put("aulaflix.storage.read-only.access-key-id", "ro" + randomHex(8));
        secrets.put("aulaflix.storage.read-only.secret-access-key", randomHex(20));
        secrets.put("aulaflix.storage.read-write.access-key-id", "rw" + randomHex(8));
        secrets.put("aulaflix.storage.read-write.secret-access-key", randomHex(20));
        secrets.forEach((name, value) -> withCopyToContainer(Transferable.of(value + "\n"), SECRETS + name));
        withCopyFileToContainer(MountableFile.forHostPath(LICENSE), SECRETS + "minio.license");
        withCopyFileToContainer(MountableFile.forHostPath("storage"), "/storage");
        withEnv("MINIO_ROOT_USER_FILE", SECRETS + "storage.root-user");
        withEnv("MINIO_ROOT_PASSWORD_FILE", SECRETS + "storage.root-password");
        withCommand("server", "/data", "--license", SECRETS + "minio.license");
        withExposedPorts(S3_PORT);
        waitingFor(Wait.forHttp("/minio/health/live").forPort(S3_PORT));
    }

    /** The S3 endpoint as the host sees it: the API's internal endpoint and the one it signs for, in the tests. */
    public String endpoint() {
        return "http://%s:%d".formatted(getHost(), getMappedPort(S3_PORT));
    }

    public String rootUser() {
        return secrets.get("storage.root-user");
    }

    public String rootPassword() {
        return secrets.get("storage.root-password");
    }

    /**
     * The {@code aulaflix.storage.*} properties that point the API at this container, with its keys. Each value is
     * read when asked for, since the endpoints carry the port the container gets once it runs.
     */
    public Map<String, Supplier<Object>> applicationProperties() {
        Map<String, Supplier<Object>> properties = new LinkedHashMap<>();
        properties.put("aulaflix.storage.internal-endpoint", this::endpoint);
        properties.put("aulaflix.storage.public-endpoint", this::endpoint);
        secrets.keySet().stream()
                .filter(name -> name.startsWith("aulaflix.storage."))
                .forEach(name -> properties.put(name, () -> secrets.get(name)));
        return properties;
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        ExecResult init = runInit();
        if (init.getExitCode() != 0) {
            throw new IllegalStateException("storage/init.sh failed: " + init.getStderr());
        }
    }

    /** Runs {@code storage/init.sh} against the running server, as storage-init does on every {@code up}. */
    public ExecResult runInit() {
        try {
            return execInContainer("env", "STORAGE_URL=http://localhost:" + S3_PORT, "sh", "/storage/init.sh");
        } catch (IOException failure) {
            throw new IllegalStateException("storage/init.sh could not run", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("storage/init.sh was interrupted", interrupted);
        }
    }

    private static void requireTheLicense() {
        if (!Files.isReadable(LICENSE)) {
            throw new IllegalStateException("AIStor Free needs its license at " + LICENSE.toAbsolutePath()
                    + "; without it every S3 operation is denied. See the README's Tests section.");
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
