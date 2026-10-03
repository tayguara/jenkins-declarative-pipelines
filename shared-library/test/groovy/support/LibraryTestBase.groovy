package support

import com.lesfurets.jenkins.unit.MethodCall
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import groovy.json.JsonSlurperClassic
import org.junit.jupiter.api.BeforeEach

import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.regex.Pattern

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource

/**
 * Base class for every test that needs Jenkins steps.
 *
 * It registers the real ci-common library (from shared-library/) and replaces the plugin steps
 * JenkinsPipelineUnit does not know with in-memory fakes: a tiny workspace backs readFile,
 * writeFile, fileExists, readJSON, readYaml and findFiles, and sh is routed by regex.
 */
abstract class LibraryTestBase extends DeclarativePipelineTest {

    static final String WORKSPACE = '/ws'

    /** In-memory workspace: absolute path to content. Use putFile() to fill it. */
    Map<String, String> files = [:]

    protected final List<String> dirStack = []

    @BeforeEach
    @Override
    void setUp() {
        super.setUp()
        binding.setVariable('env', [WORKSPACE: WORKSPACE, JOB_NAME: 'php-app-pipeline', BUILD_NUMBER: '12',
                                    GIT_COMMIT: 'abc1234def5678'])
        Map build = binding.getVariable('currentBuild')
        build.durationString = '1 min 3 sec and counting'
        build.getBuildCauses = { String type -> [[shortDescription: 'Started by user admin', userId: 'admin', userName: 'Admin']] }

        helper.registerSharedLibrary(library()
                .name('ci-common')
                .defaultVersion('<notNeeded>')
                .allowOverride(true)
                .implicit(true)
                .targetPath('<notNeeded>')
                .retriever(projectSource('shared-library'))
                .build())
        registerWorkspaceSteps()
        registerPluginSteps()
    }

    // workspace

    /** Puts a file in the workspace. A relative path is resolved against the current dir() block. */
    void putFile(String path, String content) {
        files[resolve(path)] = content
    }

    String fileText(String path) {
        return files[resolve(path)]
    }

    protected String resolve(String path) {
        String base = path.startsWith('/') ? '' : ([WORKSPACE] + dirStack).join('/') + '/'
        return Paths.get(base + path).normalize().toString()
    }

    private void registerWorkspaceSteps() {
        helper.registerAllowedMethod('dir', [String, Closure], { String path, Closure body ->
            dirStack << path
            try {
                body.delegate = delegate
                helper.callClosure(body)
            } finally {
                dirStack.remove(dirStack.size() - 1)
            }
        })
        helper.registerAllowedMethod('writeFile', [Map], { Map args -> putFile(args.file as String, args.text as String) })
        helper.registerAllowedMethod('readFile', [Map], { Map args -> readText(args.file as String) })
        helper.registerAllowedMethod('readFile', [String], { String file -> readText(file) })
        helper.registerAllowedMethod('fileExists', [String], { String file -> files.containsKey(resolve(file)) })
        helper.registerAllowedMethod('readJSON', [Map], { Map args ->
            new JsonSlurperClassic().parseText(args.text ? args.text as String : readText(args.file as String))
        })
        helper.registerAllowedMethod('readYaml', [Map], { Map args -> parseFlatYaml(readText(args.file as String)) })
        helper.registerAllowedMethod('findFiles', [Map], { Map args ->
            Pattern glob = Pattern.compile('^' + (args.glob as String).replace('.', '\\.').replace('**', '\u0000')
                    .replace('*', '[^/]*').replace('\u0000', '.*') + '$')
            String prefix = resolve('.') + '/'
            files.keySet().findAll { String abs -> abs.startsWith(prefix) && glob.matcher(abs.substring(prefix.length())).matches() }
                    .sort().collect { String abs -> [path: abs.substring(prefix.length()), name: Paths.get(abs).fileName.toString()] }
        })
    }

    protected String readText(String file) {
        String content = files[resolve(file)]
        if (content == null) {
            throw new NoSuchFileException(file)
        }
        return content
    }

    /** Test-only reader for the flat "key: value" files under config/environments. */
    protected static Map parseFlatYaml(String text) {
        Map result = [:]
        text.readLines().findAll { String l -> l.trim() && !l.trim().startsWith('#') }.each { String l ->
            int colon = l.indexOf(':')
            result[l.substring(0, colon).trim()] = l.substring(colon + 1).trim()
        }
        return result
    }

    // plugin steps with no behavior worth faking

    private void registerPluginSteps() {
        ['recordCoverage', 'junit', 'archiveArtifacts'].each { String name ->
            helper.registerAllowedMethod(name, [Map])
        }
    }

    // sh routing

    /** Makes any sh script matching the regex (found anywhere in the script) return this status and output. */
    void mockSh(String regex, int status = 0, String stdout = '') {
        helper.addShMock(Pattern.compile("(?s).*(?:${regex}).*"), stdout, status)
    }

    /** Like mockSh, but the answer is computed from the script. Return [stdout: String, exitValue: int]. */
    void mockSh(String regex, Closure answer) {
        helper.addShMock(Pattern.compile("(?s).*(?:${regex}).*"), { String script, Object... groups -> answer.call(script) })
    }

    // error handling

    /** Makes `error` throw like the real step, so a step under test stops where it would stop in Jenkins. */
    void errorThrows() {
        helper.registerAllowedMethod('error', [String], { String message ->
            updateBuildStatus('FAILURE')
            throw new PipelineError(message)
        })
    }

    // call stack readers

    List<MethodCall> callsTo(String name) {
        return helper.callStack.findAll { MethodCall c -> c.methodName == name }
    }

    List<String> stagesExecuted() {
        return callsTo('stage').collect { MethodCall c -> c.args[0].toString() }
    }

    List<String> echoes() {
        return callsTo('echo').collect { MethodCall c -> c.args[0].toString() }
    }

    List<String> shScripts() {
        return callsTo('sh').collect { MethodCall c ->
            c.args[0] instanceof Map ? (c.args[0] as Map).script.toString() : c.args[0].toString()
        }
    }

    List<Map> shCalls() {
        return callsTo('sh').collect { MethodCall c -> c.args[0] instanceof Map ? (c.args[0] as Map) : [script: c.args[0].toString()] }
    }

    /** Runs a snippet of pipeline code as a script, with the library on the classpath. */
    Object runSnippet(String code) {
        return runScript(loadInlineScript(code))
    }
}
