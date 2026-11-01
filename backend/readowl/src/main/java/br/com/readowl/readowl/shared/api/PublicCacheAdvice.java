package br.com.readowl.readowl.shared.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.Duration;

/**
 * Aplica {@link PublicCache}. Respostas de erro vêm de outro método e continuam sem cache.
 */
@ControllerAdvice
class PublicCacheAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(PublicCache.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        int seconds = returnType.getMethodAnnotation(PublicCache.class).seconds();
        response.getHeaders().setCacheControl(CacheControl.maxAge(Duration.ofSeconds(seconds)).cachePublic());
        return body;
    }
}
