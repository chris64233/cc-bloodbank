package com.chris64233.cc.bloodbank.api;

import com.chris64233.cc.bloodbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReservationApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Instant EXPIRES = Instant.parse("2026-09-29T08:00:00Z");

    private void registerViaApi(String barcode, String bloodType) throws Exception {
        String body = """
                {
                  "barcode": "%s",
                  "componentType": "RBC",
                  "bloodType": "%s",
                  "collectedAt": "2026-08-29T08:00:00Z",
                  "expiresAt": "%s"
                }
                """.formatted(barcode, bloodType, EXPIRES);
        mockMvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String reserveBody(String requestNo, String patientType, int count, String latestUse,
                               String expireAt) {
        String expireField = expireAt == null ? "null" : "\"" + expireAt + "\"";
        return """
                {
                  "requestNo": "%s",
                  "patientBloodType": "%s",
                  "componentType": "RBC",
                  "unitCount": %d,
                  "latestUseAt": "%s",
                  "expireAt": %s
                }
                """.formatted(requestNo, patientType, count, latestUse, expireField);
    }

    @Test
    void fullReserveQueryIssueFlow() throws Exception {
        registerViaApi("A1", "O-");
        registerViaApi("A2", "O-");
        registerViaApi("A3", "A+");

        String body = reserveBody("REQ-WEB", "A+", 2, "2026-09-29T08:00:00Z", null);
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].barcode").value("A1"))
                .andExpect(jsonPath("$.items[1].barcode").value("A2"))
                .andExpect(jsonPath("$.expireAt").value(T0.plus(Duration.ofMinutes(30)).toString()));

        // 预留明细查询
        mockMvc.perform(get("/api/reservations/REQ-WEB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
        mockMvc.perform(get("/api/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 库存状态查询：2 个 RESERVED、1 个 AVAILABLE
        mockMvc.perform(get("/api/units").param("status", "RESERVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/units").param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 确认发放
        mockMvc.perform(post("/api/reservations/REQ-WEB/issue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.items[0].status").value("ISSUED"));
        // 重复发放幂等
        mockMvc.perform(post("/api/reservations/REQ-WEB/issue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(get("/api/units").param("status", "ISSUED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void sameContentReplayReturns200OriginalAllocation() throws Exception {
        registerViaApi("B1", "O-");
        String body = reserveBody("REQ-R", "O-", 1, "2026-09-29T08:00:00Z", null);
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].barcode").value("B1"));
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].barcode").value("B1"));
    }

    @Test
    void differentContentReturns409() throws Exception {
        registerViaApi("C1", "O-");
        registerViaApi("C2", "O-");
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody("REQ-C", "O-", 1, "2026-09-29T08:00:00Z", null)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody("REQ-C", "O-", 2, "2026-09-29T08:00:00Z", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONTENT_CONFLICT"));
    }

    @Test
    void insufficientUnitsReturns409AndNothingReserved() throws Exception {
        registerViaApi("D1", "O-");
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody("REQ-D", "O-", 2, "2026-09-29T08:00:00Z", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_UNITS"));
        mockMvc.perform(get("/api/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/units/D1"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void expiredIssueReturns409AndManualExpireReleasesUnits() throws Exception {
        registerViaApi("E1", "O-");
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody("REQ-E", "O-", 1, "2026-09-29T08:00:00Z",
                                T0.plus(Duration.ofMinutes(10)).toString())))
                .andExpect(status().isCreated());

        clock.advance(Duration.ofMinutes(11));

        mockMvc.perform(post("/api/reservations/REQ-E/issue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        // 手动触发到期释放
        mockMvc.perform(post("/api/reservations/REQ-E/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.items[0].status").value("RELEASED"));
        mockMvc.perform(get("/api/units/E1"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        // 已释放后再发放
        mockMvc.perform(post("/api/reservations/REQ-E/issue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
    }

    @Test
    void unknownResourcesAndValidationReturn404And400() throws Exception {
        mockMvc.perform(get("/api/reservations/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/reservations/NOPE/issue")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/units/NOPE")).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content("""
                {"barcode": "", "componentType": "RBC", "bloodType": "O-",
                 "collectedAt": "2026-08-29T08:00:00Z", "expiresAt": "2026-09-29T08:00:00Z"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestNo": "REQ-BAD", "patientBloodType": "O-", "componentType": "RBC",
                 "unitCount": 0, "latestUseAt": "2026-09-29T08:00:00Z", "expireAt": null}
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateBarcodeReturns409() throws Exception {
        registerViaApi("F1", "O-");
        mockMvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content("""
                {"barcode": "F1", "componentType": "RBC", "bloodType": "O-",
                 "collectedAt": "2026-08-29T08:00:00Z", "expiresAt": "2026-09-29T08:00:00Z"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BARCODE_EXISTS"));
    }
}
