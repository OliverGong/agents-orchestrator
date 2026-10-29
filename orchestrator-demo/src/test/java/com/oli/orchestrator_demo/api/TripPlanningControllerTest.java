/**
 * @ClassName TripPlanningControllerTest
 * @Description Web layer test (standalone MockMvc): POST /api/trips/plan
 *              delegates to the orchestrator and serializes the final
 *              context back to the client.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.oli.orchestrator_demo.model.ItineraryResponse;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ResearchResponse;
import com.oli.orchestrator_demo.model.ReviewResponse;
import com.oli.orchestrator_demo.model.TripRequest;
import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;

class TripPlanningControllerTest {

  private TripPlanningOrchestrator orchestrator;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    orchestrator = mock(TripPlanningOrchestrator.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new TripPlanningController(orchestrator)).build();
  }

  @Test
  void planDelegatesToOrchestratorAndReturnsFullContext() throws Exception {
    TripRequest request = new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture");
    OrchestratorContext planned = OrchestratorContext.start(request)
        .withResearchResponse(new ResearchResponse(
            List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen")))
        .withItineraryResponse(new ItineraryResponse(List.of(
            new ItineraryResponse.DayPlan(1, "Senso-ji", "Ueno Park", "Ramen tasting", new BigDecimal("250")))))
        .withReviewResponse(new ReviewResponse(
            true, "A well balanced trip", List.of(), List.of("Book tickets in advance")));
    when(orchestrator.planTrip(any(TripRequest.class))).thenReturn(planned);

    mockMvc.perform(post("/api/trips/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"destination":"Tokyo","numberOfDays":5,"budget":15000,"preferences":"food and culture"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.request.destination").value("Tokyo"))
        .andExpect(jsonPath("$.researchResponse.attractions[0]").value("Senso-ji"))
        .andExpect(jsonPath("$.itineraryResponse.dayPlans[0].morning").value("Senso-ji"))
        .andExpect(jsonPath("$.reviewResponse.isValid").value(true))
        .andExpect(jsonPath("$.reviewResponse.summary").value("A well balanced trip"));
  }
}
