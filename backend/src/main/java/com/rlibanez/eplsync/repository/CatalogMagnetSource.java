package com.rlibanez.eplsync.repository;

/** Proyección para generar magnets sin cargar sinopsis y demás datos del catálogo. */
public interface CatalogMagnetSource {
    Long getEplId();
    String getTitle();
    String getLinks();
}
