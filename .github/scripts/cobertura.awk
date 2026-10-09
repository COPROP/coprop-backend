# Suma el CSV de JaCoCo y escribe una tabla Markdown con la cobertura del proyecto.
# Uso: awk -f .github/scripts/cobertura.awk build/reports/jacoco/test/jacocoTestReport.csv
#
# El CSV trae una fila por clase y estas columnas:
#   1 GROUP  2 PACKAGE  3 CLASS
#   4 INSTRUCTION_MISSED   5 INSTRUCTION_COVERED
#   6 BRANCH_MISSED        7 BRANCH_COVERED
#   8 LINE_MISSED          9 LINE_COVERED
#  10 COMPLEXITY_MISSED   11 COMPLEXITY_COVERED
#  12 METHOD_MISSED       13 METHOD_COVERED

BEGIN { FS = "," }
NR == 1 { next }
{
    im += $4; ic += $5
    bm += $6; bc += $7
    lm += $8; lc += $9
    mm += $12; mc += $13
    clases++
}
END {
    print "## Cobertura\n"
    print "| Metrica | Cubierto | Total | % |"
    print "|---|---:|---:|---:|"
    fila("Lineas", lc, lm + lc)
    fila("Ramas", bc, bm + bc)
    fila("Instrucciones", ic, im + ic)
    fila("Metodos", mc, mm + mc)
    printf "\n%d %s.\n", clases, (clases == 1 ? "clase analizada" : "clases analizadas")
}

# Una metrica sin nada que medir (ramas en un codigo sin condicionales) sale como n/a y no
# como 0%, que se leeria como un problema donde no lo hay.
function fila(etiqueta, cubierto, total) {
    if (total == 0) { printf "| %s | 0 | 0 | n/a |\n", etiqueta; return }
    printf "| %s | %d | %d | %.1f%% |\n", etiqueta, cubierto, total, cubierto * 100 / total
}
