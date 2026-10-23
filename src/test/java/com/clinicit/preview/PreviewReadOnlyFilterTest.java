package com.clinicit.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewReadOnlyFilterTest {

    private final PreviewReadOnlyFilter filter =
            new PreviewReadOnlyFilter(new ObjectMapper().registerModule(new JavaTimeModule()));

    private MockHttpServletResponse call(String method, String path, MockFilterChain chain)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        return response;
    }

    @Test
    void writesToTheApiAreRefusedWithAClearMessage() throws Exception {
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = call(method, "/api/v1/queue/entries", chain);

            assertThat(response.getStatus()).as(method).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("PREVIEW_READ_ONLY", "read-only preview");
            assertThat(chain.getRequest()).as("the request never reaches the application").isNull();
        }
    }

    @Test
    void readsPassThrough() throws Exception {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = call(method, "/api/v1/queue/entries", chain);

            assertThat(response.getStatus()).as(method).isEqualTo(200);
            assertThat(chain.getRequest()).as(method).isNotNull();
        }
    }

    @Test
    void signingInAndOutStillWork() throws Exception {
        for (String path : new String[] {"/api/v1/auth/login", "/api/v1/auth/logout"}) {
            MockFilterChain chain = new MockFilterChain();
            call("POST", path, chain);
            assertThat(chain.getRequest()).as(path).isNotNull();
        }
    }

    @Test
    void onlyTheApiIsGuarded() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        call("POST", "/ws", chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void lookalikePathsAreNotExempt() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = call("POST", "/api/v1/auth/login/../users", chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }
}
