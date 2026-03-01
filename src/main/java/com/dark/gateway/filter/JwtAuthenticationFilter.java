package com.dark.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

@Slf4j
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final PathMatcher pathMatcher = new AntPathMatcher();

    // In production, move this to config/Nacos
    private static final String SECRET = "your-256-bit-secret-your-256-bit-secret";

    @Autowired
    private IgnoreWhiteProperties ignoreWhiteProperties; // 注入白名单配置

    @org.springframework.beans.factory.annotation.Value("${spring.security.login-url}")
    private String loginUrl;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Skip auth for login or public endpoints if needed
        if (request.getURI().getPath().startsWith("/api/public")) {
            return chain.filter(exchange);
        }
        String url = exchange.getRequest().getURI().getPath();
        // 2. 🔥【关键修复】检查白名单：如果匹配，直接放行，不做 Token 校验
        if (isWhiteList(url)) {
            return chain.filter(exchange);
        }

        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            return onError(exchange, "Missing Authorization Header", HttpStatus.UNAUTHORIZED);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return onError(exchange, "Invalid Authorization Header", HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);
        try {
            validateToken(token);
            // Optionally parse claims and add to headers
            // Claims claims = parseToken(token);
            // request.mutate().header("X-UserId", claims.getSubject()).build();
        } catch (Exception e) {
            log.error("Token validation failed: {}", e.getMessage());
            return onError(exchange, "Invalid Token", HttpStatus.UNAUTHORIZED);
        }

        return chain.filter(exchange);
    }

    private void validateToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        // Return JSON error with redirect URL
        String jsonResponse = String.format("{\"url\": \"%s\", \"message\": \"%s\"}", loginUrl, err);
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        response.getHeaders().add("Content-Type", "application/json");

        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 判断路径是否在白名单中
     */
    private boolean isWhiteList(String url) {
        // 遍历配置中的白名单列表
        for (String pattern : ignoreWhiteProperties.getUrls()) {
            // 使用 AntPathMatcher 支持 ** 通配符
            if (pathMatcher.match(pattern, url)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return -100; // High priority
    }
}
