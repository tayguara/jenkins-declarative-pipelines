package vars

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import support.Fixtures
import support.LibraryTestBase
import support.PipelineError
import support.Thrown

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class LoadEnvConfigTest extends LibraryTestBase {

    @BeforeEach
    void stepSetUp() {
        errorThrows()
    }

    @Test
    void 'loads the config of a known environment as a plain map'() {
        putFile('config/environments/staging.yaml', Fixtures.text('environments/staging.yaml'))

        Map cfg = runSnippet("loadEnvConfig('staging')") as Map

        assertEquals([releasesDir: '/srv/deploy/staging', healthUrl: 'http://web-staging:8000/health',
                      credentialsId: 'deploy-token-staging'], cfg)
        assertEquals(LinkedHashMap, cfg.getClass())
    }

    @Test
    void 'rejects names that could leave the config directory'() {
        ['../x', 'Prod', 'a/b', ''].each { String bad ->
            IllegalArgumentException e = Thrown.by(IllegalArgumentException) { runSnippet("loadEnvConfig('${bad}')") }
            assertTrue(e.message.startsWith('Invalid environment name'), e.message)
        }
        assertTrue(callsTo('readYaml').isEmpty())
    }

    @Test
    void 'an environment without a file fails with its name'() {
        PipelineError e = Thrown.by(PipelineError) { runSnippet("loadEnvConfig('qa')") }
        assertEquals("Unknown environment 'qa': config/environments/qa.yaml not found", e.message)
    }

    @Test
    void 'a missing required key fails and names the key'() {
        ['releasesDir', 'healthUrl', 'credentialsId'].each { String key ->
            String yaml = Fixtures.text('environments/staging.yaml').readLines().findAll { String l -> !l.startsWith(key) }.join('\n')
            putFile('config/environments/staging.yaml', yaml)
            PipelineError e = Thrown.by(PipelineError) { runSnippet("loadEnvConfig('staging')") }
            assertEquals("Environment 'staging' is missing '${key}' in config/environments/staging.yaml".toString(), e.message)
        }
    }

    @Test
    void 'a releasesDir that is not a safe absolute path is rejected'() {
        putFile('config/environments/staging.yaml', Fixtures.text('environments/staging.yaml').replace('/srv/deploy/staging', "/srv/x'; id; '"))
        Thrown.by(IllegalArgumentException) { runSnippet("loadEnvConfig('staging')") }
    }
}
