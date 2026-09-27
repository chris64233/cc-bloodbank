package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API 端到端：登记单位 → 申请预留 → 查询 → 发放 → 库存状态；重放与冲突。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BloodBankApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    BloodComponentRepository componentRepository;
    @Autowired
    ClinicalRequestRepository requestRepository;
    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    ReservationItemRepository itemRepository;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanUp() {
        itemRepository.deleteAll();
        reservationRepository.deleteAll();
        requestRepository.deleteAll();
        componentRepository.deleteAll();
        clock.setInstant(TestClockConfig.T0);
    }

    private static String componentJson(String barcode) {
        return """
                {"barcode":"%s","componentType":"RED_CELLS","aboGroup":"O","rhFactor":"POSITIVE",
                 "collectedAt":"2026-09-26T08:00:00Z","expiresAt":"2026-09-29T08:00:00Z"}
                """.formatted(barcode);
    }

    private static final String RESERVATION_JSON = """
            {"requestNo":"RQ-API-1","patientAbo":"A","patientRh":"POSITIVE","componentType":"RED_CELLS",
             "unitCount":1,"latestUseTime":"2026-09-27T20:00:00Z"}
            """;

    @Test
    void fullReserveIssueFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("API-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        // 提交申请并预留
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestNo").value("RQ-API-1"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items[0].barcode").value("API-1"));

        // 相同申请号相同内容重放 → 返回原分配
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].barcode").value("API-1"));

        // 相同申请号不同内容 → 409 冲突
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON.replace("\"unitCount\":1", "\"unitCount\":2")))
                .andExpect(status().isConflict());

        // 申请与预留明细查询
        mockMvc.perform(get("/api/requests/RQ-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitCount").value(1))
                .andExpect(jsonPath("$.reservation.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservation.items[0].barcode").value("API-1"));

        // 确认发放，重复发放幂等
        mockMvc.perform(post("/api/reservations/RQ-API-1/issue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(post("/api/reservations/RQ-API-1/issue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        // 库存状态查询：单位已发放
        mockMvc.perform(get("/api/components").param("status", "ISSUED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].barcode").value("API-1"));
        mockMvc.perform(get("/api/components/API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    @Test
    void unknownRequestReturns404AndInvalidBodyReturns400() throws Exception {
        mockMvc.perform(get("/api/requests/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/reservations/NOPE/issue")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestNo\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredReservationReleasedViaEndpoint() throws Exception {
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("API-2")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON.replace("RQ-API-1", "RQ-API-2")))
                .andExpect(status().isCreated());

        clock.advance(Duration.ofHours(2));
        mockMvc.perform(post("/api/reservations/release-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(1));
        mockMvc.perform(get("/api/components/API-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }
}
