package unit

import io.github.tayguara.ci.BlueGreen
import org.junit.jupiter.api.Test
import support.Thrown

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class BlueGreenTest {

    @Test
    void 'colorOf reads a relative symlink target'() {
        assertEquals('blue', BlueGreen.colorOf('blue'))
        assertEquals('green', BlueGreen.colorOf('green\n'))
    }

    @Test
    void 'colorOf reads the last segment of an absolute target'() {
        assertEquals('green', BlueGreen.colorOf('/srv/deploy/staging/green'))
        assertEquals('blue', BlueGreen.colorOf('/srv/deploy/staging/blue/'))
    }

    @Test
    void 'colorOf returns null when nothing is live yet'() {
        assertNull(BlueGreen.colorOf(''))
        assertNull(BlueGreen.colorOf('  \n'))
        assertNull(BlueGreen.colorOf(null))
    }

    @Test
    void 'colorOf rejects unknown colors'() {
        ['red', '../blue-ish', 'Blue', 'bluegreen'].each { String bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { BlueGreen.colorOf(bad) }
            assertTrue(e.message.contains('Unknown slot'), e.message)
        }
    }

    @Test
    void 'other flips the color'() {
        assertEquals('green', BlueGreen.other('blue'))
        assertEquals('blue', BlueGreen.other('green'))
    }

    @Test
    void 'other rejects unknown colors'() {
        Thrown.by(IllegalArgumentException) { BlueGreen.other('purple') }
        Thrown.by(IllegalArgumentException) { BlueGreen.other(null) }
    }

    @Test
    void 'first deploy targets blue'() {
        assertEquals('blue', BlueGreen.target(null, false))
    }

    @Test
    void 'deploy targets the idle slot'() {
        assertEquals('green', BlueGreen.target('blue', false))
        assertEquals('blue', BlueGreen.target('green', false))
    }

    @Test
    void 'rollback targets the previous slot, which is the idle one'() {
        assertEquals('blue', BlueGreen.target('green', true))
        assertEquals('green', BlueGreen.target('blue', true))
    }

    @Test
    void 'rollback with nothing live has no target'() {
        IllegalArgumentException e = Thrown.by(IllegalArgumentException) { BlueGreen.target(null, true) }
        assertTrue(e.message.contains('nothing is live'), e.message)
    }
}
