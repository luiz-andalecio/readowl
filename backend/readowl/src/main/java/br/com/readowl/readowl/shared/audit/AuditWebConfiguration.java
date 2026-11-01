package br.com.readowl.readowl.shared.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class AuditWebConfiguration implements WebMvcConfigurer {

    private final AuditInterceptor interceptor;
    private final AccessRecordInterceptor accessRecords;

    AuditWebConfiguration(AuditInterceptor interceptor, AccessRecordInterceptor accessRecords) {
        this.interceptor = interceptor;
        this.accessRecords = accessRecords;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(accessRecords).addPathPatterns("/api/v1/**");
    }
}
