package vars

import org.junit.jupiter.api.Test
import support.LibraryTestBase
import support.Thrown

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class SmokeCheckTest extends LibraryTestBase {

    private static final String URL = 'http://web-staging:8000/health'
    private static final String OK_BODY = '{"status":"ok","slot":"green","version":"abc1234","build":"12"}'

    private Map smoke(String extra = '') {
        return runSnippet("smokeCheck(url: '${URL}', credentialsId: 'deploy-token-staging'${extra})") as Map
    }

    private List<String> withEnvList() {
        return callsTo('withEnv')[0].args[0] as List<String>
    }

    @Test
    void 'a healthy answer gives ok, status, slot and version'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', OK_BODY)

        Map result = smoke(", slot: 'green'")

        assertEquals([ok: true, status: 'ok', slot: 'green', version: 'abc1234'], result)
    }

    @Test
    void 'the token comes from the credential with the given id'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', OK_BODY)

        smoke()

        assertEquals('deploy-token-staging', callsTo('string')[0].args[0].credentialsId)
        assertEquals('DEPLOY_TOKEN', callsTo('string')[0].args[0].variable)
        assertEquals(1, callsTo('withCredentials').size())
    }

    @Test
    void 'the shell script has no Groovy interpolation of the url, the credential id or the token'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', OK_BODY)

        smoke(", slot: 'green'")

        String script = shScripts()[0]
        assertFalse(script.contains(URL), script)
        assertFalse(script.contains('deploy-token-staging'), script)
        assertTrue(script.contains('"$SMOKE_URL"'), script)
        assertTrue(script.contains('$DEPLOY_TOKEN'), script)
        assertTrue(withEnvList().contains("SMOKE_URL=${URL}".toString()), withEnvList().toString())
    }

    @Test
    void 'the slot travels in the environment and is empty when not given'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', OK_BODY)

        smoke(", slot: 'green'")
        assertTrue(withEnvList().contains('SMOKE_SLOT=green'), withEnvList().toString())

        helper.clearCallStack()
        smoke()
        assertTrue(withEnvList().contains('SMOKE_SLOT='), withEnvList().toString())
    }

    @Test
    void 'an unknown slot is rejected before curl runs'() {
        Thrown.by(IllegalArgumentException) { smoke(", slot: 'red'") }
        assertTrue(callsTo('sh').isEmpty())
    }

    @Test
    void 'a failing curl gives ok false without raising an error'() {
        mockSh('curl', 22)

        Map result = smoke()

        assertEquals(false, result.ok)
        assertEquals('unreachable', result.status)
        assertNull(result.slot)
        assertEquals('SUCCESS', binding.getVariable('currentBuild').result)
        assertTrue(callsTo('error').isEmpty())
    }

    @Test
    void 'an answer that is not JSON gives ok false'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', '<html>oops</html>')

        Map result = smoke()

        assertEquals(false, result.ok)
        assertEquals('invalid response', result.status)
    }

    @Test
    void 'an abort while reading the answer is not swallowed'() {
        mockSh('curl', 0)
        helper.registerAllowedMethod('readJSON', [Map], { Map args -> throw new InterruptedException('aborted') })

        Thrown.by(InterruptedException) { smoke() }
    }

    @Test
    void 'an answer with a status other than ok is not healthy'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', '{"status":"degraded","slot":"blue","version":"x"}')

        Map result = smoke()

        assertEquals(false, result.ok)
        assertEquals('degraded', result.status)
    }

    @Test
    void 'the response file is removed afterwards'() {
        mockSh('curl', 0)
        putFile('smoke-response.json', OK_BODY)

        smoke()

        assertTrue(shScripts().last().contains("rm -f 'smoke-response.json'"), shScripts().toString())
    }

    // The script is real shell: run it against a stub curl to see what curl would receive.

    private Map runScriptWithStubCurl(Map<String, String> env) {
        Path dir = Files.createTempDirectory('smoke-stub')
        try {
            Path bin = Files.createDirectory(dir.resolve('bin'))
            Path stub = bin.resolve('curl')
            stub.toFile().text = '''#!/bin/sh
printf '%s\\n' "$@" > "$STUB_DIR/argv"
cat > "$STUB_DIR/stdin"
while [ $# -gt 0 ]; do if [ "$1" = "-o" ]; then out=$2; fi; shift; done
echo '{"status":"ok"}' > "$out"
'''
            stub.toFile().setExecutable(true)
            mockSh('curl', 0)
            putFile('smoke-response.json', OK_BODY)
            smoke(env.SMOKE_SLOT ? ", slot: '${env.SMOKE_SLOT}'" : '')

            ProcessBuilder pb = new ProcessBuilder('/bin/sh', '-c', shScripts()[0])
            pb.directory(dir.toFile())
            pb.environment().putAll([PATH: "${bin}:${System.getenv('PATH')}".toString(), STUB_DIR: dir.toString(),
                                     DEPLOY_TOKEN: 'secret-token-value', SMOKE_URL: URL, SMOKE_SLOT: env.SMOKE_SLOT ?: ''])
            Process p = pb.start()
            p.waitFor()
            return [exit: p.exitValue(), argv: new File(dir.toFile(), 'argv').text, stdin: new File(dir.toFile(), 'stdin').text]
        } finally {
            dir.toFile().deleteDir()
        }
    }

    @Test
    void 'curl gets the slot header only for a slot and never sees the token in its arguments'() {
        Map withSlot = runScriptWithStubCurl([SMOKE_SLOT: 'green'])
        assertEquals(0, withSlot.exit)
        assertTrue(withSlot.argv.contains('X-Slot: green'), withSlot.argv)
        assertTrue(withSlot.argv.contains(URL), withSlot.argv)
        assertFalse(withSlot.argv.contains('secret-token-value'), withSlot.argv)
        assertTrue(withSlot.stdin.contains('X-Deploy-Token: secret-token-value'), withSlot.stdin)

        helper.clearCallStack()
        Map withoutSlot = runScriptWithStubCurl([:])
        assertFalse(withoutSlot.argv.contains('X-Slot'), withoutSlot.argv)
    }
}
