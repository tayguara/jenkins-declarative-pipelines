package io.github.tayguara.ci

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonException
import groovy.json.JsonSlurperClassic
import groovy.util.slurpersupport.GPathResult
import org.xml.sax.SAXException

import java.math.RoundingMode

/**
 * Parsers for the machine-readable reports the gates produce. Pure functions, no Jenkins steps.
 *
 * Library code runs on the controller, so XML is parsed with external entities and external
 * DTDs switched off: a report coming from a build must not be able to read controller files
 * or make the controller call out. The DOCTYPE itself is allowed because some Cobertura
 * writers emit one.
 *
 * The strict parsers throw IllegalArgumentException on anything unexpected, so a gate can never
 * pass on a report it did not understand. Only summarize() is lenient, because it feeds a label.
 */
class Reports {

    private static final List<String> TYPES = ['checkstyle', 'phpstan', 'composer-audit', 'junit']
    private static final String UNREADABLE = 'report could not be read'

    /** Hardened XML parsing. Public so the XXE test can inspect what a document resolves to. */
    @NonCPS
    static GPathResult parseXml(String xml) {
        XmlSlurper slurper = new XmlSlurper(false, false, true)
        slurper.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
        slurper.setFeature('http://xml.org/sax/features/external-general-entities', false)
        slurper.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
        slurper.setFeature('http://javax.xml.XMLConstants/feature/secure-processing', true)
        try {
            return slurper.parseText(xml)
        } catch (SAXException e) {
            throw new IllegalArgumentException("Report is not valid XML: ${e.message}".toString(), e)
        }
    }

    /** Cobertura line-rate as a percentage with 2 decimals, rounded down so it never overstates coverage. */
    @NonCPS
    static BigDecimal coberturaLinePercent(String xml) {
        GPathResult doc = parseXml(xml)
        BigDecimal rate = doc.name() == 'coverage' ? Args.numberOrNull(doc.attributes().get('line-rate')) : null
        if (rate == null || rate < BigDecimal.ZERO || rate > BigDecimal.ONE) {
            throw new IllegalArgumentException('Not a Cobertura report: expected <coverage line-rate="0..1">')
        }
        return (rate * new BigDecimal('100')).setScale(2, RoundingMode.DOWN)
    }

    /** Methods whose CRAP score is above max, as [method: 'Class::name', crap: BigDecimal], worst first. */
    @NonCPS
    static List<Map> crapOffenders(String xml, Number max) {
        BigDecimal limit = new BigDecimal(max.toString())
        return crapMethods(xml).findAll { Map m -> m.crap > limit }
    }

    /** The worst method in the report, or an empty map when it lists none. */
    @NonCPS
    static Map crapTop(String xml) {
        List<Map> all = crapMethods(xml)
        return all.isEmpty() ? [:] : all.first()
    }

    @NonCPS
    static Map phpstanTotals(String json) {
        Object totals = jsonMap(json, 'PHPStan').totals
        if (!(totals in Map)) {
            throw new IllegalArgumentException('Not a PHPStan report: expected a "totals" object')
        }
        return [errors: count(totals.errors), fileErrors: count(totals.file_errors)]
    }

    @NonCPS
    static int checkstyleErrorCount(String xml) {
        return parseXml(xml).depthFirst().findAll { GPathResult n -> n.name() == 'error' }.size()
    }

    /** Sums the top-level suites. PHPUnit nests suites, so adding every level would count tests twice. */
    @NonCPS
    static Map junitTotals(String xml) {
        GPathResult doc = parseXml(xml)
        List<GPathResult> suites = []
        if (doc.name() == 'testsuites') {
            for (GPathResult suite : doc.testsuite) {
                suites << suite
            }
        } else if (doc.name() == 'testsuite') {
            suites << doc
        } else {
            throw new IllegalArgumentException('Not a JUnit report: expected <testsuites> or <testsuite>')
        }
        Map totals = [tests: 0, failures: 0, errors: 0, skipped: 0]
        for (GPathResult suite : suites) {
            for (String key : totals.keySet()) {
                totals[key] += count(suite.attributes().get(key))
            }
        }
        return totals
    }

    @NonCPS
    static int composerAdvisoryCount(String json) {
        Map doc = jsonMap(json, 'Composer audit')
        if (!doc.containsKey('advisories')) {
            throw new IllegalArgumentException('Not a Composer audit report: expected an "advisories" key')
        }
        Object advisories = doc.advisories
        if (advisories in Map) {
            return advisories.values().sum { Object list -> list.size() } ?: 0
        }
        return advisories.size()
    }

    /** One-line detail for a gate record. Lenient: an unreadable report is described, not thrown. */
    @NonCPS
    static String summarize(String type, String text) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown report type '${type}'".toString())
        }
        try {
            return describe(type, text)
        } catch (IllegalArgumentException ignored) {
            return UNREADABLE    // the gate's own exit code already decided pass or fail
        }
    }

    @NonCPS
    private static String describe(String type, String text) {
        switch (type) {
            case 'checkstyle':
                return plural(checkstyleErrorCount(text), 'violation', 'violations')
            case 'phpstan':
                Map totals = phpstanTotals(text)
                return plural(totals.errors + totals.fileErrors, 'error', 'errors')
            case 'composer-audit':
                return plural(composerAdvisoryCount(text), 'advisory', 'advisories')
            default:
                Map junit = junitTotals(text)
                int failed = junit.failures + junit.errors
                return "${junit.tests} tests, ${failed} failed, ${junit.skipped} skipped".toString()
        }
    }

    @NonCPS
    private static List<Map> crapMethods(String xml) {
        GPathResult doc = parseXml(xml)
        if (doc.name() != 'crap_result') {
            throw new IllegalArgumentException('Not a crap4j report: expected a <crap_result> root')
        }
        List<Map> methods = []
        for (GPathResult node : doc.methods.method) {
            BigDecimal crap = Args.numberOrNull(node.crap.text())
            if (crap == null) {
                throw new IllegalArgumentException('Not a crap4j report: a method has a non numeric <crap> value')
            }
            methods << [method: node.className.text() + '::' + node.methodName.text(), crap: crap]
        }
        return methods.sort(false) { Map a, Map b -> b.crap <=> a.crap }
    }

    /** A count from a report: missing means 0, anything that is not a whole number is an error. */
    @NonCPS
    private static int count(Object value) {
        BigDecimal number = value == null ? BigDecimal.ZERO : Args.numberOrNull(value)
        if (number == null || number.remainder(BigDecimal.ONE).signum() != 0) {
            throw new IllegalArgumentException("Expected a whole number in the report but got '${value}'".toString())
        }
        return number.intValue()
    }

    @NonCPS
    private static String plural(int count, String one, String many) {
        return "${count} ${count == 1 ? one : many}".toString()
    }

    @NonCPS
    private static Map jsonMap(String json, String what) {
        Object parsed = null
        try {
            parsed = new JsonSlurperClassic().parseText(json)
        } catch (JsonException ignored) {
            throw new IllegalArgumentException("${what} report is not valid JSON".toString())
        }
        if (!(parsed in Map)) {
            throw new IllegalArgumentException("${what} report is not a JSON object".toString())
        }
        return (Map) parsed
    }
}
