package com.rlibanez.eplsync.ordering;

import com.rlibanez.eplsync.exception.UserInputException;

/** Shared validation for inclusive/exclusive filters. */
public final class StateOperator {
    private StateOperator() {}
    public static boolean excluded(String operator) {
        if(operator==null || operator.equals("eq")) return false;
        if(operator.equals("ne")) return true;
        throw new UserInputException("Operador de filtro inválido");
    }
}
