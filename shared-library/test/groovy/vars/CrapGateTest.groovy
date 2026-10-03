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

class CrapGateTest extends LibraryTestBase {

    @BeforeEach
    void gateSetUp() {
        errorThrows()
        binding.getVariable('env').GATE_RECORDS = '/ws/gates'
    }

    private Map record() {
        return new JsonSlurperClassic().parseText(fileText('/ws/gates/complexity-crap.json'))
    }

    @Test
    void 'no offenders passes, echoes the worst score and records it'() {
        putFile('reports/crap4j.xml', Fixtures.text('crap4j-ok.xml'))

        runSnippet("crapGate(report: 'reports/crap4j.xml', max: 30)")

        assertTrue(echoes().contains('Highest CRAP: 30 (App\\SemverBump::bump), maximum 30'), echoes().toString())
        assertEquals(true, record().passed)
        assertEquals('highest 30 (maximum 30)', record().detail)
    }

    @Test
    void 'max defaults to 30'() {
        putFile('reports/crap4j.xml', Fixtures.text('crap4j-ok.xml'))
        runSnippet("crapGate(report: 'reports/crap4j.xml')")
        assertEquals(true, record().passed)
    }

    @Test
    void 'offenders fail the gate, worst first, listing at most five'() {
        putFile('reports/crap4j.xml', Fixtures.text('crap4j-high.xml'))

        PipelineError e = Thrown.by(PipelineError) { runSnippet("crapGate(report: 'reports/crap4j.xml', max: 30)") }

        assertEquals('Complexity gate: 7 method(s) above CRAP 30: App\\Billing\\Invoice::totalD (156), ' +
                'App\\Billing\\Invoice::totalB (90.25), App\\Billing\\Invoice::totalF (72), ' +
                'App\\Billing\\Invoice::totalC (42.5), App\\Billing\\Invoice::totalE (33.75), ... ' +
                'Cover them with tests or simplify them.', e.message)
        assertEquals(false, record().passed)
        assertFalse(e.message.contains('totalG'))
    }

    @Test
    void 'the failure message has no ellipsis when five or fewer offenders'() {
        putFile('reports/crap4j.xml', Fixtures.text('crap4j-high.xml'))
        PipelineError e = Thrown.by(PipelineError) { runSnippet("crapGate(report: 'reports/crap4j.xml', max: 60)") }
        assertEquals('Complexity gate: 3 method(s) above CRAP 60: App\\Billing\\Invoice::totalD (156), ' +
                'App\\Billing\\Invoice::totalB (90.25), App\\Billing\\Invoice::totalF (72). ' +
                'Cover them with tests or simplify them.', e.message)
    }

    @Test
    void 'a missing report fails and is recorded'() {
        PipelineError e = Thrown.by(PipelineError) { runSnippet("crapGate(report: 'reports/crap4j.xml')") }
        assertEquals('Complexity gate: report reports/crap4j.xml not found. Did the Unit tests stage run?', e.message)
        assertEquals(false, record().passed)
    }

    @Test
    void 'a report without methods passes'() {
        putFile('reports/crap4j.xml', '<crap_result><methods/></crap_result>')
        runSnippet("crapGate(report: 'reports/crap4j.xml')")
        assertEquals('no methods in report', record().detail)
    }

    @Test
    void 'a report that is not crap4j fails the gate, with a record'() {
        putFile('reports/crap4j.xml', '<coverage/>')

        PipelineError e = Thrown.by(PipelineError) { runSnippet("crapGate(report: 'reports/crap4j.xml')") }

        assertTrue(e.message.startsWith('Complexity gate: report reports/crap4j.xml is not readable: Not a crap4j report'), e.message)
        assertEquals(false, record().passed)
        assertEquals('report not readable', record().detail)
    }

    @Test
    void 'max must be a positive number'() {
        putFile('reports/crap4j.xml', Fixtures.text('crap4j-ok.xml'))
        Thrown.by(IllegalArgumentException) { runSnippet("crapGate(report: 'reports/crap4j.xml', max: 0)") }
        Thrown.by(IllegalArgumentException) { runSnippet("crapGate(report: 'reports/crap4j.xml', max: 'abc')") }
    }
}
