package unit

import io.github.tayguara.ci.BuildSummary
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class BuildSummaryTest {

    private static Map facts(String result) {
        return [job: 'php-app-pipeline', number: '12', result: result, commit: 'abc1234',
                triggeredBy: 'admin', duration: '1 min 3 sec']
    }

    private static Map gate(String name, boolean passed, String detail) {
        return [name: name, passed: passed, exitCode: passed ? 0 : 1, report: 'r', detail: detail]
    }

    @Test
    void 'render of a successful build is a snapshot'() {
        String md = BuildSummary.render(facts('SUCCESS'), [
                gate('Code style', true, '0 violations'),
                gate('Unit tests', true, '62 tests, 0 failed, 0 skipped')])

        assertEquals('''\
# php-app-pipeline #12: SUCCESS

- Commit: `abc1234`
- Started by: admin
- Duration: 1 min 3 sec

| Gate | Result | Detail |
| --- | --- | --- |
| Code style | PASSED | 0 violations |
| Unit tests | PASSED | 62 tests, 0 failed, 0 skipped |

All gates passed.
''', md)
    }

    @Test
    void 'render of a failed build names the first failed gate and keeps earlier results'() {
        String md = BuildSummary.render(facts('FAILURE'), [
                gate('Code style', true, '0 violations'),
                gate('Static analysis', false, '4 errors')])

        assertEquals('''\
# php-app-pipeline #12: FAILURE

- Commit: `abc1234`
- Started by: admin
- Duration: 1 min 3 sec

| Gate | Result | Detail |
| --- | --- | --- |
| Code style | PASSED | 0 violations |
| Static analysis | FAILED | 4 errors |

Stopped at: Static analysis
''', md)
    }

    @Test
    void 'render without records says so'() {
        String md = BuildSummary.render(facts('FAILURE'), [])
        assertTrue(md.contains('No gate records found'), md)
        assertFalse(md.contains('| Gate |'), md)
        assertFalse(md.contains('Stopped at'), md)
    }

    @Test
    void 'render shows an unreadable record as a row instead of failing'() {
        String md = BuildSummary.render(facts('SUCCESS'), [
                gate('Code style', true, '0 violations'),
                [name: 'unreadable record', passed: null, unreadable: true]])

        assertTrue(md.contains('| unreadable record | UNKNOWN | could not be parsed |'), md)
        assertFalse(md.contains('All gates passed.'), md)
    }

    @Test
    void 'render of a failed build without a failed record points to the console'() {
        String md = BuildSummary.render(facts('FAILURE'), [gate('Code style', true, '0 violations')])
        assertTrue(md.contains('No failed gate was recorded'), md)
    }

    @Test
    void 'render escapes table separators and newlines in details'() {
        String md = BuildSummary.render(facts('FAILURE'), [gate('Gate | one', false, 'a | b\nsecond line')])
        assertTrue(md.contains('| Gate \\| one | FAILED | a \\| b second line |'), md)
    }

    @Test
    void 'render handles a build not started by a user'() {
        Map f = facts('SUCCESS')
        f.triggeredBy = null
        assertTrue(BuildSummary.render(f, [gate('Code style', true, 'ok')]).contains('- Started by: automated trigger'))
    }

    private static Map deployStep(String name, boolean passed, String detail) {
        return [name: name, passed: passed, exitCode: passed ? 0 : 1, report: '', detail: detail]
    }

    @Test
    void 'a successful rollback is described as a rollback, not as passed gates'() {
        List<Map> records = [deployStep('Rollback', true, 'staging: green -> blue')]

        assertEquals('SUCCESS: rolled back staging (green -> blue) (abc1234)', BuildSummary.oneLine(facts('SUCCESS'), records))

        String md = BuildSummary.render(facts('SUCCESS'), records)
        assertTrue(md.contains('| Rollback | PASSED | staging: green -> blue |'), md)
        assertTrue(md.endsWith('\nNo quality gates ran: this was a rollback (staging: green -> blue).\n'), md)
        assertFalse(md.contains('All gates passed'), md)
    }

    @Test
    void 'a deploy is not counted as a quality gate'() {
        List<Map> records = [gate('Code style', true, 'ok'), gate('Unit tests', true, 'ok'),
                             deployStep('Deploy', true, 'staging: blue -> green')]

        assertEquals('SUCCESS: 2/2 gates passed (abc1234)', BuildSummary.oneLine(facts('SUCCESS'), records))
        String md = BuildSummary.render(facts('SUCCESS'), records)
        assertTrue(md.contains('| Deploy | PASSED | staging: blue -> green |'), md)
        assertTrue(md.endsWith('\nAll gates passed.\n'), md)
    }

    @Test
    void 'failure wording is unchanged for the deploy steps'() {
        assertEquals('FAILURE: stopped at Rollback (abc1234)',
                BuildSummary.oneLine(facts('FAILURE'), [deployStep('Rollback', false, 'Rollback: nothing is live')]))
        assertTrue(BuildSummary.render(facts('FAILURE'), [deployStep('Deploy', false, 'x')]).endsWith('Stopped at: Deploy\n'))
    }

    @Test
    void 'a rollback detail in an unexpected shape is shown as is'() {
        assertEquals('SUCCESS: rolled back staging: green to blue (abc1234)',
                BuildSummary.oneLine(facts('SUCCESS'), [deployStep('Rollback', true, 'staging: green to blue')]))
    }

    @Test
    void 'oneLine summarizes success'() {
        assertEquals('SUCCESS: 2/2 gates passed (abc1234)',
                BuildSummary.oneLine(facts('SUCCESS'), [gate('A', true, ''), gate('B', true, '')]))
    }

    @Test
    void 'oneLine names the stopping gate on failure'() {
        assertEquals('FAILURE: stopped at Static analysis (abc1234)',
                BuildSummary.oneLine(facts('FAILURE'), [gate('Code style', true, ''), gate('Static analysis', false, '')]))
    }

    @Test
    void 'oneLine of a failed build with no failed gate does not claim all gates passed'() {
        assertEquals('FAILURE: failed outside a recorded gate; see console (abc1234)',
                BuildSummary.oneLine(facts('FAILURE'), [gate('Code style', true, ''), gate('Install', true, '')]))
        assertEquals('ABORTED: failed outside a recorded gate; see console (abc1234)',
                BuildSummary.oneLine(facts('ABORTED'), [gate('Code style', true, '')]))
    }

    @Test
    void 'oneLine without records still reports the result'() {
        assertEquals('FAILURE: no gate records (abc1234)', BuildSummary.oneLine(facts('FAILURE'), []))
    }
}
