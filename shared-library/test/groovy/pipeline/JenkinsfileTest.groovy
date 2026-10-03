package pipeline

import com.lesfurets.jenkins.unit.MethodCall
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import support.Fixtures
import support.LibraryTestBase

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Loads the real ./Jenkinsfile with the real shared-library and checks what the pipeline decides:
 * stage order, what a rollback skips, what a failed gate stops, and a few policy guards.
 * It does not validate declarative syntax or run real plugins; the declarative linter and the
 * end-to-end smoke test (scripts/) cover that.
 */
class JenkinsfileTest extends LibraryTestBase {

    private static final List<String> GATES = ['Code style', 'Static analysis', 'Dependency audit', 'Unit tests']
    private static final String JENKINSFILE = new File('Jenkinsfile').text

    @BeforeEach
    void pipelineSetUp() {
        ['staging', 'production'].each { String name ->
            putFile("config/environments/${name}.yaml".toString(), new File("config/environments/${name}.yaml").text)
        }
        runWith('staging', false)
        // Reports the tools would have written in app/reports.
        putFile('/ws/app/reports/php-cs-fixer.xml', Fixtures.text('checkstyle-clean.xml'))
        putFile('/ws/app/reports/phpstan.json', Fixtures.text('phpstan-clean.json'))
        putFile('/ws/app/reports/composer-audit.json', Fixtures.text('composer-audit-clean.json'))
        putFile('/ws/app/reports/coverage/cobertura.xml', Fixtures.text('cobertura-85.xml'))
        putFile('/ws/app/reports/coverage/crap4j.xml', Fixtures.text('crap4j-ok.xml'))
        // The deploy: nothing live yet, and every health check answers from blue.
        mockSh('readlink', 0, '')
        putFile('smoke-response.json', '{"status":"ok","slot":"blue","version":"abc1234","build":"12"}')
    }

    private void runWith(String targetEnv, boolean rollback) {
        binding.setVariable('params', [TARGET_ENV: targetEnv, ROLLBACK: rollback].asImmutable())
    }

    private void runPipeline() {
        runScript('Jenkinsfile')
    }

    private List<String> gateNames() {
        return callsTo('runGate').collect { MethodCall c -> (c.args[0] as Map).name as String }
    }

    private String summary() {
        return fileText('/ws/app/reports/build-summary.md')
    }

    // happy path

    @Test
    void 'a green build runs the stages in order, deploys to the chosen environment and reports once'() {
        runPipeline()

        assertEquals(['Install', 'Quality gates', 'Code style', 'Static analysis', 'Dependency audit', 'Unit tests',
                      'Coverage', 'Complexity (CRAP)', 'Package', 'Deploy'], stagesExecuted())
        assertTrue(echoes().contains('Skipping stage Rollback'), echoes().toString())
        assertEquals(GATES, gateNames())
        assertEquals(1, callsTo('coverageGate').size())
        assertEquals(80, (callsTo('coverageGate')[0].args[0] as Map).min)
        assertEquals(30, (callsTo('crapGate')[0].args[0] as Map).max)

        List<MethodCall> deploys = callsTo('deployBlueGreen')
        assertEquals(1, deploys.size())
        assertEquals('staging', (deploys[0].args[0] as Map).environment)
        assertEquals('dist/release', (deploys[0].args[0] as Map).buildDir)
        assertEquals(1, callsTo('notifyBuild').size())
        assertTrue(summary().contains('All gates passed.'), summary())
        assertEquals('SUCCESS: 6/6 gates passed (abc1234)', binding.getVariable('currentBuild').description)
        assertJobStatusSuccess()
    }

    @Test
    void 'the gates run in the app dir and write their records where notifyBuild looks'() {
        runPipeline()

        assertEquals('/ws/app/reports/gates', binding.getVariable('env').GATE_RECORDS.toString())
        assertTrue(files.keySet().containsAll(['/ws/app/reports/gates/code-style.json', '/ws/app/reports/gates/coverage.json',
                                               '/ws/app/reports/gates/deploy.json']), files.keySet().toString())
        assertTrue(callsTo('dir').every { MethodCall c -> c.args[0] == 'app' })
    }

    @Test
    void 'the target environment parameter is passed to the deploy'() {
        runWith('production', false)
        runPipeline()
        assertEquals('production', (callsTo('deployBlueGreen')[0].args[0] as Map).environment)
    }

    @Test
    void 'results are published even on a green build'() {
        runPipeline()
        assertEquals(1, callsTo('junit').size())
        assertEquals(1, callsTo('recordCoverage').size())
        assertEquals(1, callsTo('archiveArtifacts').size())
        assertEquals(1, callsTo('cleanWs').size())
    }

    @Test
    void 'the unit tests gate runs phpunit with the three reports and the report the later gates read'() {
        runPipeline()

        Map unitTests = callsTo('runGate').collect { MethodCall c -> c.args[0] as Map }.find { Map m -> m.name == 'Unit tests' }
        assertEquals('vendor/bin/phpunit --log-junit reports/junit/phpunit.xml ' +
                '--coverage-cobertura reports/coverage/cobertura.xml --coverage-crap4j reports/coverage/crap4j.xml',
                unitTests.command)
        assertEquals('reports/phpunit.txt', unitTests.report)
        assertEquals('reports/coverage/cobertura.xml', (callsTo('coverageGate')[0].args[0] as Map).report)
        assertEquals('reports/coverage/crap4j.xml', (callsTo('crapGate')[0].args[0] as Map).report)
    }

    @Test
    void 'post publishes results, then writes the summary, then archives it with the other reports'() {
        runPipeline()

        assertEquals('app/reports/junit/*.xml', (callsTo('junit')[0].args[0] as Map).testResults)
        Map coverage = callsTo('recordCoverage')[0].args[0] as Map
        assertEquals([[parser: 'COBERTURA', pattern: 'app/reports/coverage/cobertura.xml']], coverage.tools)
        assertEquals('app/reports/**', (callsTo('archiveArtifacts')[0].args[0] as Map).artifacts)

        List<String> post = helper.callStack.collect { MethodCall c -> c.methodName }
                .findAll { String name -> ['junit', 'recordCoverage', 'notifyBuild', 'archiveArtifacts'].contains(name) }
        assertEquals(['junit', 'recordCoverage', 'notifyBuild', 'archiveArtifacts'], post)
    }

    // rollback

    @Test
    void 'a rollback skips every gate and calls none of them'() {
        runWith('staging', true)
        mockSh('readlink', 0, 'green\n')
        putFile('/srv/deploy/staging/blue/RELEASE', '{"version":"old"}')

        runPipeline()

        assertEquals(['Rollback'], stagesExecuted())
        ['Install', 'Quality gates', 'Package', 'Deploy'].each { String name ->
            assertTrue(echoes().contains("Skipping stage ${name}".toString()), echoes().toString())
        }
        ['runGate', 'coverageGate', 'crapGate'].each { String step ->
            assertEquals(0, callsTo(step).size(), step)
        }
        List<MethodCall> deploys = callsTo('deployBlueGreen')
        assertEquals(1, deploys.size())
        assertEquals(true, (deploys[0].args[0] as Map).rollback)
        assertEquals(1, callsTo('notifyBuild').size())
        assertTrue(summary().contains('| Rollback | PASSED | staging: green -> blue |'), summary())
        assertTrue(summary().contains('No quality gates ran: this was a rollback (staging: green -> blue).'), summary())
        assertEquals('SUCCESS: rolled back staging (green -> blue) (abc1234)', binding.getVariable('currentBuild').description)
        assertJobStatusSuccess()
    }

    // a gate fails

    @Test
    void 'a failed gate stops the later stages, but the summary still runs and says where it stopped'() {
        mockSh('phpstan', 1)
        putFile('/ws/app/reports/phpstan.json', Fixtures.text('phpstan-errors.json'))

        runPipeline()

        assertJobStatusFailure()
        assertEquals(['Install', 'Quality gates', 'Code style', 'Static analysis'], stagesExecuted())
        ['Dependency audit', 'Unit tests', 'Coverage', 'Complexity (CRAP)', 'Package', 'Deploy'].each { String name ->
            assertTrue(echoes().contains("Stage \"${name}\" skipped due to earlier failure(s)".toString()), echoes().toString())
        }
        assertEquals(['Code style', 'Static analysis'], gateNames())
        assertEquals(0, callsTo('coverageGate').size())
        assertEquals(0, callsTo('deployBlueGreen').size())

        assertEquals(1, callsTo('junit').size())
        assertEquals(1, callsTo('notifyBuild').size())
        assertTrue(summary().contains('| Code style | PASSED | 0 violations |'), summary())
        assertTrue(summary().contains('Stopped at: Static analysis'), summary())
        assertEquals(1, callsTo('cleanWs').size())
    }

    @Test
    void 'coverage below the minimum stops before the package and the deploy'() {
        putFile('/ws/app/reports/coverage/cobertura.xml', Fixtures.text('cobertura-60.xml'))

        runPipeline()

        assertJobStatusFailure()
        assertEquals(GATES, gateNames())
        assertFalse(stagesExecuted().contains('Package'))
        assertFalse(stagesExecuted().contains('Deploy'))
        assertEquals(0, callsTo('crapGate').size())
        assertEquals(0, callsTo('deployBlueGreen').size())
        assertTrue(summary().contains('Stopped at: Coverage'), summary())
    }

    // policy guards, on the text of the Jenkinsfile

    @Test
    void 'the shared library is pinned to a tag'() {
        assertTrue((JENKINSFILE =~ /@Library\('ci-common@v\d+\.\d+\.\d+'\)/).find(), 'pin ci-common to a release tag')
    }

    @Test
    void 'there is no script block and no try or catch'() {
        assertFalse((JENKINSFILE =~ /\bscript\s*\{/).find(), 'use when {} and library steps instead of script {}')
        assertFalse((JENKINSFILE =~ /\b(try|catch|finally)\b/).find(), 'use post {} instead of try/catch')
    }

    @Test
    void 'no sh step uses double quotes, so no Groovy interpolation reaches a shell'() {
        assertFalse((JENKINSFILE =~ /\bsh\s*\(?\s*"/).find(), 'use single quotes for sh')
        assertFalse((JENKINSFILE =~ /\bwithCredentials\b/).find(), 'credentials are handled inside library steps')
    }

    @Test
    void 'the policy options are present'() {
        String options = (JENKINSFILE =~ /(?s)options\s*\{(.*?)\n    \}/)[0][1]
        ['timeout', 'timestamps', 'disableConcurrentBuilds', 'buildDiscarder'].each { String option ->
            assertTrue(options.contains(option), "options { } must contain ${option}")
        }
        assertTrue(JENKINSFILE.contains("label 'php'"))
    }

    @Test
    void 'the stages are declared in the contract order'() {
        List<String> declared = (JENKINSFILE =~ /stage\('([^']+)'\)/).collect { List m -> m[1] as String }
        assertEquals(['Install', 'Quality gates', 'Code style', 'Static analysis', 'Dependency audit', 'Unit tests',
                      'Coverage', 'Complexity (CRAP)', 'Package', 'Deploy', 'Rollback'], declared)
    }

    @Test
    void 'the summary and cleanup run in post, not in a stage'() {
        String post = JENKINSFILE.substring(JENKINSFILE.indexOf('post {'))
        assertTrue(post.contains('always {'))
        assertTrue(post.contains('notifyBuild('))
        assertTrue(post.contains('cleanup {'))
        assertFalse(JENKINSFILE.substring(0, JENKINSFILE.indexOf('post {')).contains('notifyBuild'))
    }
}
