/**
 * @ClassName GlobalExceptionHandlerTest
 * @Description Verifies the HTTP error mapping: malformed input -> 400,
 *              unparseable model output -> 502, unavailable model backend ->
 *              503, each with a structured JSON error body.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;
import tools.jackson.core.exc.StreamReadException;

class GlobalExceptionHandlerTest {

  private TripPlanningOrchestrator orchestrator;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    orchestrator = mock(TripPlanningOrchestrator.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new TripPlanningController(orchestrator))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void invalidInputMapsTo400() throws Exception {
    mockMvc.perform(post("/api/trips/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"destination":"Tokyo","numberOfDays":0,"budget":15000,"preferences":"food"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Invalid request"));
  }

  @Test
  void malformedBodyMapsTo400() throws Exception {
    mockMvc.perform(post("/api/trips/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Malformed request body"));
  }

  @Test
  void unparseableModelOutputMapsTo502() throws Exception {
    when(orchestrator.planTrip(any())).thenThrow(new StreamReadException("Unexpected character ('`')"));

    mockMvc.perform(post("/api/trips/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"destination":"Tokyo","numberOfDays":5,"budget":15000,"preferences":"food"}
                """))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.status").value(502))
        .andExpect(jsonPath("$.error").value("Model returned unparseable output"));
  }

  @Test
  void unreachableBackendMapsTo503() throws Exception {
    when(orchestrator.planTrip(any()))
        .thenThrow(new ResourceAccessException("Connection refused: localhost:11434"));

    mockMvc.perform(post("/api/trips/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"destination":"Tokyo","numberOfDays":5,"budget":15000,"preferences":"food"}
                """))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.error").value("Model backend unavailable"));
  }

  @Test
  void backendClientErrorMapsTo503() throws Exception {
    when(orchestrator.planTrip(any()))
        .thenThrow(new RestClientException("404 model 'qwen:7b' not found"));

    mockMvc.perform(post("/api/trips/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"destination":"Tokyo","numberOfDays":5,"budget":15000,"preferences":"food"}
                """))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void unknownResourceMapsTo404InsteadOf500() {
    ResponseEntity<GlobalExceptionHandler.ApiErrorResponse> response =
        new GlobalExceptionHandler().unknownPath(
            new NoResourceFoundException(HttpMethod.GET, "actuator/health", "No static resource actuator/health"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("Not found");
  }
}
