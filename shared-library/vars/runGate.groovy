import io.github.tayguara.ci.Args
import io.github.tayguara.ci.GateRecords
import io.github.tayguara.ci.Reports

/**
 * Runs one quality gate: the command writes a machine-readable report, a gate record is saved,
 * and a non-zero exit code fails the build with a message that says where to look.
 *
 *   runGate(name: 'Static analysis', type: 'phpstan',
 *           command: 'vendor/bin/phpstan analyse ...', report: 'reports/phpstan.json')
 *
 * `command` runs in a shell, so it must come from the reviewed Jenkinsfile, never from a build parameter.
 * `type` (checkstyle, phpstan, composer-audit, junit) adds a one-line detail from the report.
 */
def call(Map args) {
    String name = Args.required(args, 'name')
    String command = Args.required(args, 'command')
    String report = Args.safePath(Args.required(args, 'report') as String)
    String type = args.type

    String reportDir = report.contains('/') ? report.substring(0, report.lastIndexOf('/')) : '.'
    int status = sh(label: name, returnStatus: true, script: "mkdir -p '${reportDir}' && ${command} > '${report}'")

    String detail = ''
    if (type) {
        detail = fileExists(report) ? Reports.summarize(type, readFile(report)) : 'report not found'
    }
    writeFile(file: GateRecords.recordPath(env.GATE_RECORDS, name), text: GateRecords.toJson(
            [name: name, passed: status == 0, exitCode: status, report: report, detail: detail]))

    if (status != 0) {
        error("${name} gate failed (exit code ${status}). Findings: ${report}")
    }
}
