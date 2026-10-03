import io.github.tayguara.ci.Args
import io.github.tayguara.ci.BlueGreen

/**
 * Calls a health endpoint with the deploy token and reports what came back. It never fails the
 * build: the caller decides what an unhealthy answer means.
 *
 *   Map r = smokeCheck(url: cfg.healthUrl, credentialsId: cfg.credentialsId, slot: 'green')
 *   // r = [ok: Boolean, status: String, slot: String, version: String]
 *
 * With `slot`, the request asks the router for that slot (preview) instead of the live one.
 * The shell script is single quoted: the token and the URL reach it through the environment,
 * so Groovy never interpolates them and Jenkins can mask the token. The token goes to curl
 * on stdin (a config line), not on its command line. Tokens must not contain '"' or '\'.
 */
Map call(Map args) {
    String url = Args.required(args, 'url')
    String credentialsId = Args.required(args, 'credentialsId')
    String slot = args.slot ? BlueGreen.colorOf(args.slot as String) : null
    Map result = [ok: false, status: 'unreachable', slot: null, version: null]
    int exitCode = 0

    withCredentials([string(credentialsId: credentialsId, variable: 'DEPLOY_TOKEN')]) {
        withEnv(["SMOKE_URL=${url}".toString(), "SMOKE_SLOT=${slot ?: ''}".toString()]) {
            exitCode = sh(label: 'Smoke check', returnStatus: true, script: '''
                set --
                if [ -n "$SMOKE_SLOT" ]; then set -- -H "X-Slot: $SMOKE_SLOT"; fi
                printf 'header = "X-Deploy-Token: %s"\\n' "$DEPLOY_TOKEN" | curl -fsS --config - \\
                    --retry 5 --retry-connrefused --retry-delay 1 --max-time 5 \\
                    "$@" -o smoke-response.json "$SMOKE_URL"
            ''')
        }
    }

    if (exitCode == 0) {
        try {
            Map body = readJSON(file: 'smoke-response.json')
            result = [ok: body.status == 'ok', status: body.status as String,
                      slot: body.slot as String, version: body.version as String]
        } catch (InterruptedException aborted) {
            throw aborted    // an abort must stop the build, not become an unhealthy answer
        } catch (Exception ignored) { // groovylint-disable-line CatchException
            // A 200 that is not our JSON (a proxy page, for instance) is an unhealthy answer, not a crash.
            result = [ok: false, status: 'invalid response', slot: null, version: null]
        }
    }
    sh "rm -f 'smoke-response.json'"
    return result
}
