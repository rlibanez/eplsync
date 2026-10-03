package com.rlibanez.eplsync.filter;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.WebDataBinder;

/** A comma in an author/title is text, not a separator; repeat query parameters instead. */
@ControllerAdvice
public class CatalogFilterBinding {
    @InitBinder
    public void bind(WebDataBinder binder) {
        if (!(binder.getTarget() instanceof CatalogBookFilter) && !"catalogBookFilter".equals(binder.getObjectName())) return;
        binder.registerCustomEditor(String[].class, new java.beans.PropertyEditorSupport() {
            @Override public void setAsText(String text) { setValue(new String[]{text}); }
        });
    }
}
