package com.josegabrielmarves.footballpredictor.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilidades para operaciones sobre matrices de probabilidad (golesLocal × golesVisitante).
 * <p>
 * Proporciona métodos recursivos para extraer los top N valores de una matriz,
 * búsqueda del máximo global, y clonado profundo.
 * <p>
 * Todos los métodos son estáticos y la clase no es instanciable.
 */
public final class MatrixUtils {

    // ──────────────────────────────────────────────────────────────────────────
    // Record: celda de la matriz con coordenadas y valor
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Una celda de la matriz con sus coordenadas (fila, columna) y valor.
     *
     * @param row   índice de fila (goles local)
     * @param col   índice de columna (goles visitante)
     * @param value probabilidad de ese marcador [0, 1]
     */
    public record ScoredCell(int row, int col, double value) {}

    // ──────────────────────────────────────────────────────────────────────────
    // Constructor privado — clase utilitaria no instanciable
    // ──────────────────────────────────────────────────────────────────────────

    private MatrixUtils() {}

    // ──────────────────────────────────────────────────────────────────────────
    // topN — recursivo
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Extrae los {@code n} valores más grandes de la matriz en orden descendente.
     * <p>
     * <b>Algoritmo recursivo:</b>
     * <ol>
     *   <li>Caso base: si {@code n <= 0} o la matriz está vacía, devuelve lista vacía.</li>
     *   <li>Encuentra el máximo global en la matriz.</li>
     *   <li>Lo añade a la lista de resultados.</li>
     *   <li>Marca la celda como {@code -1.0} (visitada) en una copia mutable.</li>
     *   <li>Llama recursivamente con {@code n - 1}.</li>
     * </ol>
     *
     * @param matrix matriz de probabilidades [golesLocal][golesVisitante]
     * @param n      número de celdas a extraer
     * @return lista ordenada descendente con los N mejores valores;
     *         lista vacía si {@code n <= 0} o matriz vacía
     * @throws NullPointerException si {@code matrix} es {@code null}
     */
    public static List<ScoredCell> topN(double[][] matrix, int n) {
        int rows = matrix.length;
        int cols = (rows > 0) ? matrix[0].length : 0;

        // Caso base: matriz vacía o n no positivo
        if (rows == 0 || cols == 0 || n <= 0) {
            return List.of();
        }

        // Copia mutable para no modificar el original
        double[][] copy = clone(matrix);

        List<ScoredCell> result = new ArrayList<>();
        topNRecursive(copy, rows, cols, result, n);
        return result;
    }

    /**
     * Paso recursivo: encuentra el máximo, lo guarda, y llama con {@code remaining - 1}.
     *
     * @param m         matriz mutable (se modifica in-place marcando celdas como {@code -1.0})
     * @param rows      número de filas
     * @param cols      número de columnas
     * @param result    lista acumuladora de resultados
     * @param remaining cuántos valores quedan por extraer
     */
    private static void topNRecursive(double[][] m, int rows, int cols,
                                       List<ScoredCell> result, int remaining) {
        // ── CASO BASE ──
        if (remaining <= 0) {
            return;
        }

        // ── PASO RECURSIVO ──

        // 1. Encontrar el máximo global en la matriz
        ScoredCell max = findMaxInMatrix(m, rows, cols);

        // Si no hay más valores positivos, terminamos
        if (max == null || max.value() <= 0.0) {
            return;
        }

        // 2. Guardar el resultado
        result.add(max);

        // 3. Marcar la celda como visitada (no volverá a ser elegida)
        m[max.row()][max.col()] = -1.0;

        // 4. Llamada recursiva con remaining - 1
        topNRecursive(m, rows, cols, result, remaining - 1);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findMax
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Encuentra el valor máximo global en la matriz.
     *
     * @param matrix matriz de probabilidades [golesLocal][golesVisitante]
     * @return celda con el valor máximo, o {@code null} si la matriz está vacía
     * @throws NullPointerException si {@code matrix} es {@code null}
     */
    public static ScoredCell findMax(double[][] matrix) {
        int rows = matrix.length;
        if (rows == 0) {
            return null;
        }
        int cols = matrix[0].length;
        if (cols == 0) {
            return null;
        }
        return findMaxInMatrix(matrix, rows, cols);
    }

    /**
     * Implementación común de búsqueda de máximo.
     *
     * @param m    matriz a recorrer
     * @param rows número de filas
     * @param cols número de columnas
     * @return celda con el valor máximo, o {@code null} si no hay valores positivos
     */
    private static ScoredCell findMaxInMatrix(double[][] m, int rows, int cols) {
        int maxR = -1;
        int maxC = -1;
        double maxVal = -1.0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (m[r][c] > maxVal) {
                    maxVal = m[r][c];
                    maxR = r;
                    maxC = c;
                }
            }
        }

        return maxR >= 0 ? new ScoredCell(maxR, maxC, maxVal) : null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // clone
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Crea una copia profunda (defensiva) de la matriz.
     * <p>
     * Cada fila se clona individualmente, de modo que modificar la copia
     * no afecta al original.
     *
     * @param matrix matriz original
     * @return copia profunda de la matriz, o {@code null} si la entrada es {@code null}
     */
    public static double[][] clone(double[][] matrix) {
        if (matrix == null) {
            return null;
        }
        int rows = matrix.length;
        double[][] result = new double[rows][];
        for (int r = 0; r < rows; r++) {
            result[r] = matrix[r].clone();
        }
        return result;
    }
}
