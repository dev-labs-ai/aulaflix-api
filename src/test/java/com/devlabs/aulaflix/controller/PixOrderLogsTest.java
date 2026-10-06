package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.exception.PaymentProviderErrorException;
import com.devlabs.aulaflix.service.AccountService;

/**
 * The CPF is never stored or logged, whatever the placement answers; nor is the Student's email, nor Asaas's key. An
 * Asaas refusal that a retry will not fix is logged at ERROR, with what Asaas said, for whoever looks into it.
 */
@ExtendWith(OutputCaptureExtension.class)
class PixOrderLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private JdbcTemplate jdbc;

    private long course;

    @BeforeEach
    void putACourseOnSale() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos)
                .onSale(newSlug());
    }

    @Test
    void logsNoCpfEmailOrKey(CapturedOutput output) {
        String email = StudentApi.newEmail();
        String cpf = Cpfs.newCpf();
        String refusedCpf = Cpfs.newCpf();
        String failingCpf = Cpfs.newCpf();
        String invalidCpf = cpf.substring(0, 10) + (cpf.charAt(10) == '0' ? '1' : '0');
        asaas.answerNextCustomerWith(refusedCpf, Asaas.error(400, "invalid_cpfCnpj"));
        asaas.answerNextCustomerWith(failingCpf, serverError());
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), Asaas.error(400, "invalid_value"));
        StudentOrders orders = signedUp(email);

        orders.placePix(course, null);
        orders.placePix(course, invalidCpf);
        orders.placePix(course, Cpfs.punctuated(refusedCpf));
        orders.placePix(course, failingCpf);
        orders.placePix(course, Cpfs.punctuated(cpf));
        String code = orders.placedPix(course, null);
        orders.placePix(course, null);
        orders.get(code);
        orders.list();

        assertThat(output.getAll()).doesNotContain(cpf, Cpfs.punctuated(cpf), refusedCpf,
                Cpfs.punctuated(refusedCpf), failingCpf, invalidCpf, email, Asaas.API_KEY);
    }

    @Test
    void storesNoCpf() {
        String email = StudentApi.newEmail();
        String cpf = Cpfs.newCpf();
        StudentOrders orders = signedUp(email);

        String code = orders.placedPix(course, Cpfs.punctuated(cpf));

        Map<String, Object> account = jdbc.queryForMap("select * from accounts where email = ?", email);
        Map<String, Object> order = jdbc.queryForMap("select * from orders where code = ?", code);
        assertThat(account.values()).isNotEmpty().noneSatisfy(value -> assertThat(String.valueOf(value))
                .containsAnyOf(cpf, Cpfs.punctuated(cpf)));
        assertThat(order.values()).isNotEmpty().noneSatisfy(value -> assertThat(String.valueOf(value))
                .containsAnyOf(cpf, Cpfs.punctuated(cpf)));
        assertThat(account.get("asaas_customer_id")).isEqualTo(Asaas.customerOf(cpf));
    }

    @Test
    void logsAnAsaasRefusalAtErrorWithWhatAsaasSaid(CapturedOutput output) {
        String cpf = Cpfs.newCpf();
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), Asaas.error(400, "invalid_billingType"));

        signedUp(StudentApi.newEmail()).placePix(course, cpf);

        assertThat(output.getAll()).containsPattern(
                "ERROR .*Refused POST /v1/account/orders: payment-provider-error; Order [2-9A-Z]{8} cancelled; "
                        + "Asaas refused creating a Pix charge: HTTP 400 \\[invalid_billingType]\\R+"
                        + PaymentProviderErrorException.class.getName() + ": ");
    }

    @Test
    void logsAnAsaasOutageAtWarnWithItsCause(CapturedOutput output) {
        String cpf = Cpfs.newCpf();
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), serverError());

        signedUp(StudentApi.newEmail()).placePix(course, cpf);

        assertThat(output.getAll()).containsPattern(
                "WARN .*Refused POST /v1/account/orders: payment-unavailable; Order [2-9A-Z]{8} cancelled; "
                        + "Asaas failed creating a Pix charge: HTTP 500");
    }

    private StudentOrders signedUp(String email) {
        BffApi bff = new BffApi(mvc);
        return new StudentOrders(bff, new StudentApi(bff).signedUp(email, PASSWORD));
    }
}
