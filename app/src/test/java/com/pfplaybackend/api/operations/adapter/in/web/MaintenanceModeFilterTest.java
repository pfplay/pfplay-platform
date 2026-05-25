package com.pfplaybackend.api.operations.adapter.in.web;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaintenanceModeFilterTest {

    @Mock
    MaintenanceGate gate;

    MaintenanceModeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MaintenanceModeFilter(gate);
    }

    @Test
    void passes_through_when_disabled() throws ServletException, IOException {
        when(gate.isUnderMaintenance()).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // Chain WAS invoked (filter passed through). Filter did NOT touch the response.
        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(res.getContentAsString()).isEmpty();
    }

    @Test
    void returns_503_with_message_when_enabled_and_path_not_bypassed() throws ServletException, IOException {
        when(gate.isUnderMaintenance()).thenReturn(true);
        when(gate.getMaintenanceMessage()).thenReturn("점검중");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("점검중");
        assertThat(chain.getRequest()).isNull(); // chain not invoked
    }

    @Test
    void bypasses_admin_paths_even_when_enabled() throws ServletException, IOException {
        // lenient: bypass check runs before gate lookup — stub may not be consumed
        lenient().when(gate.isUnderMaintenance()).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/system/config/maintenance");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // Chain invoked, filter did NOT short-circuit despite maintenance enabled.
        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(res.getContentAsString()).isEmpty();
    }

    @Test
    void bypasses_actuator_health_even_when_enabled() throws ServletException, IOException {
        // lenient: bypass check runs before gate lookup — stub may not be consumed
        lenient().when(gate.isUnderMaintenance()).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(res.getContentAsString()).isEmpty();
    }

    @Test
    void does_not_bypass_admin_lookalike_path() throws ServletException, IOException {
        when(gate.isUnderMaintenance()).thenReturn(true);
        when(gate.getMaintenanceMessage()).thenReturn("점검중");

        // Path contains "admin" but is not under /api/v1/admin
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/admin-friend-list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
    }
}
