package vars

import io.github.tayguara.ci.GateRecords
import org.junit.jupiter.api.Test
import support.LibraryTestBase
import support.Thrown

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class NotifyBuildTest extends LibraryTestBase {

    private void gate(String file, String name, boolean passed, String detail, long at) {
        putFile("app/reports/gates/${file}.json".toString(),
                GateRecords.toJson([name: name, passed: passed, exitCode: passed ? 0 : 1, report: 'r', detail: detail, at: at]))
    }

    private String summary() {
        return fileText('/ws/app/reports/build-summary.md')
    }

    private Map build() {
        return binding.getVariable('currentBuild')
    }

    private void notifyBuild() {
        runSnippet("notifyBuild(reportsDir: 'app/reports')")
    }

    @Test
    void 'a successful build gets a summary, an echo and a one-line description'() {
        gate('code-style', 'Code style', true, '0 violations', 1)
        gate('unit-tests', 'Unit tests', true, '62 tests, 0 failed, 0 skipped', 2)

        notifyBuild()

        assertTrue(summary().startsWith('# php-app-pipeline #12: SUCCESS\n'), summary())
        assertTrue(summary().contains('- Commit: `abc1234`'), summary())
        assertTrue(summary().contains('- Started by: Admin'), summary())
        assertTrue(summary().contains('- Duration: 1 min 3 sec\n'), summary())
        assertTrue(summary().contains('| Unit tests | PASSED | 62 tests, 0 failed, 0 skipped |'), summary())
        assertTrue(summary().contains('All gates passed.'), summary())
        assertTrue(echoes().contains(summary()), echoes().toString())
        assertEquals('SUCCESS: 2/2 gates passed (abc1234)', build().description)
    }

    @Test
    void 'a failed build shows earlier gates as passed and where it stopped'() {
        updateBuildStatus('FAILURE')
        gate('static-analysis', 'Static analysis', false, '4 errors', 2)
        gate('code-style', 'Code style', true, '0 violations', 1)

        notifyBuild()

        assertTrue(summary().contains('# php-app-pipeline #12: FAILURE'), summary())
        assertTrue(summary().contains('| Code style | PASSED | 0 violations |'), summary())
        assertTrue(summary().contains('| Static analysis | FAILED | 4 errors |'), summary())
        assertTrue(summary().contains('Stopped at: Static analysis'), summary())
        assertTrue(summary().indexOf('Code style') < summary().indexOf('Static analysis'), 'records are sorted by time')
        assertEquals('FAILURE: stopped at Static analysis (abc1234)', build().description)
    }

    @Test
    void 'a build with no records still gets a summary that says so'() {
        updateBuildStatus('FAILURE')

        notifyBuild()

        assertTrue(summary().contains('No gate records found'), summary())
        assertEquals('FAILURE: no gate records (abc1234)', build().description)
    }

    @Test
    void 'a corrupted record becomes a row and does not stop the summary'() {
        gate('code-style', 'Code style', true, '0 violations', 1)
        putFile('app/reports/gates/broken.json', '{not json')

        notifyBuild()

        assertTrue(summary().contains('| unreadable record | UNKNOWN | could not be parsed |'), summary())
        assertTrue(summary().contains('| Code style | PASSED |'), summary())
        assertEquals('SUCCESS', build().result)
    }

    @Test
    void 'the build result is never changed, and a failure of the summary only logs a warning'() {
        updateBuildStatus('FAILURE')
        helper.registerAllowedMethod('findFiles', [Map], { Map args -> throw new IOException('disk gone') })

        notifyBuild()

        assertEquals('FAILURE', build().result)
        assertTrue(echoes().any { String e -> e.startsWith('notifyBuild: could not write the build summary') && e.contains('disk gone') },
                echoes().toString())
    }

    @Test
    void 'an abort is not swallowed'() {
        helper.registerAllowedMethod('findFiles', [Map], { Map args -> throw new InterruptedException('aborted') })
        Thrown.by(InterruptedException) { notifyBuild() }
    }

    @Test
    void 'a user who did not start the build is reported as an automated trigger'() {
        build().getBuildCauses = { String type -> [] }
        gate('code-style', 'Code style', true, 'ok', 1)

        notifyBuild()

        assertTrue(summary().contains('- Started by: automated trigger'), summary())
    }

    @Test
    void 'the commit is shortened to seven characters'() {
        binding.getVariable('env').GIT_COMMIT = '0123456789abcdef'
        notifyBuild()
        assertTrue(summary().contains('`0123456`'), summary())
    }

    @Test
    void 'an unsafe reports dir is rejected'() {
        Thrown.by(IllegalArgumentException) { runSnippet("notifyBuild(reportsDir: '../x')") }
        assertFalse(files.keySet().any { String f -> f.endsWith('build-summary.md') })
    }
}
