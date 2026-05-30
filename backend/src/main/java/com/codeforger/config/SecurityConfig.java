package com.codeforger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("https://*.github.io", "http://localhost:*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Component
    @Order(1)
    public static class PasscodeFilter extends OncePerRequestFilter {
        private final String passcode;

        public PasscodeFilter(@Value("${APP_SECRET_PASSCODE:dev-secret}") String passcode) {
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

            boolean isValid = passcode.equals(apiKeyHeader) ||
                    (authHeader != null && authHeader.replace("Bearer ", "").equals(passcode));

            if (!isValid) {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid Passcode");
                return;
            }

            chain.doFilter(request, response);
        }
    }
}
