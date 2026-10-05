package com.rlibanez.eplsync.api;

import java.util.Map;
import jakarta.validation.Validation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared contract: an explicit JSON boolean and no silently ignored options. */
public final class OperationBody {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final jakarta.validation.Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private OperationBody() {}
    public static void noQuery(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isBlank())
            throw new com.rlibanez.eplsync.exception.UserInputException("Las opciones deben ir en el cuerpo de la petición");
    }
    public static <T> T read(Map<String,Object> body, Class<T> type, jakarta.servlet.http.HttpServletRequest request) {
        noQuery(request);
        if (!(body.get("dryRun") instanceof Boolean))
            throw new com.rlibanez.eplsync.exception.UserInputException("dryRun es obligatorio y debe ser booleano");
        final T input;
        try { input = JSON.convertValue(body, type); }
        catch (RuntimeException ex) { throw new com.rlibanez.eplsync.exception.UserInputException("Opciones desconocidas o inválidas", ex); }
        var violations = VALIDATOR.validate(input);
        if (!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
        return input;
    }
}
