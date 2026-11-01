package br.com.readowl.readowl.auth.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * As sessões guardam objetos serializados em Java, que podem ficar ilegíveis ao atualizar o framework ou mover
 * uma classe. Por padrão, isso responderia 500 a cada requisição de quem estava logado até a sessão expirar. Aqui,
 * um atributo ilegível é tratado como ausente: a pessoa só precisa entrar de novo.
 */
@Configuration(proxyBeanMethods = false)
class SessionSerializationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionSerializationConfiguration.class);

    @Bean
    @Qualifier("springSessionConversionService")
    ConversionService springSessionConversionService() {
        SerializingConverter serializer = new SerializingConverter();
        DeserializingConverter deserializer = new DeserializingConverter();
        GenericConversionService service = new GenericConversionService();
        service.addConverter(Object.class, byte[].class, serializer);
        service.addConverter(byte[].class, Object.class, (byte[] bytes) -> {
            try {
                return deserializer.convert(bytes);
            } catch (RuntimeException ex) {
                log.warn("Atributo de sessao ilegivel: causa={}", ex.getClass().getSimpleName());
                return null;
            }
        });
        return service;
    }
}
