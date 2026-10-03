package vars

import groovy.json.JsonSlurperClassic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import support.Fixtures
import support.LibraryTestBase
import support.PipelineError
import support.Thrown

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class RunGateTest extends LibraryTestBase {

    @BeforeEach
    void gateSetUp() {
        errorThrows()
        binding.getVariable('env').GATE_RECORDS = '/ws/app/reports/gates'
    }

    private Map record(String name) {
        return new JsonSlurperClassic().parseText(fileText("/ws/app/reports/gates/${name}.json"))
    }

    private void runGate(String args) {
        runSnippet("runGate(${args})")
    }

    @Test
    void 'a passing gate runs the command into the report and records success'() {
        mockSh('phpstan', 0)
        putFile('/ws/reports/phpstan.json', Fixtures.text('phpstan-clean.json'))

        runGate("name: 'Static analysis', type: 'phpstan', command: 'vendor/bin/phpstan analyse', report: 'reports/phpstan.json'")

        String script = shScripts()[0]
        assertEquals("mkdir -p 'reports' && vendor/bin/phpstan analyse > 'reports/phpstan.json'", script)
        assertEquals('Static analysis', shCalls()[0].label)
        assertEquals(true, shCalls()[0].returnStatus)

        Map saved = record('static-analysis')
        assertEquals(true, saved.passed)
        assertEquals(0, saved.exitCode)
        assertEquals('reports/phpstan.json', saved.report)
        assertEquals('0 errors', saved.detail)
        assertEquals('SUCCESS', binding.getVariable('currentBuild').result)
    }

    @Test
    void 'a failing gate records the failure before it stops the build'() {
        mockSh('phpstan', 2)
        putFile('/ws/reports/phpstan.json', Fixtures.text('phpstan-errors.json'))

        PipelineError e = Thrown.by(PipelineError) {
            runGate("name: 'Static analysis', type: 'phpstan', command: 'vendor/bin/phpstan analyse', report: 'reports/phpstan.json'")
        }

        assertEquals('Static analysis gate failed (exit code 2). Findings: reports/phpstan.json', e.message)
        Map saved = record('static-analysis')
        assertEquals(false, saved.passed)
        assertEquals(2, saved.exitCode)
        assertEquals('4 errors', saved.detail)
    }

    @Test
    void 'a failing gate whose report was never written still fails with the gate message'() {
        mockSh('phpstan', 2)

        PipelineError e = Thrown.by(PipelineError) {
            runGate("name: 'Static analysis', type: 'phpstan', command: 'vendor/bin/phpstan analyse', report: 'reports/phpstan.json'")
        }

        assertEquals('Static analysis gate failed (exit code 2). Findings: reports/phpstan.json', e.message)
        assertEquals(false, record('static-analysis').passed)
        assertEquals('report not found', record('static-analysis').detail)
        assertTrue(callsTo('readFile').isEmpty())
    }

    @Test
    void 'a gate without a type has an empty detail and does not read the report'() {
        mockSh('phpunit', 0)

        runGate("name: 'Unit tests', command: 'vendor/bin/phpunit', report: 'reports/phpunit.txt'")

        assertEquals('', record('unit-tests').detail)
        assertTrue(callsTo('readFile').isEmpty())
    }

    @Test
    void 'records go to reports gates when GATE_RECORDS is not set'() {
        binding.getVariable('env').remove('GATE_RECORDS')
        mockSh('phpunit', 0)

        runGate("name: 'Unit tests', command: 'vendor/bin/phpunit', report: 'reports/phpunit.txt'")

        assertTrue(files.containsKey('/ws/reports/gates/unit-tests.json'))
    }

    @Test
    void 'an unsafe report path is rejected before anything runs'() {
        ["a'b", '../x', '/etc/passwd'].each { String bad ->
            Thrown.by(IllegalArgumentException) {
                runGate("name: 'Gate', command: 'true', report: \"${bad}\"")
            }
        }
        assertTrue(callsTo('sh').isEmpty())
    }

    @Test
    void 'missing arguments are rejected and named'() {
        ['command', 'report', 'name'].each { String missing ->
            Map all = [name: "'Gate'", command: "'true'", report: "'reports/x.txt'"]
            all.remove(missing)
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) {
                runGate(all.collect { k, v -> "${k}: ${v}" }.join(', '))
            }
            assertEquals("Missing required argument '${missing}'".toString(), e.message)
        }
    }

    @Test
    void 'a type that is not supported is rejected'() {
        mockSh('true', 0)
        putFile('/ws/reports/x.txt', 'x')
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) {
            runGate("name: 'Gate', type: 'sarif', command: 'true', report: 'reports/x.txt'")
        }
        assertTrue(e.message.contains("Unknown report type 'sarif'"), e.message)
        assertFalse(files.containsKey('/ws/app/reports/gates/gate.json'))
    }
}
