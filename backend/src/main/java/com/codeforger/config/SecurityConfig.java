package com.codeforger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("https://*.github.io", "http://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // allowCredentials(true) intentionally omitted to tighten security

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        // Run before PasscodeFilter to ensure 401s carry CORS headers back to the browser
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Component
    @Order(1) // Runs after HIGHEST_PRECEDENCE CorsFilter
    public static class PasscodeFilter extends OncePerRequestFilter {
        private final String passcode;

        public PasscodeFilter(@Value("${APP_SECRET_PASSCODE}") String passcode) {
            this.passcode = passcode;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {

            if (!request.getRequestURI().startsWith("/api/")) {
                chain.doFilter(request, response);
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }

            String authHeader = request.getHeader("Authorization");
            String apiKeyHeader = request.getHeader("X-API-Key");

            String provided = apiKeyHeader;
            if (provided == null && authHeader != null && authHeader.startsWith("Bearer ")) {
                provided = authHeader.substring(7);
            }

            if (provided == null || !MessageDigest.isEqual(
                    passcode.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid Passcode");
                return;
            }

            chain.doFilter(request, response);
        }
    }
}
