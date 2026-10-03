import io.github.tayguara.ci.Args
import io.github.tayguara.ci.GateRecords
import io.github.tayguara.ci.Reports

/**
 * Fails the build when line coverage, read from the Cobertura report the unit tests wrote,
 * is below `min` (0 to 100). The report is parsed in the library, so no xmllint is needed.
 *
 *   coverageGate(report: 'reports/coverage/cobertura.xml', min: 80)
 */
def call(Map args) {
    String report = Args.safePath(Args.required(args, 'report') as String)
    BigDecimal min = Args.percent(Args.required(args, 'min'))
    String recordFile = GateRecords.recordPath(env.GATE_RECORDS, 'Coverage')

    if (!fileExists(report)) {
        writeFile(file: recordFile, text: GateRecords.toJson(
                [name: 'Coverage', passed: false, exitCode: 1, report: report, detail: 'report not found']))
        error("Coverage gate: report ${report} not found. Did the Unit tests stage run?")
    } else {
        BigDecimal actual = null
        String unreadable = null
        try {
            actual = Reports.coberturaLinePercent(readFile(report))
        } catch (IllegalArgumentException e) {
            unreadable = e.message
        }
        if (unreadable != null) {
            writeFile(file: recordFile, text: GateRecords.toJson(
                    [name: 'Coverage', passed: false, exitCode: 1, report: report, detail: 'report not readable']))
            error("Coverage gate: report ${report} is not readable: ${unreadable}")
        } else {
            boolean passed = actual >= min
            echo "Line coverage: ${actual}% (minimum ${min}%)"
            writeFile(file: recordFile, text: GateRecords.toJson([name: 'Coverage', passed: passed,
                    exitCode: passed ? 0 : 1, report: report, detail: "${actual}% (minimum ${min}%)".toString()]))
            if (!passed) {
                error("Coverage gate: line coverage ${actual}% is below the ${min}% minimum. " +
                        'Add tests or lower the threshold in a reviewed change.')
            }
        }
    }
}
