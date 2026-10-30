package br.com.readowl.readowl.shared.security;

import br.com.readowl.readowl.shared.logging.CorrelationIdFilter;
import br.com.readowl.readowl.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class SecurityBaselineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublicAndHidesDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void anonymousRequestToPrivatePathReceivesJsonProblem() throws Exception {
        mockMvc.perform(get("/api/v1/qualquer"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId", notNullValue()))
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/qualquer").with(user("leitora")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void sensitiveActuatorEndpointsAreNotAvailable() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env").with(user("administradora").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownPathForAuthenticatedUserReceivesJsonProblem() throws Exception {
        mockMvc.perform(get("/api/v1/inexistente").with(user("leitora")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void generatesOwnCorrelationIdIgnoringClientValue() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health").header(CorrelationIdFilter.HEADER, "forjado"))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo("forjado");
    }
}
