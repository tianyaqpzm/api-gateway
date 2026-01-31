package com.dark.gateway.filter;

import java.nio.charset.StandardCharsets;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class AiBillingFilter extends AbstractGatewayFilterFactory<AiBillingFilter.Config> {

    public AiBillingFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpResponse originalResponse = exchange.getResponse();
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();

            ServerHttpResponseDecorator decoratedResponse =
                    new ServerHttpResponseDecorator(originalResponse) {
                        @Override
                        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                            if (body instanceof Flux) {
                                Flux<? extends DataBuffer> fluxBody =
                                        (Flux<? extends DataBuffer>) body;

                                return super.writeWith(fluxBody.buffer().map(dataBuffers -> {
                                    // Join all data buffers into one
                                    DataBuffer join = bufferFactory.join(dataBuffers);
                                    byte[] content = new byte[join.readableByteCount()];
                                    join.read(content);
                                    DataBufferUtils.release(join);

                                    String responseBody =
                                            new String(content, StandardCharsets.UTF_8);

                                    // Audit Logic: Parse responseBody for "usage"
                                    if (responseBody.contains("\"usage\"")) {
                                        log.info(
                                                "AI Billing Audit: Token usage detected in response.");
                                        // Here you would parse the JSON and save to DB
                                    }

                                    return bufferFactory.wrap(content);
                                }));
                            }
                            return super.writeWith(body);
                        }
                    };

            return chain.filter(exchange.mutate().response(decoratedResponse).build());
        };
    }

    public static class Config {
        // Configuration properties
    }
}
