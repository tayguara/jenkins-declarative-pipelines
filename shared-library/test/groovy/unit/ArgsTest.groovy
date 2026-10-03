package unit

import io.github.tayguara.ci.Args
import org.junit.jupiter.api.Test
import support.Thrown

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class ArgsTest {

    // required

    @Test
    void 'required returns the value'() {
        assertEquals('x', Args.required([name: 'x'], 'name'))
    }

    @Test
    void 'required rejects a missing key and names it'() {
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Args.required([:], 'report') }
        assertEquals("Missing required argument 'report'", e.message)
    }

    @Test
    void 'required rejects null and blank values'() {
        Thrown.by(IllegalArgumentException) { Args.required([name: null], 'name') }
        Thrown.by(IllegalArgumentException) { Args.required([name: '   '], 'name') }
    }

    @Test
    void 'required accepts false and zero as real values'() {
        assertEquals(0, Args.required([min: 0], 'min'))
        assertEquals(false, Args.required([flag: false], 'flag'))
    }

    // safePath

    @Test
    void 'safePath accepts relative report paths'() {
        assertEquals('reports/php-cs-fixer.xml', Args.safePath('reports/php-cs-fixer.xml'))
        assertEquals('reports/coverage/cobertura.xml', Args.safePath('reports/coverage/cobertura.xml'))
    }

    @Test
    void 'safePath rejects single quotes, parent segments and absolute paths'() {
        ["a'b", "reports/x'; rm -rf /; '", '../etc/passwd', 'reports/../../x', '/etc/passwd'].each { String bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Args.safePath(bad) }
            assertTrue(e.message.contains('Unsafe path'), "message for ${bad}: ${e.message}")
        }
    }

    @Test
    void 'safePath rejects shell metacharacters and whitespace'() {
        ['a b', 'a$(id)', 'a`id`', 'a;b', "a\nb", 'a\\b', ''].each { String bad ->
            Thrown.by(IllegalArgumentException) { Args.safePath(bad) }
        }
    }

    @Test
    void 'safePath rejects null'() {
        Thrown.by(IllegalArgumentException) { Args.safePath(null) }
    }

    // envName

    @Test
    void 'envName accepts lowercase names'() {
        assertEquals('staging', Args.envName('staging'))
        assertEquals('prod-eu1', Args.envName('prod-eu1'))
    }

    @Test
    void 'envName rejects traversal, uppercase and junk'() {
        ['../x', 'Prod', 'a/b', '1abc', '', 'a' * 32, null].each { bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Args.envName(bad as String) }
            assertTrue(e.message.startsWith('Invalid environment name'), e.message)
        }
    }

    // percent

    @Test
    void 'percent converts numbers and numeric strings to BigDecimal'() {
        assertEquals(new BigDecimal('80'), Args.percent(80))
        assertEquals(new BigDecimal('72.5'), Args.percent('72.5'))
        assertEquals(new BigDecimal('0'), Args.percent(0))
        assertEquals(new BigDecimal('100'), Args.percent(new BigDecimal('100')))
    }

    @Test
    void 'percent rejects values outside 0-100 and non numbers'() {
        [-1, 100.01, 'abc', null, ''].each { bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Args.percent(bad) }
            assertTrue(e.message.contains('between 0 and 100'), e.message)
        }
    }

    // positiveNumber

    @Test
    void 'positiveNumber accepts numbers above zero'() {
        assertEquals(new BigDecimal('30'), Args.positiveNumber(30))
        assertEquals(new BigDecimal('0.5'), Args.positiveNumber('0.5'))
    }

    @Test
    void 'positiveNumber rejects zero, negatives and non numbers'() {
        [0, -1, 'abc', null, ''].each { bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Args.positiveNumber(bad) }
            assertTrue(e.message.contains('positive number'), e.message)
        }
    }

    // safeAbsolutePath

    @Test
    void 'safeAbsolutePath accepts plain absolute paths'() {
        assertEquals('/srv/deploy/staging', Args.safeAbsolutePath('/srv/deploy/staging'))
    }

    @Test
    void 'safeAbsolutePath rejects relative paths, quotes, parent segments and metacharacters'() {
        ['srv/deploy', "/srv/it's", '/srv/../etc', '/srv/a b', '/srv/$(id)', '/', '', null].each { bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { Args.safeAbsolutePath(bad as String) }
            assertTrue(e.message.contains('Unsafe absolute path'), e.message)
        }
    }
}
