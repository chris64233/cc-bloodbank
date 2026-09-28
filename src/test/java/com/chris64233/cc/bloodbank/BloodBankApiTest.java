package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.repository.BatchRecallRepository;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
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
    ReplacementItemRepository replacementItemRepository;
    @Autowired
    ReplacementTaskRepository replacementTaskRepository;
    @Autowired
    BatchRecallRepository recallRepository;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanUp() {
        replacementItemRepository.deleteAll();
        replacementTaskRepository.deleteAll();
        itemRepository.deleteAll();
        reservationRepository.deleteAll();
        requestRepository.deleteAll();
        recallRepository.deleteAll();
        componentRepository.deleteAll();
        clock.setInstant(TestClockConfig.T0);
    }

    private static String componentJson(String barcode) {
        return """
                {"barcode":"%s","batchNo":"BATCH-A","componentType":"RED_CELLS","aboGroup":"O","rhFactor":"POSITIVE",
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

    private static String componentJson(String barcode, String batch, String expiresAt) {
        return """
                {"barcode":"%s","batchNo":"%s","componentType":"RED_CELLS","aboGroup":"O","rhFactor":"POSITIVE",
                 "collectedAt":"2026-09-26T08:00:00Z","expiresAt":"%s"}
                """.formatted(barcode, batch, expiresAt);
    }

    @Test
    void recallReplacementFlowOverHttp() throws Exception {
        // 被召回单位到期更早（先被预留），替代单位到期更晚
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("RC-OLD", "BATCH-OLD", "2026-09-29T00:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("RC-NEW", "BATCH-OK", "2026-09-30T00:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON.replace("RQ-API-1", "RQ-RC-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].barcode").value("RC-OLD"));

        String recallJson = """
                {"eventNo":"EV-API-1","batchNo":"BATCH-OLD","reason":"试剂污染",
                 "effectiveAt":"2026-09-27T08:00:00Z"}
                """;
        // 发布召回（即时生效）
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON).content(recallJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.tasks[0].requestNo").value("RQ-RC-1"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));
        // 同号同内容重放：返回原结果
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON).content(recallJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventNo").value("EV-API-1"));
        // 同号异内容：冲突
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON)
                        .content(recallJson.replace("试剂污染", "其他原因")))
                .andExpect(status().isConflict());

        // 召回后发放被阻止
        mockMvc.perform(post("/api/reservations/RQ-RC-1/issue"))
                .andExpect(status().isConflict());

        // 执行换血：一次性替换为较早到期的合格替代单位
        mockMvc.perform(post("/api/recalls/replacements/RQ-RC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLACED"))
                .andExpect(jsonPath("$.replacements[0].oldBarcode").value("RC-OLD"))
                .andExpect(jsonPath("$.replacements[0].newBarcode").value("RC-NEW"))
                .andExpect(jsonPath("$.replacements[0].eventNo").value("EV-API-1"));
        // 重复执行返回已有结果
        mockMvc.perform(post("/api/recalls/replacements/RQ-RC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLACED"));

        // 查询展示召回范围、受影响申请与换血前后明细
        mockMvc.perform(get("/api/recalls/EV-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value("BATCH-OLD"))
                .andExpect(jsonPath("$.tasks[0].status").value("REPLACED"));
        mockMvc.perform(get("/api/requests/RQ-RC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation.blocked").value(false))
                .andExpect(jsonPath("$.reservation.items[0].barcode").value("RC-NEW"))
                .andExpect(jsonPath("$.reservation.replacements[0].oldBarcode").value("RC-OLD"))
                .andExpect(jsonPath("$.reservation.replacements[0].newBarcode").value("RC-NEW"));

        // 换血后可发放替代单位
        mockMvc.perform(post("/api/reservations/RQ-RC-1/issue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(get("/api/components/RC-NEW"))
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(get("/api/components/RC-OLD"))
                .andExpect(jsonPath("$.status").value("RECALLED"));
    }

    @Test
    void unresolvableRecallTerminatesReservationAndReleasesGoodUnit() throws Exception {
        // 申请 2 个单位：一个属召回批次（到期更早）、一个合格；库存无富余替代单位
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("UR-OLD", "BATCH-OLD", "2026-09-29T00:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("UR-GOOD", "BATCH-OK", "2026-09-30T00:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON.replace("RQ-API-1", "RQ-UR-1")
                                .replace("\"unitCount\":1", "\"unitCount\":2")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo":"EV-API-2","batchNo":"BATCH-OLD","reason":"污染",
                                 "effectiveAt":"2026-09-27T08:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        // 人工确认无法换血：仍占用的合格单位释放回库、被召回单位保持冻结、预留终止
        mockMvc.perform(post("/api/recalls/replacements/RQ-UR-1/unresolvable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNRESOLVABLE"));
        mockMvc.perform(get("/api/requests/RQ-UR-1"))
                .andExpect(jsonPath("$.reservation.status").value("TERMINATED"));
        mockMvc.perform(get("/api/components/UR-GOOD"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mockMvc.perform(get("/api/components/UR-OLD"))
                .andExpect(jsonPath("$.status").value("RECALLED"));
        // 重复确认返回已有结果
        mockMvc.perform(post("/api/recalls/replacements/RQ-UR-1/unresolvable"))
                .andExpect(jsonPath("$.status").value("UNRESOLVABLE"));
    }
}
