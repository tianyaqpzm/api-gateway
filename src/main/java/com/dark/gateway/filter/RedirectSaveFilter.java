package com.dark.gateway.filter;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class RedirectSaveFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 如果是触发登录的请求
        if (path.startsWith("/oauth2/authorization/casdoor")) {
            // 获取前端传过来的 redirect 参数
            String redirectParam = exchange.getRequest().getQueryParams().getFirst("redirect");

            if (redirectParam != null) {
                // 把原页面地址存入 Session，供登录成功后使用
                return exchange.getSession().doOnNext(session -> {
                    session.getAttributes().put("CUSTOM_REDIRECT_URI", redirectParam);
                }).then(chain.filter(exchange));
            }
        }
        return chain.filter(exchange);
    }
}