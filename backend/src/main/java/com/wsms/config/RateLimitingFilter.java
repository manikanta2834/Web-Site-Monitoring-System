package com.wsms.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final int MAX_REQUESTS = 5;
    private static final long TIME_WINDOW_MS = TimeUnit.MINUTES.toMillis(1); // 1 minute window

    // In-memory cache tracking client IP requests
    private final ConcurrentHashMap<String, TokenBucket> ipCache = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (request instanceof HttpServletRequest httpServletRequest && response instanceof HttpServletResponse httpServletResponse) {
            String clientIp = getClientIp(httpServletRequest);
            String path = httpServletRequest.getRequestURI();

            // Only enforce rate limits on login/auth requests to prevent DDoS/abuse of auth endpoint
            if (path.startsWith("/api/auth/")) {
                TokenBucket bucket = ipCache.computeIfAbsent(clientIp, k -> new TokenBucket(MAX_REQUESTS, TIME_WINDOW_MS));

                if (!bucket.tryConsume()) {
                    log.warn("Rate limit breached for IP: {} on URI: {}", clientIp, path);
                    httpServletResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    httpServletResponse.setContentType("application/json");
                    httpServletResponse.getWriter().write("{\"message\": \"Too many requests. Please try again in 1 minute.\"}");
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }

    private static class TokenBucket {
        private final int maxTokens;
        private final long refillIntervalMs;
        private double tokens;
        private long lastRefillTime;

        public TokenBucket(int maxTokens, long refillIntervalMs) {
            this.maxTokens = maxTokens;
            this.refillIntervalMs = refillIntervalMs;
            this.tokens = maxTokens;
            this.lastRefillTime = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsedTime = now - lastRefillTime;
            
            // Add tokens proportionally to elapsed time
            double tokensToAdd = ((double) elapsedTime / refillIntervalMs) * maxTokens;
            if (tokensToAdd > 0) {
                tokens = Math.min(maxTokens, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }
}
