package vars

import groovy.json.JsonSlurperClassic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import support.Fixtures
import support.LibraryTestBase
import support.PipelineError
import support.Thrown

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class CoverageGateTest extends LibraryTestBase {

    @BeforeEach
    void gateSetUp() {
        errorThrows()
        binding.getVariable('env').GATE_RECORDS = '/ws/gates'
    }

    private Map record() {
        return new JsonSlurperClassic().parseText(fileText('/ws/gates/coverage.json'))
    }

    @Test
    void 'coverage above the minimum passes and is recorded'() {
        putFile('reports/cobertura.xml', Fixtures.text('cobertura-85.xml'))

        runSnippet("coverageGate(report: 'reports/cobertura.xml', min: 80)")

        assertTrue(echoes().contains('Line coverage: 85.00% (minimum 80%)'), echoes().toString())
        assertEquals(true, record().passed)
        assertEquals('85.00% (minimum 80%)', record().detail)
        assertEquals('SUCCESS', binding.getVariable('currentBuild').result)
    }

    @Test
    void 'coverage exactly at the minimum passes'() {
        putFile('reports/cobertura.xml', Fixtures.text('cobertura-85.xml'))
        runSnippet("coverageGate(report: 'reports/cobertura.xml', min: 85)")
        assertEquals(true, record().passed)
    }

    @Test
    void 'coverage below the minimum fails with an actionable message after recording'() {
        putFile('reports/cobertura.xml', Fixtures.text('cobertura-60.xml'))

        PipelineError e = Thrown.by(PipelineError) { runSnippet("coverageGate(report: 'reports/cobertura.xml', min: 80)") }

        assertEquals('Coverage gate: line coverage 60.00% is below the 80% minimum. ' +
                'Add tests or lower the threshold in a reviewed change.', e.message)
        assertEquals(false, record().passed)
        assertEquals(1, record().exitCode)
    }

    @Test
    void 'a missing report fails and is recorded'() {
        PipelineError e = Thrown.by(PipelineError) { runSnippet("coverageGate(report: 'reports/cobertura.xml', min: 80)") }

        assertEquals('Coverage gate: report reports/cobertura.xml not found. Did the Unit tests stage run?', e.message)
        assertEquals(false, record().passed)
        assertEquals('report not found', record().detail)
    }

    @Test
    void 'a report that is not Cobertura fails the gate, with a record, instead of passing'() {
        putFile('reports/cobertura.xml', '<html/>')

        PipelineError e = Thrown.by(PipelineError) { runSnippet("coverageGate(report: 'reports/cobertura.xml', min: 80)") }

        assertTrue(e.message.startsWith('Coverage gate: report reports/cobertura.xml is not readable: Not a Cobertura report'), e.message)
        assertEquals(false, record().passed)
        assertEquals('report not readable', record().detail)
    }

    @Test
    void 'a report that is not XML fails the gate, with a record'() {
        putFile('reports/cobertura.xml', 'PHP Fatal error: out of memory')

        PipelineError e = Thrown.by(PipelineError) { runSnippet("coverageGate(report: 'reports/cobertura.xml', min: 80)") }

        assertTrue(e.message.contains('is not readable'), e.message)
        assertEquals(false, record().passed)
    }

    @Test
    void 'a minimum outside 0 to 100 is rejected'() {
        putFile('reports/cobertura.xml', Fixtures.text('cobertura-85.xml'))
        [150, -5].each { int bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) {
                runSnippet("coverageGate(report: 'reports/cobertura.xml', min: ${bad})")
            }
            assertTrue(e.message.contains('between 0 and 100'), e.message)
        }
    }

    @Test
    void 'required arguments are checked'() {
        assertEquals("Missing required argument 'min'",
                Thrown.by(IllegalArgumentException) { runSnippet("coverageGate(report: 'reports/cobertura.xml')") }.message)
        assertEquals("Missing required argument 'report'",
                Thrown.by(IllegalArgumentException) { runSnippet('coverageGate(min: 80)') }.message)
    }
}
