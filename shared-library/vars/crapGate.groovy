import io.github.tayguara.ci.Args
import io.github.tayguara.ci.GateRecords
import io.github.tayguara.ci.Reports

/**
 * Fails the build when any method has a CRAP score (complexity combined with coverage)
 * above `max`. Reads the crap4j report the unit tests wrote.
 *
 *   crapGate(report: 'reports/coverage/crap4j.xml', max: 30)
 */
def call(Map args) {
    String report = Args.safePath(Args.required(args, 'report') as String)
    BigDecimal max = Args.positiveNumber(args.max == null ? 30 : args.max)
    String recordFile = GateRecords.recordPath(env.GATE_RECORDS, 'Complexity (CRAP)')

    if (!fileExists(report)) {
        writeFile(file: recordFile, text: GateRecords.toJson(
                [name: 'Complexity (CRAP)', passed: false, exitCode: 1, report: report, detail: 'report not found']))
        error("Complexity gate: report ${report} not found. Did the Unit tests stage run?")
    } else {
        Map top = [:]
        List<Map> offenders = []
        String unreadable = null
        try {
            String xml = readFile(report)
            top = Reports.crapTop(xml)
            offenders = Reports.crapOffenders(xml, max)
        } catch (IllegalArgumentException e) {
            unreadable = e.message
        }
        if (unreadable != null) {
            writeFile(file: recordFile, text: GateRecords.toJson([name: 'Complexity (CRAP)', passed: false,
                    exitCode: 1, report: report, detail: 'report not readable']))
            error("Complexity gate: report ${report} is not readable: ${unreadable}")
        } else {
            // A report with no methods passes. That is safe because the Coverage gate runs first and
            // fails on a report that does not look like a real coverage run.
            String highest = top.isEmpty() ? 'no methods in report' : "highest ${top.crap} (maximum ${max})"
            echo top.isEmpty() ? 'Highest CRAP: none (no methods in report)'
                               : "Highest CRAP: ${top.crap} (${top.method}), maximum ${max}"
            writeFile(file: recordFile, text: GateRecords.toJson([name: 'Complexity (CRAP)',
                    passed: offenders.isEmpty(), exitCode: offenders.isEmpty() ? 0 : 1,
                    report: report, detail: highest.toString()]))
            if (!offenders.isEmpty()) {
                String shown = offenders.take(5).collect { Map m -> "${m.method} (${m.crap})" }.join(', ')
                error("Complexity gate: ${offenders.size()} method(s) above CRAP ${max}: ${shown}" +
                        (offenders.size() > 5 ? ', ...' : '.') + ' Cover them with tests or simplify them.')
            }
        }
    }
}
