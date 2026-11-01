package br.com.readowl.readowl.shared.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Para visitantes, a resposta é igual para todos e pode ficar em cache público. Sem isso, o padrão é não guardar. Quem
 * está logado pode ver também obras adultas, então a resposta dessa pessoa nunca é guardada. Conteúdo tirado do ar pode
 * continuar visível em caches por este tempo, então a validade é curta.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicCache {

    int seconds() default 30;
}
