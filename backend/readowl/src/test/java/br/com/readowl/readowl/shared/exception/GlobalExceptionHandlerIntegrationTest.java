package br.com.readowl.readowl.shared.exception;

import br.com.readowl.readowl.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource({
            "api-exception,   409, BOOK_ALREADY_PUBLISHED",
            "optimistic-lock, 409, CONCURRENT_MODIFICATION",
            "data-integrity,  409, DATA_CONFLICT",
            "access-denied,   403, ACCESS_DENIED",
            "temporary,       503, TEMPORARILY_UNAVAILABLE",
            "unexpected,      500, INTERNAL_ERROR"
    })
    void mapsFailuresToStableProblemWithoutLeakingInternals(String scenario, int status, String code) throws Exception {
        mockMvc.perform(get("/test/errors/" + scenario).with(user("leitora")))
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString("leitora@example.com"))));
    }

    @Test
    void reportsInvalidFields() throws Exception {
        mockMvc.perform(post("/test/errors/validation").with(user("leitora")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void reportsMalformedBody() throws Exception {
        mockMvc.perform(post("/test/errors/validation").with(user("leitora")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
