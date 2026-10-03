import io.github.tayguara.ci.Args

/**
 * Loads config/environments/<name>.yaml: data only (paths, URLs, credential IDs), never secrets.
 * Returns a plain Map with at least releasesDir, healthUrl and credentialsId.
 *
 *   Map cfg = loadEnvConfig('staging')
 */
Map call(String name) {
    String envName = Args.envName(name)
    String file = "config/environments/${envName}.yaml"
    Map result = [:]

    if (!fileExists(file)) {
        error("Unknown environment '${envName}': ${file} not found")
    } else {
        Map loaded = readYaml(file: file)
        for (String key : ['releasesDir', 'healthUrl', 'credentialsId']) {
            if (loaded[key] == null) {
                error("Environment '${envName}' is missing '${key}' in ${file}")
            }
        }
        Args.safeAbsolutePath(loaded.releasesDir as String)
        result = new LinkedHashMap(loaded)
    }
    return result
}
