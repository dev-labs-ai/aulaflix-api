package com.devlabs.aulaflix.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * The AulaFlix server blocks for vps-edge ({@code deploy/nginx/}), run in the nginx image the edge uses, on an IPv6
 * network like edge-aulaflix. A second nginx stands in for web:3000, api:8080 and storage:9000 and answers with what
 * reached it. Requests are made with curl inside the containers, so that each reaches the edge from a known address:
 * the stand-in's own, or the edge's loopback.
 *
 * <p>The edge runs the server blocks as they are, but for one line: it also allows its own loopback to reach the
 * webhook, since no test can come from Asaas's addresses. The unchanged file is the one {@code nginx -t} checks.
 */
class EdgeServerBlocksTest {

    private static final String NGINX = "nginx:1.29.8-alpine";
    private static final Path SERVER_BLOCKS = Path.of("deploy/nginx/aulaflix.conf");
    private static final Path MEDIA_KEY = Path.of("deploy/nginx/aulaflix-media.js");
    private static final String CERTIFICATES = "/etc/letsencrypt/live/aulaflix.com.br/";
    private static final String CHALLENGE = "/.well-known/acme-challenge/a-certbot-token";

    /** What vps-edge's nginx.conf provides around the server blocks. */
    private static final String EDGE_MAIN = """
            load_module modules/ngx_http_js_module.so;
            error_log /dev/stderr notice;
            events {}
            http {
                include /etc/nginx/conf.d/aulaflix.conf;
            }
            """;

    /** web:3000, api:8080 and storage:9000, each answering with the request that reached it. */
    private static final String UPSTREAMS = """
            error_log /dev/stderr notice;
            events {}
            http {
                server {
                    listen 3000; listen [::]:3000; listen 8080; listen [::]:8080; listen 9000; listen [::]:9000;
                    location /videos/big.mp4 { root /srv; }
                    location / {
                        return 200 "port=$server_port host=$host xff=$http_x_forwarded_for \
            bff=[$http_aulaflix_bff_key] client-ip=[$http_aulaflix_client_ip] uri=$request_uri\\n";
                    }
                }
            }
            """;

    private static Network network;
    private static GenericContainer<?> upstreams;
    private static GenericContainer<?> edge;

    @BeforeAll
    static void startTheEdge() throws Exception {
        network = Network.builder().createNetworkCmdModifier(create -> create.withEnableIpv6(true)).build();
        upstreams = new GenericContainer<>(NGINX)
                .withNetwork(network)
                .withNetworkAliases("web", "api", "storage")
                .withCopyToContainer(Transferable.of(UPSTREAMS), "/etc/nginx/nginx.conf")
                .waitingFor(Wait.forLogMessage(".*start worker process.*\\n", 1));
        upstreams.start();
        run(upstreams, "sh", "-c", "mkdir -p /srv/videos && head -c 16777216 /dev/zero > /srv/videos/big.mp4");

        String serverBlocks = Files.readString(SERVER_BLOCKS);
        String webhookFromLoopback =
                serverBlocks.replace("        deny all;", "        allow 127.0.0.1;\n        deny all;");
        assertThat(webhookFromLoopback).isNotEqualTo(serverBlocks);
        Path certificate = selfSignedCertificate();
        edge = new GenericContainer<>(NGINX)
                .withNetwork(network)
                .withNetworkAliases("edge")
                .withCopyToContainer(Transferable.of(EDGE_MAIN), "/etc/nginx/nginx.conf")
                .withCopyToContainer(Transferable.of(webhookFromLoopback), "/etc/nginx/conf.d/aulaflix.conf")
                .withCopyToContainer(Transferable.of(serverBlocks), "/etc/nginx/unchanged/aulaflix.conf")
                .withCopyFileToContainer(MountableFile.forHostPath(MEDIA_KEY), "/etc/nginx/njs/aulaflix-media.js")
                .withCopyFileToContainer(MountableFile.forHostPath(certificate.resolve("fullchain.pem")),
                        CERTIFICATES + "fullchain.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(certificate.resolve("privkey.pem")),
                        CERTIFICATES + "privkey.pem")
                .withCopyToContainer(Transferable.of("the-challenge-answer"), "/var/www/certbot" + CHALLENGE)
                .waitingFor(Wait.forLogMessage(".*start worker process.*\\n", 1));
        edge.start();
    }

    @AfterAll
    static void stopTheEdge() {
        Stream.of(edge, upstreams).filter(container -> container != null).forEach(GenericContainer::stop);
        if (network != null) {
            network.close();
        }
    }

    @Test
    void theUnchangedServerBlocksPassNginxT() throws Exception {
        String main = EDGE_MAIN.replace("/etc/nginx/conf.d/", "/etc/nginx/unchanged/");
        edge.copyFileToContainer(Transferable.of(main), "/etc/nginx/unchanged.conf");

        var test = edge.execInContainer("nginx", "-t", "-c", "/etc/nginx/unchanged.conf");

        assertThat(test.getExitCode()).as(test.getStderr()).isZero();
        assertThat(test.getStderr()).contains("test is successful");
    }

    @Test
    void plainHttpAnswersTheChallengeAndSendsEverythingElseToHttps() throws Exception {
        assertThat(fromOutside("http://aulaflix.com.br/cursos?area=backend"))
                .satisfies(response -> assertThat(response.status()).isEqualTo(301))
                .satisfies(response -> assertThat(response.header("location"))
                        .isEqualTo("https://aulaflix.com.br/cursos?area=backend"));
        assertThat(fromOutside("http://www.aulaflix.com.br/cursos").header("location"))
                .isEqualTo("https://aulaflix.com.br/cursos");
        assertThat(fromOutside("http://media.aulaflix.com.br/videos/a.mp4").header("location"))
                .isEqualTo("https://media.aulaflix.com.br/videos/a.mp4");
        assertThat(fromOutside("http://api.aulaflix.com.br/v1/webhooks/asaas", "-X", "POST").status())
                .isEqualTo(404);

        for (String host : List.of("aulaflix.com.br", "www.aulaflix.com.br", "media.aulaflix.com.br",
                "api.aulaflix.com.br")) {
            Response challenge = fromOutside("http://" + host + CHALLENGE);
            assertThat(challenge.status()).as(host).isEqualTo(200);
            assertThat(challenge.body()).as(host).isEqualTo("the-challenge-answer");
        }
    }

    @Test
    void everyHttpsAnswerCarriesHstsWithoutSubdomains() throws Exception {
        for (String url : List.of("https://aulaflix.com.br/", "https://www.aulaflix.com.br/",
                "https://media.aulaflix.com.br/nothing", "https://api.aulaflix.com.br/nothing")) {
            assertThat(fromOutside(url).header("strict-transport-security")).as(url).isEqualTo("max-age=31536000");
        }
    }

    @Test
    void theWebGetsEveryPathWithTheClientsOwnAddress() throws Exception {
        Response response = fromOutside("https://aulaflix.com.br/aprender/react/estado?x=1",
                "-H", "X-Forwarded-For: 203.0.113.66");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("port=3000", "host=aulaflix.com.br", "uri=/aprender/react/estado?x=1",
                "xff=" + upstreamsAddress() + " ");
    }

    @Test
    void wwwMovesToTheApex() throws Exception {
        Response response = fromOutside("https://www.aulaflix.com.br/cursos/react?x=1");

        assertThat(response.status()).isEqualTo(301);
        assertThat(response.header("location")).isEqualTo("https://aulaflix.com.br/cursos/react?x=1");
    }

    @Test
    void mediaPassesVideoReadsAndUploadsToTheStorageUnderItsOwnHostname() throws Exception {
        String video = "https://media.aulaflix.com.br/videos/lessons/7/abc.mp4?X-Amz-Signature=the-signature";

        for (String method : List.of("GET", "PUT")) {
            Response response = fromOutside(video, "-X", method, "-H", "X-Forwarded-For: 203.0.113.66");
            assertThat(response.status()).as(method).isEqualTo(200);
            assertThat(response.body()).as(method).contains("port=9000", "host=media.aulaflix.com.br",
                    "uri=/videos/lessons/7/abc.mp4?X-Amz-Signature=the-signature",
                    "xff=" + upstreamsAddress() + " ");
        }
        assertThat(fromOutside(video, "--head").status()).isEqualTo(200);
    }

    @Test
    void mediaAnswers404ToAnyOtherMethodOrPath() throws Exception {
        for (String method : List.of("POST", "DELETE", "PATCH", "OPTIONS")) {
            assertThat(fromOutside("https://media.aulaflix.com.br/videos/lessons/7/abc.mp4", "-X", method).status())
                    .as(method).isEqualTo(404);
        }
        assertThat(fromOutside("https://media.aulaflix.com.br/minio/health/live").status()).isEqualTo(404);
        assertThat(fromOutside("https://media.aulaflix.com.br/other-bucket/x.mp4").status()).isEqualTo(404);
    }

    @Test
    void mediaLogsThePathWithoutThePresignedQuery() throws Exception {
        fromOutside("https://media.aulaflix.com.br/videos/lessons/8/logged.mp4?X-Amz-Signature=never-logged");

        assertThat(edge.getLogs()).contains("\"GET /videos/lessons/8/logged.mp4 HTTP/")
                .doesNotContain("never-logged");
    }

    @Test
    void mediaServesTheFirst4MbAtFullSpeedThenOneMbASecond() throws Exception {
        assertThat(secondsToDownload("0-4194303")).isLessThan(1.0);
        // 4 MB at once, then 2 MB at 1 MB/s
        assertThat(secondsToDownload("0-6291455")).isBetween(1.5, 6.0);
    }

    private static double secondsToDownload(String range) throws Exception {
        return Double.parseDouble(run(upstreams, "curl", "--ipv4", "--silent", "--insecure", "--connect-to", "::edge:",
                "--output", "/dev/null", "--range", range, "--max-time", "20", "--write-out", "%{time_total}",
                "https://media.aulaflix.com.br/videos/big.mp4"));
    }

    @Test
    void mediaAllowsSixDownloadsPerClientAndAnyNumberOfUploads() throws Exception {
        String script = """
                media="curl -4 -sk --connect-to ::edge: -o /dev/null"
                for i in 1 2 3 4 5 6; do
                    $media --max-time 30 https://media.aulaflix.com.br/videos/big.mp4 &
                done
                sleep 2
                $media -w 'seventh-download=%{http_code} ' https://media.aulaflix.com.br/videos/big.mp4
                $media -w 'upload=%{http_code}' -X PUT --data-binary x https://media.aulaflix.com.br/videos/up.mp4
                kill $(jobs -p) 2>/dev/null || true
                wait
                """;

        assertThat(run(upstreams, "sh", "-c", script)).isEqualTo("seventh-download=429 upload=200");
    }

    @Test
    void mediaCountsAnIpv4AddressAloneAndAnIpv6AddressByItsSlash64() throws Exception {
        String script = """
                import media from 'aulaflix-media.js';
                ['203.0.113.7', '::ffff:198.51.100.9', '2001:DB8:0:1:aaaa::5',
                 '2001:0db8:0000:0001:ffff:ffff:ffff:ffff', '2001:db8:0:1::', '2001:db8:0:2::1', '2001:db8::1',
                 '64:ff9b::192.0.2.1', '1::2:3:4:5:192.0.2.1']
                    .forEach(address => console.log(address + ' ' + media.limitKey(address)));
                """;
        edge.copyFileToContainer(Transferable.of(script), "/tmp/keys.mjs");

        assertThat(run(edge, "njs", "-p", "/etc/nginx/njs", "/tmp/keys.mjs").lines()).containsExactly(
                "203.0.113.7 203.0.113.7",
                "::ffff:198.51.100.9 198.51.100.9",
                "2001:DB8:0:1:aaaa::5 2001:db8:0:1::/64",
                "2001:0db8:0000:0001:ffff:ffff:ffff:ffff 2001:db8:0:1::/64",
                "2001:db8:0:1:: 2001:db8:0:1::/64",
                "2001:db8:0:2::1 2001:db8:0:2::/64",
                "2001:db8::1 2001:db8:0:0::/64",
                "64:ff9b::192.0.2.1 64:ff9b:0:0::/64",
                "1::2:3:4:5:192.0.2.1 1:0:2:3::/64");
    }

    @Test
    void theApiTakesOnlyTheWebhookByPostFromAsaas() throws Exception {
        String webhook = "https://api.aulaflix.com.br/v1/webhooks/asaas";

        assertThat(fromOutside(webhook, "-X", "POST", "--data", "{}").status()).isEqualTo(403);
        assertThat(fromOutside(webhook).status()).isEqualTo(404);
        assertThat(fromOutside("https://api.aulaflix.com.br/v1/admin/courses", "-X", "POST").status()).isEqualTo(404);
        assertThat(fromOutside("https://api.aulaflix.com.br/v3/api-docs").status()).isEqualTo(404);
    }

    @Test
    void theWebhookReachesTheApiWithoutTheBffHeaders() throws Exception {
        Response response = fromTheAllowedLoopback("https://api.aulaflix.com.br/v1/webhooks/asaas", "-X", "POST",
                "--data", "{}", "-H", "AulaFlix-BFF-Key: forged", "-H", "AulaFlix-Client-IP: 203.0.113.66");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("port=8080", "uri=/v1/webhooks/asaas", "xff=127.0.0.1 ", "bff=[]",
                "client-ip=[]");
    }

    @Test
    void theWebhookRefusesABodyAbove256Kb() throws Exception {
        String overLimit = "x".repeat(256 * 1024 + 1);
        edge.copyFileToContainer(Transferable.of(overLimit), "/tmp/over-limit.json");
        edge.copyFileToContainer(Transferable.of(overLimit.substring(1)), "/tmp/at-limit.json");

        assertThat(fromTheAllowedLoopback("https://api.aulaflix.com.br/v1/webhooks/asaas", "-X", "POST",
                "--data-binary", "@/tmp/over-limit.json").status()).isEqualTo(413);
        assertThat(fromTheAllowedLoopback("https://api.aulaflix.com.br/v1/webhooks/asaas", "-X", "POST",
                "--data-binary", "@/tmp/at-limit.json").status()).isEqualTo(200);
    }

    /** From the stand-in's address, which is nobody special to the edge. */
    private static Response fromOutside(String url, String... options) throws Exception {
        List<String> overIpv4 = new ArrayList<>(List.of("--ipv4"));
        overIpv4.addAll(List.of(options));
        return curl(upstreams, "::edge:", url, overIpv4.toArray(String[]::new));
    }

    private static Response fromTheAllowedLoopback(String url, String... options) throws Exception {
        return curl(edge, "::127.0.0.1:", url, options);
    }

    private static Response curl(GenericContainer<?> from, String connectTo, String url, String... options)
            throws Exception {
        List<String> command = new ArrayList<>(List.of("curl", "--silent", "--insecure", "--max-time", "10",
                "--connect-to", connectTo, "--include", "--write-out", "\n%{http_code}"));
        command.addAll(List.of(options));
        command.add(url);
        String output = run(from, command.toArray(String[]::new));
        int lastLine = output.lastIndexOf('\n');
        String[] headersAndBody = output.substring(0, lastLine).split("\r\n\r\n", 2);
        return new Response(Integer.parseInt(output.substring(lastLine + 1).trim()), headersAndBody[0],
                headersAndBody.length > 1 ? headersAndBody[1] : "");
    }

    private static String run(GenericContainer<?> container, String... command) throws Exception {
        var result = container.execInContainer(StandardCharsets.UTF_8, command);
        assertThat(result.getExitCode()).as("%s: %s", String.join(" ", command), result.getStderr()).isZero();
        return result.getStdout();
    }

    private static String upstreamsAddress() {
        return upstreams.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next()
                .getIpAddress();
    }

    /** One certificate for the four names, as certbot issues it, made with the JDK's keytool. */
    private static Path selfSignedCertificate() throws IOException, InterruptedException, GeneralSecurityException {
        Path directory = Files.createTempDirectory("edge-certificate");
        Path keyStore = directory.resolve("edge.p12");
        char[] password = "edge-test".toCharArray();
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "edge", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=aulaflix.com.br",
                "-ext", "SAN=dns:aulaflix.com.br,dns:www.aulaflix.com.br,dns:media.aulaflix.com.br,"
                        + "dns:api.aulaflix.com.br",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", new String(password))
                .redirectErrorStream(true).start();
        assertThat(keytool.waitFor()).as(new String(keytool.getInputStream().readAllBytes())).isZero();

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStore)) {
            store.load(input, password);
        }
        Files.writeString(directory.resolve("fullchain.pem"),
                pem("CERTIFICATE", store.getCertificate("edge").getEncoded()));
        Files.writeString(directory.resolve("privkey.pem"),
                pem("PRIVATE KEY", store.getKey("edge", password).getEncoded()));
        return directory;
    }

    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    private record Response(int status, String headers, String body) {

        String header(String name) {
            return headers.lines()
                    .filter(line -> line.toLowerCase().startsWith(name.toLowerCase() + ":"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse(null);
        }
    }
}
