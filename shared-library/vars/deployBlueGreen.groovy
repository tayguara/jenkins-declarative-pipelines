import io.github.tayguara.ci.Args
import io.github.tayguara.ci.BlueGreen
import io.github.tayguara.ci.GateRecords

/**
 * Blue-green deploy (or rollback) with two directories per environment and a `current` symlink.
 *
 *   deployBlueGreen(environment: 'staging', buildDir: 'dist/release')   // copy to the idle slot, switch
 *   deployBlueGreen(environment: 'staging', rollback: true)             // switch back, copy nothing
 *
 * Steps: check the idle slot with a preview request, switch the symlink atomically (a temporary
 * link renamed with mv -T), check the live URL, and switch back if it is not healthy. Everything
 * is recorded as a gate record, success or not, and the build fails last.
 */
def call(Map args) {
    String environment = Args.required(args, 'environment')
    boolean rollback = args.rollback == true
    String buildDir = rollback ? null : Args.safePath(Args.required(args, 'buildDir') as String)
    String report = args.report ? Args.safePath(args.report as String) : null
    String label = rollback ? 'Rollback' : 'Deploy'
    Map cfg = loadEnvConfig(environment)
    String dir = cfg.releasesDir

    String live = BlueGreen.colorOf(sh(returnStdout: true,
            script: "if [ -L '${dir}/current' ]; then readlink '${dir}/current'; fi"))
    String target = (rollback && live == null) ? null : BlueGreen.target(live, rollback)
    String failure = null

    if (rollback && live == null) {
        failure = "Rollback: nothing is live in ${environment} yet"
    } else if (rollback && !fileExists("${dir}/${target}/RELEASE")) {
        failure = "Rollback: slot ${target} holds no release"
    } else {
        if (!rollback) {
            sh "mkdir -p '${dir}'"
            sh "rsync -a --delete '${buildDir}/' '${dir}/${target}/'"
        }
        Map preview = healthOf(cfg, target)
        if (!preview.ok || preview.slot != target) {
            failure = "${label}: smoke check of idle slot ${target} failed; live slot unchanged"
        } else {
            switchTo(dir, target)
            Map after = healthOf(cfg, null)
            if (!after.ok || after.slot != target) {
                failure = "${label}: health check failed after switching to ${target}; " + undo(dir, live)
            }
        }
    }

    String detail = failure == null ? "${environment}: ${live ?: 'none'} -> ${target}".toString() : failure.toString()
    String json = GateRecords.toJson([name: label, passed: failure == null, exitCode: failure == null ? 0 : 1,
            report: report ?: '', detail: detail])
    writeFile(file: GateRecords.recordPath(env.GATE_RECORDS, label), text: json)
    if (report) {
        writeFile(file: report, text: json)
    }
    if (failure != null) {
        error(failure)
    } else {
        echo "Live color is now ${target} (was ${live ?: 'none'})"
    }
}

/** Preview request for a slot, or the live URL when slot is null. */
Map healthOf(Map cfg, String slot) {
    return slot ? smokeCheck(url: cfg.healthUrl, credentialsId: cfg.credentialsId, slot: slot)
                : smokeCheck(url: cfg.healthUrl, credentialsId: cfg.credentialsId)
}

/** Atomic: build the new link next to the old one, then rename it over `current`. */
void switchTo(String dir, String color) {
    sh "ln -sfn '${color}' '${dir}/current.next' && mv -Tf '${dir}/current.next' '${dir}/current'"
}

/** Puts the previous slot back; on a first deploy there is none, so nothing stays live. */
String undo(String dir, String live) {
    if (live != null) {
        switchTo(dir, live)
        return "switched back to ${live}"
    }
    sh "rm -f '${dir}/current'"
    return 'nothing is live now'
}
