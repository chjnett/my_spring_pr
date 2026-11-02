package com.secureflow.secureflow.global.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsSafeClientCorrelationIdDuringRequestAndReturnsIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "client-request_2026-11-02");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                observed.set(MDC.get(CorrelationIdContext.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(observed).hasValue("client-request_2026-11-02");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .isEqualTo("client-request_2026-11-02");
        assertThat(MDC.get(CorrelationIdContext.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeCorrelationIdInsteadOfPuttingItInLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "../../unsafe value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                observed.set(MDC.get(CorrelationIdContext.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(observed.get())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(observed.get());
        assertThat(MDC.get(CorrelationIdContext.MDC_KEY)).isNull();
    }
}
