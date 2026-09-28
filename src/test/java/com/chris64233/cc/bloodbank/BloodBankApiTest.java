package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
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
    RecallRepository recallRepository;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanUp() {
        replacementItemRepository.deleteAll();
        replacementTaskRepository.deleteAll();
        itemRepository.deleteAll();
        recallRepository.deleteAll();
        reservationRepository.deleteAll();
        requestRepository.deleteAll();
        componentRepository.deleteAll();
        clock.setInstant(TestClockConfig.T0);
    }

    private static String componentJson(String barcode) {
        return """
                {"barcode":"%s","batchNo":"BATCH-API","componentType":"RED_CELLS","aboGroup":"O","rhFactor":"POSITIVE",
                 "collectedAt":"2026-09-26T08:00:00Z","expiresAt":"2026-09-29T08:00:00Z"}
                """.formatted(barcode);
    }

    private static String componentJson(String barcode, String batch, String expiresAt) {
        return """
                {"barcode":"%s","batchNo":"%s","componentType":"RED_CELLS","aboGroup":"O","rhFactor":"POSITIVE",
                 "collectedAt":"2026-09-26T08:00:00Z","expiresAt":"%s"}
                """.formatted(barcode, batch, expiresAt);
    }

    private static final String RECALL_JSON = """
            {"eventNo":"EV-API-1","batchNo":"BATCH-OLD","reason":"API 端到端召回",
             "effectiveAt":"2026-09-27T08:00:00Z"}
            """;

    @Test
    void recallReplacementAndTerminateFlowOverHttp() throws Exception {
        // 被召回单位到期较早（FEFO 选中），替代品到期较晚但不早于最晚使用时间
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("API-OLD", "BATCH-OLD", "2026-09-27T20:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("API-NEW", "BATCH-NEW", "2026-09-29T08:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].barcode").value("API-OLD"));

        // 发布召回：立即生效；同号重放幂等，同号异内容 409
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON).content(RECALL_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.eventNo").value("EV-API-1"));
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON).content(RECALL_JSON))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON)
                        .content(RECALL_JSON.replace("BATCH-OLD", "BATCH-X")))
                .andExpect(status().isConflict());

        // 被召回单位不能发放
        mockMvc.perform(post("/api/reservations/RQ-API-1/issue"))
                .andExpect(status().isConflict());

        // 召回详情：范围与受影响申请、换血前明细
        mockMvc.perform(get("/api/recalls/EV-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope[0].barcode").value("API-OLD"))
                .andExpect(jsonPath("$.scopeStatusCounts.RECALLED").value(1))
                .andExpect(jsonPath("$.affectedRequests[0].requestNo").value("RQ-API-1"))
                .andExpect(jsonPath("$.affectedRequests[0].status").value("PENDING"))
                .andExpect(jsonPath("$.affectedRequests[0].items[0].removed.barcode").value("API-OLD"))
                .andExpect(jsonPath("$.affectedRequests[0].items[0].replacement").doesNotExist());

        // 执行换血：替代品占用，阻断解除
        mockMvc.perform(post("/api/recalls/EV-API-1/requests/RQ-API-1/replace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].replacement.barcode").value("API-NEW"));
        mockMvc.perform(get("/api/requests/RQ-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation.issueBlocked").value(false))
                .andExpect(jsonPath("$.reservation.items[0].barcode").value("API-NEW"))
                .andExpect(jsonPath("$.replacementTasks[0].status").value("COMPLETED"));

        // 重复执行返回已有结果
        mockMvc.perform(post("/api/recalls/EV-API-1/requests/RQ-API-1/replace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 发放替代品
        mockMvc.perform(post("/api/reservations/RQ-API-1/issue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(get("/api/components/API-NEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(get("/api/components/API-OLD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECALLED"));
    }

    @Test
    void insufficientReplacementCanTerminateViaHttpAndUnknownRecallReturns404() throws Exception {
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("API-T", "BATCH-T", "2026-09-27T20:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVATION_JSON.replace("RQ-API-1", "RQ-API-T")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON)
                        .content(RECALL_JSON.replace("EV-API-1", "EV-API-T").replace("BATCH-OLD", "BATCH-T")))
                .andExpect(status().isCreated());

        // 无替代品：任务停留 PENDING 并给出原因
        mockMvc.perform(post("/api/recalls/EV-API-T/requests/RQ-API-T/replace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.unresolvedReason").isNotEmpty());

        // 人工终止：预留 TERMINATED，被召回单位保持隔离
        mockMvc.perform(post("/api/recalls/EV-API-T/requests/RQ-API-T/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"临床取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));
        mockMvc.perform(get("/api/components/API-T"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECALLED"));

        mockMvc.perform(get("/api/recalls/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/recalls/tasks/999/execute")).andExpect(status().isNotFound());
    }

    @Test
    void futureRecallActivatedByManualSweepEndpoint() throws Exception {
        mockMvc.perform(post("/api/components").contentType(MediaType.APPLICATION_JSON)
                        .content(componentJson("API-F", "BATCH-F", "2026-09-29T08:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/recalls").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo":"EV-API-F","batchNo":"BATCH-F","reason":"未来生效",
                                 "effectiveAt":"2026-09-27T10:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activated").value(false));
        mockMvc.perform(post("/api/recalls/activate-due")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activated").value(0));

        clock.advance(Duration.ofHours(3));
        mockMvc.perform(post("/api/recalls/activate-due")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activated").value(1));
        mockMvc.perform(get("/api/components/API-F"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECALLED"));
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
