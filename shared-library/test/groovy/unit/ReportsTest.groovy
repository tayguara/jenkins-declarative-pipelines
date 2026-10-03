package unit

import io.github.tayguara.ci.Reports
import org.junit.jupiter.api.Test
import support.Fixtures
import support.Thrown

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ReportsTest {

    // Cobertura

    @Test
    void 'coberturaLinePercent turns line-rate into a percentage'() {
        assertEquals(new BigDecimal('85.00'), Reports.coberturaLinePercent(Fixtures.text('cobertura-85.xml')))
        assertEquals(new BigDecimal('60.00'), Reports.coberturaLinePercent(Fixtures.text('cobertura-60.xml')))
    }

    @Test
    void 'coberturaLinePercent reads a real PHPUnit report without DOCTYPE'() {
        assertEquals(new BigDecimal('100.00'), Reports.coberturaLinePercent(Fixtures.text('cobertura-real.xml')))
    }

    @Test
    void 'coberturaLinePercent never rounds up'() {
        String xml = '<coverage line-rate="0.79996"/>'
        assertEquals(new BigDecimal('79.99'), Reports.coberturaLinePercent(xml))
    }

    @Test
    void 'coberturaLinePercent rejects reports that are not Cobertura'() {
        ['<coverage/>', '<other line-rate="0.5"/>', '<coverage line-rate="abc"/>', '<coverage line-rate="1.5"/>'].each { String bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Reports.coberturaLinePercent(bad) }
            assertTrue(e.message.contains('Cobertura'), e.message)
        }
    }

    @Test
    void 'coberturaLinePercent rejects broken XML'() {
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Reports.coberturaLinePercent('<coverage line-rate="0.8"') }
        assertTrue(e.message.contains('not valid XML'), e.message)
    }

    // crap4j

    @Test
    void 'crapOffenders is empty when every method is within the limit'() {
        assertEquals([], Reports.crapOffenders(Fixtures.text('crap4j-ok.xml'), 30))
    }

    @Test
    void 'crapOffenders lists methods above the limit, worst first'() {
        List<Map> offenders = Reports.crapOffenders(Fixtures.text('crap4j-high.xml'), 30)

        assertEquals(7, offenders.size())
        assertEquals('App\\Billing\\Invoice::totalD', offenders[0].method)
        assertEquals(new BigDecimal('156'), offenders[0].crap)
        assertEquals(['totalD', 'totalB', 'totalF', 'totalC', 'totalE', 'totalA', 'totalG'],
                offenders.collect { Map m -> m.method.split('::')[1] })
    }

    @Test
    void 'crapOffenders honors the limit passed in'() {
        assertEquals(1, Reports.crapOffenders(Fixtures.text('crap4j-high.xml'), 100).size())
        assertEquals(1, Reports.crapOffenders(Fixtures.text('crap4j-ok.xml'), 29).size())
    }

    @Test
    void 'crapOffenders reads a real PHPUnit report'() {
        assertEquals([], Reports.crapOffenders(Fixtures.text('crap4j-real.xml'), 30))
    }

    @Test
    void 'crapTop returns the worst method, or an empty map when there are none'() {
        Map top = Reports.crapTop(Fixtures.text('crap4j-high.xml'))
        assertEquals('App\\Billing\\Invoice::totalD', top.method)
        assertEquals(new BigDecimal('156'), top.crap)
        assertEquals([:], Reports.crapTop('<crap_result><methods/></crap_result>'))
    }

    @Test
    void 'crapOffenders rejects reports that are not crap4j'() {
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Reports.crapOffenders('<coverage/>', 30) }
        assertTrue(e.message.contains('crap4j'), e.message)
    }

    // PHPStan

    @Test
    void 'phpstanTotals reads clean and failing reports'() {
        assertEquals([errors: 0, fileErrors: 0], Reports.phpstanTotals(Fixtures.text('phpstan-clean.json')))
        assertEquals([errors: 1, fileErrors: 3], Reports.phpstanTotals(Fixtures.text('phpstan-errors.json')))
    }

    @Test
    void 'phpstanTotals rejects totals that are not numbers'() {
        Thrown.by(IllegalArgumentException) { Reports.phpstanTotals('{"totals":{"errors":"many","file_errors":1}}') }
    }

    @Test
    void 'truncated or odd JSON is rejected with a clear message'() {
        ['{"totals":', '[1,', 'null', '"text"', '{"totals":{"errors":0,"file_errors":0}'].each { String bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Reports.phpstanTotals(bad) }
            assertTrue(e.message.startsWith('PHPStan report is not'), "${bad}: ${e.message}")
        }
    }

    @Test
    void 'phpstanTotals rejects JSON without totals'() {
        Thrown.by(IllegalArgumentException) { Reports.phpstanTotals('{"files":{}}') }
        Thrown.by(IllegalArgumentException) { Reports.phpstanTotals('not json') }
    }

    // checkstyle

    @Test
    void 'checkstyleErrorCount counts error elements'() {
        assertEquals(0, Reports.checkstyleErrorCount(Fixtures.text('checkstyle-clean.xml')))
        assertEquals(3, Reports.checkstyleErrorCount(Fixtures.text('checkstyle-findings.xml')))
    }

    // JUnit

    @Test
    void 'junitTotals reads a single top-level suite'() {
        assertEquals([tests: 5, failures: 0, errors: 0, skipped: 1], Reports.junitTotals(Fixtures.text('junit-pass.xml')))
    }

    @Test
    void 'junitTotals sums top-level suites and does not count nested suites twice'() {
        assertEquals([tests: 7, failures: 1, errors: 0, skipped: 1], Reports.junitTotals(Fixtures.text('junit-multi-suite.xml')))
        assertEquals([tests: 6, failures: 2, errors: 1, skipped: 0], Reports.junitTotals(Fixtures.text('junit-fail.xml')))
    }

    @Test
    void 'junitTotals reads a real PHPUnit report with nested suites'() {
        Map totals = Reports.junitTotals(Fixtures.text('junit-real.xml'))
        assertEquals(62, totals.tests)
        assertEquals(0, totals.failures)
    }

    @Test
    void 'junitTotals accepts a bare testsuite root'() {
        assertEquals([tests: 2, failures: 1, errors: 0, skipped: 0],
                Reports.junitTotals('<testsuite tests="2" failures="1" errors="0" skipped="0"/>'))
    }

    // Composer audit

    @Test
    void 'composerAdvisoryCount handles an empty list and a map of packages'() {
        assertEquals(0, Reports.composerAdvisoryCount(Fixtures.text('composer-audit-clean.json')))
        assertEquals(3, Reports.composerAdvisoryCount(Fixtures.text('composer-audit-advisory.json')))
    }

    @Test
    void 'composerAdvisoryCount rejects JSON without advisories'() {
        Thrown.by(IllegalArgumentException) { Reports.composerAdvisoryCount('{"abandoned":{}}') }
    }

    // summarize

    @Test
    void 'summarize gives a one-line detail per report type'() {
        assertEquals('0 violations', Reports.summarize('checkstyle', Fixtures.text('checkstyle-clean.xml')))
        assertEquals('3 violations', Reports.summarize('checkstyle', Fixtures.text('checkstyle-findings.xml')))
        assertEquals('4 errors', Reports.summarize('phpstan', Fixtures.text('phpstan-errors.json')))
        assertEquals('0 errors', Reports.summarize('phpstan', Fixtures.text('phpstan-clean.json')))
        assertEquals('3 advisories', Reports.summarize('composer-audit', Fixtures.text('composer-audit-advisory.json')))
        assertEquals('5 tests, 0 failed, 1 skipped', Reports.summarize('junit', Fixtures.text('junit-pass.xml')))
        assertEquals('6 tests, 3 failed, 0 skipped', Reports.summarize('junit', Fixtures.text('junit-fail.xml')))
    }

    @Test
    void 'summarize uses the singular for exactly one finding'() {
        assertEquals('1 violation', Reports.summarize('checkstyle', '<checkstyle><file name="a"><error line="1"/></file></checkstyle>'))
        assertEquals('1 error', Reports.summarize('phpstan', '{"totals":{"errors":0,"file_errors":1}}'))
        assertEquals('1 advisory', Reports.summarize('composer-audit', '{"advisories":{"a/b":[{"title":"x"}]}}'))
    }

    @Test
    void 'summarize reports an unreadable file instead of throwing'() {
        assertEquals('report could not be read', Reports.summarize('phpstan', 'Fatal error: out of memory'))
        assertEquals('report could not be read', Reports.summarize('checkstyle', ''))
    }

    @Test
    void 'summarize rejects an unknown type'() {
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Reports.summarize('sarif', '{}') }
        assertTrue(e.message.contains("Unknown report type 'sarif'"), e.message)
    }

    // XXE (CA-18)

    @Test
    void 'external entities are not resolved and nothing is read from disk'() {
        Path secret = Files.createTempFile('xxe-secret', '.txt')
        try {
            secret.toFile().text = 'CANARY-do-not-leak'
            String xml = Fixtures.text('xxe-attempt.xml')
                    .replace('@SECRET_URL@', secret.toUri().toString())
                    .replace('@DTD_URL@', 'http://127.0.0.1:9/never.dtd')

            def doc = Reports.parseXml(xml)

            assertFalse(doc.sources.source.text().contains('CANARY-do-not-leak'))
            assertEquals(new BigDecimal('90.00'), Reports.coberturaLinePercent(xml))
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    void 'external DTDs are not fetched over the network'() {
        ServerSocket listener = new ServerSocket(0, 5, InetAddress.getByName('127.0.0.1'))
        try {
            listener.setSoTimeout(700)
            String xml = Fixtures.text('xxe-attempt.xml')
                    .replace('@SECRET_URL@', 'file:///nonexistent-canary')
                    .replace('@DTD_URL@', "http://127.0.0.1:${listener.localPort}/evil.dtd")

            Reports.coberturaLinePercent(xml)
            Reports.coberturaLinePercent(Fixtures.text('cobertura-85.xml'))

            // A fetch would have queued a connection in the backlog: accept() would return it.
            Thrown.by(SocketTimeoutException) { listener.accept() }
        } finally {
            listener.close()
        }
    }

    @Test
    void 'an entity expansion bomb is rejected'() {
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) {
            Reports.coberturaLinePercent(Fixtures.text('xxe-entity-bomb.xml'))
        }
        assertTrue(e.message.startsWith('Report is not valid XML'), e.message)
    }
}
