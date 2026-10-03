package io.github.tayguara.ci

import com.cloudbees.groovy.cps.NonCPS

/**
 * Renders the build summary (markdown) and the one-line build description from the facts of
 * the build and the sorted gate records. Pure functions, no Jenkins steps.
 */
class BuildSummary {

    /** Records written by deployBlueGreen. They are steps of the release, not quality gates. */
    private static final List<String> DEPLOY_STEPS = ['Deploy', 'Rollback']

    @NonCPS
    static String render(Map facts, List<Map> records) {
        List<String> lines = [
                "# ${facts.job} #${facts.number}: ${facts.result}".toString(),
                '',
                "- Commit: `${facts.commit}`".toString(),
                "- Started by: ${facts.triggeredBy ?: 'automated trigger'}".toString(),
                "- Duration: ${facts.duration}".toString(),
                '']
        if (records.isEmpty()) {
            lines << 'No gate records found. The build stopped before the first gate, or the gates did not run.'
            return lines.join('\n') + '\n'
        }
        lines << '| Gate | Result | Detail |'
        lines << '| --- | --- | --- |'
        for (Map record : records) {
            lines << "| ${cell(record.name)} | ${verdict(record)} | ${cell(detailOf(record))} |".toString()
        }
        lines << ''
        lines << closing(facts, records)
        return lines.join('\n') + '\n'
    }

    @NonCPS
    static String oneLine(Map facts, List<Map> records) {
        String body
        Map failed = firstFailed(records)
        if (records.isEmpty()) {
            body = 'no gate records'
        } else if (failed != null) {
            body = "stopped at ${failed.name}"
        } else if (facts.result != 'SUCCESS') {
            body = 'failed outside a recorded gate; see console'
        } else if (!rollbackOnly(records).isEmpty()) {
            body = 'rolled back ' + rollbackDescription(rollbackOnly(records).detail as String)
        } else {
            List<Map> gates = qualityGates(records)
            body = "${gates.count { Map r -> r.passed == true }}/${gates.size()} gates passed"
        }
        return "${facts.result}: ${body} (${facts.commit})".toString()
    }

    @NonCPS
    private static String closing(Map facts, List<Map> records) {
        Map failed = firstFailed(records)
        if (failed != null) {
            return "Stopped at: ${failed.name}".toString()
        }
        if (records.any { Map r -> r.passed != true }) {
            return 'Some records could not be read; check the console log.'
        }
        if (facts.result != 'SUCCESS') {
            return 'No failed gate was recorded; check the console log.'
        }
        Map rollback = rollbackOnly(records)
        return rollback.isEmpty() ? 'All gates passed.'
                                : "No quality gates ran: this was a rollback (${rollback.detail}).".toString()
    }

    @NonCPS
    private static List<Map> qualityGates(List<Map> records) {
        return records.findAll { Map r -> !DEPLOY_STEPS.contains(r.name) }
    }

    /** The successful Rollback record when the build ran no quality gate (a rollback run), else an empty map. */
    @NonCPS
    private static Map rollbackOnly(List<Map> records) {
        Map rollback = records.find { Map r -> r.name == 'Rollback' && r.passed == true } ?: [:]
        return qualityGates(records).isEmpty() ? rollback : [:]
    }

    /** "staging: green -> blue" becomes "staging (green -> blue)"; any other detail is shown as is. */
    @NonCPS
    private static String rollbackDescription(String detail) {
        def matcher = detail =~ /^(.+): (.+) -> (.+)$/
        return matcher.matches()
                ? "${matcher.group(1)} (${matcher.group(2)} -> ${matcher.group(3)})".toString() : detail
    }

    @NonCPS
    private static Map firstFailed(List<Map> records) {
        return records.find { Map r -> r.passed == false }
    }

    @NonCPS
    private static String verdict(Map record) {
        return record.passed == true ? 'PASSED' : (record.passed == false ? 'FAILED' : 'UNKNOWN')
    }

    @NonCPS
    private static String detailOf(Map record) {
        return record.unreadable ? 'could not be parsed' : (record.detail ?: '')
    }

    /** Keeps a value on one table row: no raw pipes, no line breaks. */
    @NonCPS
    private static String cell(Object value) {
        return String.valueOf(value).replace('|', '\\|').replaceAll('\\s*\\R\\s*', ' ').trim()
    }
}
