package com.pfplaybackend.api.operations.adapter.in.web;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Returns HTTP 503 for non-admin traffic when maintenance mode is enabled.
 *
 * Bypass paths: /api/v1/admin/** , /actuator/health.
 * Bypass means "always pass through to next filter" — ignores maintenance flag.
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-features.md §6.E-1
 *       docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6.2
 */
public class MaintenanceModeFilter extends OncePerRequestFilter {

    private static final List<String> BYPASS_PATTERNS = List.of(
        "/api/v1/admin/**",
        "/actuator/health"
    );
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final MaintenanceGate gate;

    public MaintenanceModeFilter(MaintenanceGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isBypassed(request) || !gate.isUnderMaintenance()) {
            chain.doFilter(request, response);
            return;
        }
        respond503(response);
    }

    private boolean isBypassed(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : BYPASS_PATTERNS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void respond503(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        String body = "{\"message\":" + jsonString(gate.getMaintenanceMessage()) + "}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    private String jsonString(String s) {
        // Minimal JSON escape — only quotes and backslashes. Maintenance message is operator-controlled.
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
