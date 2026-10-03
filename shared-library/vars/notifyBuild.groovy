import io.github.tayguara.ci.Args
import io.github.tayguara.ci.BuildSummary
import io.github.tayguara.ci.GateRecords

/**
 * Writes the build summary from the gate records: a markdown file in `reportsDir`, an echo and
 * the build description. Call it from `post { always { ... } }`.
 *
 * It only reports. It never changes the build result, and when it cannot write the summary it
 * logs a warning instead of failing a build that may have been fine.
 *
 *   notifyBuild(reportsDir: 'app/reports')
 */
def call(Map args) {
    String reportsDir = Args.safePath(Args.required(args, 'reportsDir') as String)
    try {
        List<String> jsons = []
        for (def file : findFiles(glob: "${reportsDir}/gates/*.json")) {
            jsons << readFile(file.path)
        }
        List<Map> records = GateRecords.parseAndSort(jsons)
        Map facts = [job        : env.JOB_NAME,
                     number     : env.BUILD_NUMBER,
                     result     : currentBuild.currentResult,
                     commit     : (env.GIT_COMMIT ?: 'unknown').take(7),
                     triggeredBy: startedBy(),
                     duration   : currentBuild.durationString.replace(' and counting', '')]
        String markdown = BuildSummary.render(facts, records)
        writeFile(file: "${reportsDir}/build-summary.md", text: markdown)
        echo markdown
        currentBuild.description = BuildSummary.oneLine(facts, records)
    } catch (InterruptedException aborted) {
        throw aborted    // an abort (FlowInterruptedException) must stop the build, not be logged
    } catch (Exception e) { // groovylint-disable-line CatchException
        echo "notifyBuild: could not write the build summary: ${e.message}"
    }
}

/** Name of the user who started the build, or null for timers, SCM triggers and API calls without a user. */
String startedBy() {
    List causes = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    return causes.isEmpty() ? null : (causes[0].userName ?: causes[0].userId)
}
