package com.josegabrielmarves.footballpredictor.api.datasource;

import com.josegabrielmarves.footballpredictor.model.Match;
import java.util.List;

/**
 * Contrato para cualquier fuente de datos de fútbol.
 * Permite sustituir proveedores sin modificar el modelo.
 */
public interface DataProvider {
    List<Match> getWorldCupMatches(int year);
    boolean isAvailable();
    String getName();
}