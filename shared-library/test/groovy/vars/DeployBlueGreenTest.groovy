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

class DeployBlueGreenTest extends LibraryTestBase {

    private static final String DIR = '/srv/deploy/staging'

    private final List<Map> smokeCalls = []

    @BeforeEach
    void stepSetUp() {
        errorThrows()
        binding.getVariable('env').GATE_RECORDS = '/ws/gates'
        putFile('config/environments/staging.yaml', Fixtures.text('environments/staging.yaml'))
    }

    // fakes

    private void liveIs(String color) {
        mockSh('readlink', 0, color == null ? '' : "${color}\n".toString())
    }

    private static String ok(String slot) {
        return """{"status":"ok","slot":"${slot}","version":"abc1234","build":"12"}""".toString()
    }

    /** Each curl call, in order, gets the next answer: [exit: int, body: String]. Records the slot it asked for. */
    private void smokeAnswers(List<Map> answers) {
        Iterator<Map> next = answers.iterator()
        mockSh('curl') { String script ->
            Map answer = next.next()
            smokeCalls << [slot: binding.getVariable('env').SMOKE_SLOT]
            if (answer.body != null) {
                putFile('smoke-response.json', answer.body as String)
            }
            return [stdout: '', exitValue: (answer.exit ?: 0) as int]
        }
    }

    private void deploy(String extra = '') {
        runSnippet("deployBlueGreen(environment: 'staging', buildDir: 'dist/release'${extra})")
    }

    private void rollback(String extra = '') {
        runSnippet("deployBlueGreen(environment: 'staging', rollback: true${extra})")
    }

    private String switchScript(String color) {
        return "ln -sfn '${color}' '${DIR}/current.next' && mv -Tf '${DIR}/current.next' '${DIR}/current'".toString()
    }

    private List<String> switches() {
        return shScripts().findAll { String s -> s.contains('mv -Tf') }
    }

    private Map record(String name) {
        return new JsonSlurperClassic().parseText(fileText("/ws/gates/${name}.json"))
    }

    // deploy

    @Test
    void 'the first deploy, with nothing live, goes to blue'() {
        liveIs(null)
        smokeAnswers([[body: ok('blue')], [body: ok('blue')]])

        deploy()

        assertTrue(shScripts().contains("rsync -a --delete 'dist/release/' '${DIR}/blue/'".toString()), shScripts().toString())
        assertEquals([switchScript('blue')], switches())
        assertTrue(echoes().contains('Live color is now blue (was none)'), echoes().toString())
        assertEquals('staging: none -> blue', record('deploy').detail)
        assertEquals(true, record('deploy').passed)
    }

    @Test
    void 'with blue live, a deploy goes to green'() {
        liveIs('blue')
        smokeAnswers([[body: ok('green')], [body: ok('green')]])

        deploy()

        assertTrue(shScripts().contains("rsync -a --delete 'dist/release/' '${DIR}/green/'".toString()), shScripts().toString())
        assertEquals([switchScript('green')], switches())
        assertTrue(echoes().contains('Live color is now green (was blue)'), echoes().toString())
    }

    @Test
    void 'with green live, a deploy goes back to blue'() {
        liveIs('green')
        smokeAnswers([[body: ok('blue')], [body: ok('blue')]])

        deploy()

        assertEquals([switchScript('blue')], switches())
    }

    @Test
    void 'the idle slot is checked before the switch and the live one after'() {
        liveIs('blue')
        smokeAnswers([[body: ok('green')], [body: ok('green')]])

        deploy()

        assertEquals([[slot: 'green'], [slot: '']], smokeCalls)
        List<String> sh = shScripts()
        int firstCurl = sh.findIndexOf { String s -> s.contains('curl') }
        int theSwitch = sh.findIndexOf { String s -> s.contains('mv -Tf') }
        int lastCurl = sh.findLastIndexOf { String s -> s.contains('curl') }
        assertTrue(firstCurl < theSwitch && theSwitch < lastCurl, sh.toString())
    }

    @Test
    void 'the live pointer is never replaced in place'() {
        liveIs('blue')
        smokeAnswers([[body: ok('green')], [body: ok('green')]])

        deploy()

        assertFalse(shScripts().any { String s -> s.contains("ln -sfn 'green' '${DIR}/current'") }, shScripts().toString())
    }

    @Test
    void 'a failed smoke check of the idle slot leaves the live slot alone'() {
        liveIs('blue')
        smokeAnswers([[exit: 22]])

        PipelineError e = Thrown.by(PipelineError) { deploy() }

        assertEquals('Deploy: smoke check of idle slot green failed; live slot unchanged', e.message)
        assertEquals([], switches())
        assertEquals(false, record('deploy').passed)
        assertEquals(1, smokeCalls.size())
    }

    @Test
    void 'a preview answered by the wrong slot cancels the deploy'() {
        liveIs('blue')
        smokeAnswers([[body: ok('blue')]])

        PipelineError e = Thrown.by(PipelineError) { deploy() }

        assertEquals('Deploy: smoke check of idle slot green failed; live slot unchanged', e.message)
        assertEquals([], switches())
    }

    @Test
    void 'a failed health check after the switch switches back to the previous slot'() {
        liveIs('blue')
        smokeAnswers([[body: ok('green')], [exit: 22]])

        PipelineError e = Thrown.by(PipelineError) { deploy() }

        assertEquals('Deploy: health check failed after switching to green; switched back to blue', e.message)
        assertEquals([switchScript('green'), switchScript('blue')], switches())
        assertEquals(false, record('deploy').passed)
    }

    @Test
    void 'a live answer from the wrong slot also switches back'() {
        liveIs('blue')
        smokeAnswers([[body: ok('green')], [body: ok('blue')]])

        PipelineError e = Thrown.by(PipelineError) { deploy() }

        assertEquals('Deploy: health check failed after switching to green; switched back to blue', e.message)
        assertEquals([switchScript('green'), switchScript('blue')], switches())
    }

    @Test
    void 'a failed first deploy leaves nothing live instead of a broken release'() {
        liveIs(null)
        smokeAnswers([[body: ok('blue')], [exit: 22]])

        PipelineError e = Thrown.by(PipelineError) { deploy() }

        assertEquals('Deploy: health check failed after switching to blue; nothing is live now', e.message)
        assertTrue(shScripts().contains("rm -f '${DIR}/current'".toString()), shScripts().toString())
    }

    // rollback

    @Test
    void 'a rollback switches to the previous slot without copying anything'() {
        liveIs('green')
        putFile("${DIR}/blue/RELEASE".toString(), '{"version":"old"}')
        smokeAnswers([[body: ok('blue')], [body: ok('blue')]])

        rollback()

        assertFalse(shScripts().any { String s -> s.contains('rsync') }, shScripts().toString())
        assertEquals([switchScript('blue')], switches())
        assertEquals([[slot: 'blue'], [slot: '']], smokeCalls)
        assertTrue(echoes().contains('Live color is now blue (was green)'), echoes().toString())
        assertEquals('staging: green -> blue', record('rollback').detail)
    }

    @Test
    void 'a rollback preview answered by the wrong slot cancels the rollback'() {
        liveIs('green')
        putFile("${DIR}/blue/RELEASE".toString(), '{"version":"old"}')
        smokeAnswers([[body: ok('green')]])

        PipelineError e = Thrown.by(PipelineError) { rollback() }

        assertEquals('Rollback: smoke check of idle slot blue failed; live slot unchanged', e.message)
        assertEquals([], switches())
    }

    @Test
    void 'a rollback with nothing live fails'() {
        liveIs(null)

        PipelineError e = Thrown.by(PipelineError) { rollback() }

        assertEquals('Rollback: nothing is live in staging yet', e.message)
        assertEquals([], switches())
        assertEquals(false, record('rollback').passed)
    }

    @Test
    void 'a rollback to a slot with no release fails before touching anything'() {
        liveIs('green')

        PipelineError e = Thrown.by(PipelineError) { rollback() }

        assertEquals('Rollback: slot blue holds no release', e.message)
        assertEquals([], switches())
        assertTrue(callsTo('withCredentials').isEmpty())
    }

    @Test
    void 'a failed smoke check of the previous slot cancels the rollback'() {
        liveIs('green')
        putFile("${DIR}/blue/RELEASE".toString(), '{"version":"old"}')
        smokeAnswers([[exit: 22]])

        PipelineError e = Thrown.by(PipelineError) { rollback() }

        assertEquals('Rollback: smoke check of idle slot blue failed; live slot unchanged', e.message)
        assertEquals([], switches())
    }

    // arguments and reports

    @Test
    void 'an unknown environment fails before any command runs'() {
        PipelineError e = Thrown.by(PipelineError) {
            runSnippet("deployBlueGreen(environment: 'qa', buildDir: 'dist/release')")
        }
        assertEquals("Unknown environment 'qa': config/environments/qa.yaml not found", e.message)
        assertTrue(callsTo('sh').isEmpty())
    }

    @Test
    void 'a deploy needs a build dir and a safe one'() {
        assertEquals("Missing required argument 'buildDir'",
                Thrown.by(IllegalArgumentException) { runSnippet("deployBlueGreen(environment: 'staging')") }.message)
        Thrown.by(IllegalArgumentException) { runSnippet("deployBlueGreen(environment: 'staging', buildDir: \"x'y\")") }
        assertTrue(callsTo('sh').isEmpty())
    }

    @Test
    void 'a live pointer that is neither blue nor green is rejected'() {
        liveIs('red')
        Thrown.by(IllegalArgumentException) { deploy() }
        assertEquals([], switches())
    }

    @Test
    void 'the report file is written when asked for, even on failure'() {
        liveIs('blue')
        smokeAnswers([[exit: 22]])

        Thrown.by(PipelineError) { deploy(", report: 'app/reports/deploy.json'") }

        Map saved = new JsonSlurperClassic().parseText(fileText('/ws/app/reports/deploy.json'))
        assertEquals('Deploy', saved.name)
        assertEquals(false, saved.passed)
    }
}
