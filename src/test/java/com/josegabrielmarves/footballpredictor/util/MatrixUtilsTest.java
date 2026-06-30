package com.josegabrielmarves.footballpredictor.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para {@link MatrixUtils}.
 * <p>
 * Cubre: topN (count, orden, empty, n mayor que matriz), findMax y clone.
 */
class MatrixUtilsTest {

    /** Matriz 3×3 con valores conocidos para tests deterministas. */
    private static final double[][] MATRIX_3x3 = {
        {0.05, 0.12, 0.03},
        {0.08, 0.20, 0.07},
        {0.02, 0.10, 0.06}
    };

    /** Máximos ordenados de MATRIX_3x3: 0.20 (1,1), 0.12 (0,1), 0.10 (2,1), 0.08 (1,0). */
    private static final int EXPECTED_TOP_N = 4;

    // ──────────────────────────────────────────────────────────────────────────
    // topN
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void testTopN_returnsCorrectCount() {
        List<MatrixUtils.ScoredCell> top = MatrixUtils.topN(MATRIX_3x3, 3);
        assertEquals(3, top.size(), "topN(3) debe devolver exactamente 3 elementos");
    }

    @Test
    void testTopN_orderedDescending() {
        List<MatrixUtils.ScoredCell> top = MatrixUtils.topN(MATRIX_3x3, 3);

        // Verificar orden descendente
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).value() >= top.get(i).value(),
                    "Los valores deben estar en orden descendente");
        }

        // Verificar valores específicos
        assertEquals(0.20, top.get(0).value(), 1e-9, "Primero debe ser 0.20");
        assertEquals(1,    top.get(0).row(), "Fila del máximo");
        assertEquals(1,    top.get(0).col(), "Columna del máximo");

        assertEquals(0.12, top.get(1).value(), 1e-9, "Segundo debe ser 0.12");
        assertEquals(0,    top.get(1).row());
        assertEquals(1,    top.get(1).col());

        assertEquals(0.10, top.get(2).value(), 1e-9, "Tercero debe ser 0.10");
        assertEquals(2,    top.get(2).row());
        assertEquals(1,    top.get(2).col());
    }

    @Test
    void testTopN_emptyMatrix() {
        double[][] empty = {};
        List<MatrixUtils.ScoredCell> top = MatrixUtils.topN(empty, 3);
        assertTrue(top.isEmpty(), "Matriz vacía debe devolver lista vacía");
    }

    @Test
    void testTopN_nLargerThanMatrix() {
        // n = 10 pero solo hay 9 celdas → debe devolver las 9 celdas sin errores
        double[][] small = {{0.3, 0.7}, {0.4, 0.6}};
        List<MatrixUtils.ScoredCell> top = MatrixUtils.topN(small, 10);
        assertEquals(4, top.size(), "Con n > celdas, debe devolver todas las celdas");
        assertFalse(top.isEmpty());
    }

    @Test
    void testTopN_nCero() {
        List<MatrixUtils.ScoredCell> top = MatrixUtils.topN(MATRIX_3x3, 0);
        assertTrue(top.isEmpty(), "n=0 debe devolver lista vacía");
    }

    @Test
    void testTopN_nNegativo() {
        List<MatrixUtils.ScoredCell> top = MatrixUtils.topN(MATRIX_3x3, -1);
        assertTrue(top.isEmpty(), "n negativo debe devolver lista vacía");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findMax
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void testFindMax_returnsCorrectCell() {
        MatrixUtils.ScoredCell max = MatrixUtils.findMax(MATRIX_3x3);
        assertNotNull(max);
        assertEquals(1, max.row(), "Fila del máximo");
        assertEquals(1, max.col(), "Columna del máximo");
        assertEquals(0.20, max.value(), 1e-9);
    }

    @Test
    void testFindMax_emptyMatrix() {
        double[][] empty = {};
        assertNull(MatrixUtils.findMax(empty), "Matriz vacía debe devolver null");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // clone
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void testClone_deepCopy() {
        double[][] original = {{0.1, 0.2}, {0.3, 0.4}};
        double[][] copy = MatrixUtils.clone(original);

        // Verificar igualdad
        assertNotNull(copy);
        assertEquals(original.length, copy.length);
        for (int r = 0; r < original.length; r++) {
            assertArrayEquals(original[r], copy[r], 1e-9);
        }

        // Modificar copia no debe afectar original
        copy[0][0] = 0.99;
        assertEquals(0.1, original[0][0], 1e-9, "Modificar copia no debe alterar original");
    }

    @Test
    void testClone_null() {
        assertNull(MatrixUtils.clone(null), "clone(null) debe devolver null");
    }
}
