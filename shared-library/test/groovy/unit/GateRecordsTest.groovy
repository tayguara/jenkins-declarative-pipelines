package unit

import groovy.json.JsonSlurperClassic
import io.github.tayguara.ci.GateRecords
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class GateRecordsTest {

    private static Map rec(String name, long at, boolean passed = true) {
        return [name: name, passed: passed, exitCode: passed ? 0 : 1, report: 'r.json', detail: 'd', at: at]
    }

    @Test
    void 'toJson keeps the record fields and adds a millisecond timestamp'() {
        long before = System.currentTimeMillis()
        Map parsed = new JsonSlurperClassic().parseText(
                GateRecords.toJson([name: 'Code style', passed: true, exitCode: 0, report: 'reports/x.xml', detail: '0 violations']))
        long after = System.currentTimeMillis()

        assertEquals('Code style', parsed.name)
        assertEquals(true, parsed.passed)
        assertEquals(0, parsed.exitCode)
        assertEquals('reports/x.xml', parsed.report)
        assertEquals('0 violations', parsed.detail)
        assertTrue(parsed.at >= before && parsed.at <= after)
    }

    @Test
    void 'toJson escapes quotes and newlines in details'() {
        String json = GateRecords.toJson([name: 'Gate', passed: false, detail: 'He said "no"\nline two'])
        Map parsed = new JsonSlurperClassic().parseText(json)
        assertEquals('He said "no"\nline two', parsed.detail)
        assertFalse(json.contains('\n'))
    }

    @Test
    void 'toJson does not overwrite an explicit timestamp'() {
        Map parsed = new JsonSlurperClassic().parseText(GateRecords.toJson([name: 'Gate', passed: true, at: 42]))
        assertEquals(42, parsed.at)
    }

    @Test
    void 'parseAndSort orders records by timestamp'() {
        List<String> jsons = [rec('Coverage', 30), rec('Code style', 10), rec('Unit tests', 20)].collect { Map m ->
            GateRecords.toJson(m)
        }
        assertEquals(['Code style', 'Unit tests', 'Coverage'], GateRecords.parseAndSort(jsons).collect { Map m -> m.name })
    }

    @Test
    void 'parseAndSort keeps input order for equal timestamps'() {
        List<String> jsons = [rec('B', 5), rec('A', 5)].collect { Map m -> GateRecords.toJson(m) }
        assertEquals(['B', 'A'], GateRecords.parseAndSort(jsons).collect { Map m -> m.name })
    }

    @Test
    void 'parseAndSort marks unreadable records and puts them last'() {
        List<String> jsons = ['{not json', GateRecords.toJson(rec('Code style', 10)), '', '[1,2]', null, '{"a":', 'null']
        List<Map> sorted = GateRecords.parseAndSort(jsons)

        assertEquals(7, sorted.size())
        assertEquals('Code style', sorted[0].name)
        sorted.tail().each { Map m ->
            assertEquals(true, m.unreadable)
            assertEquals('unreadable record', m.name)
            assertNull(m.passed)
        }
    }

    @Test
    void 'parseAndSort handles an empty list'() {
        assertEquals([], GateRecords.parseAndSort([]))
    }

    @Test
    void 'recordPath turns the gate name into a file name'() {
        assertEquals('/ws/gates/code-style.json', GateRecords.recordPath('/ws/gates', 'Code style'))
        assertEquals('/ws/gates/complexity-crap.json', GateRecords.recordPath('/ws/gates', 'Complexity (CRAP)'))
        assertEquals('/ws/gates/a-b.json', GateRecords.recordPath('/ws/gates', '  ../A/b  '))
    }

    @Test
    void 'recordPath defaults to reports gates'() {
        assertEquals('reports/gates/deploy.json', GateRecords.recordPath(null, 'Deploy'))
        assertEquals('reports/gates/deploy.json', GateRecords.recordPath('', 'Deploy'))
    }
}
