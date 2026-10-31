package br.com.readowl.readowl.shared.audit;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * O que os serviços informam a {@link AuditInterceptor} sobre a requisição atual. Fora de uma requisição, como
 * em tarefas agendadas, não faz nada.
 */
public final class AuditContext {

    private static final String OVERRIDE = AuditContext.class.getName() + ".override";
    private static final String RECORDED = AuditContext.class.getName() + ".recorded";

    private AuditContext() {
    }

    /**
     * Um administrador está agindo sobre conteúdo de outra pessoa.
     */
    public static void markOverride() {
        set(OVERRIDE);
    }

    static void markRecorded() {
        set(RECORDED);
    }

    static boolean isOverride() {
        return isSet(OVERRIDE);
    }

    static boolean isRecorded() {
        return isSet(RECORDED);
    }

    private static void set(String attribute) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(attribute, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private static boolean isSet(String attribute) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request != null && request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST) != null;
    }
}
