package br.com.readowl.readowl.shared.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A resposta é igual para todos e pode ficar em cache público. Sem isso, o padrão é não guardar. Conteúdo tirado
 * do ar pode continuar visível em caches por este tempo, então a validade é curta.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicCache {

    int seconds() default 30;
}
