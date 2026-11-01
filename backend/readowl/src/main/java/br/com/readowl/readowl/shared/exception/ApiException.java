package br.com.readowl.readowl.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Falha prevista de um caso de uso. A mensagem é enviada ao cliente: não deve conter dados internos.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }

    public static ApiException unauthorized(String code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    /**
     * A versão informada pelo cliente não é mais a atual: outra pessoa alterou o recurso depois que ele o leu.
     */
    public static ApiException versionConflict(long currentVersion) {
        return conflict("VERSION_CONFLICT", "Este item foi alterado por outra pessoa. Recarregue e tente novamente.")
                .withProperty("currentVersion", currentVersion);
    }

    public static ApiException serviceUnavailable(String code, String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, detail);
    }

    /**
     * Acrescenta um campo à resposta de erro.
     */
    public ApiException withProperty(String name, Object value) {
        properties.put(name, value);
        return this;
    }
}
