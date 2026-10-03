package io.github.tayguara.ci

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/**
 * One small JSON file per gate: written by the gate step, read back by notifyBuild.
 * Pure functions, no Jenkins steps. Reading never throws: a broken record is data too.
 */
class GateRecords {

    @NonCPS
    static String toJson(Map record) {
        Map copy = new LinkedHashMap(record)
        if (copy.at == null) {
            copy.at = System.currentTimeMillis()
        }
        return JsonOutput.toJson(copy)
    }

    /** Where a gate's record lives: one file per gate, named after the gate. Names cannot walk the file system. */
    @NonCPS
    static String recordPath(String dir, String gateName) {
        String slug = gateName.toLowerCase().replaceAll('[^a-z0-9]+', '-').replaceAll('^-|-$', '')
        return (dir ?: 'reports/gates') + '/' + slug + '.json'
    }

    /** Parses the records and sorts them by timestamp. Unreadable ones go last, flagged. */
    @NonCPS
    static List<Map> parseAndSort(List<String> jsons) {
        List<Map> readable = []
        List<Map> broken = []
        for (String json : jsons) {
            Map parsed = parse(json)
            if (parsed.isEmpty()) {
                broken << [name: 'unreadable record', passed: null, unreadable: true]
            } else {
                readable << parsed
            }
        }
        return readable.sort(false) { Map a, Map b -> (a.at ?: 0L) <=> (b.at ?: 0L) } + broken
    }

    /** The record as a Map, or an empty Map when the text is not a JSON object. */
    @NonCPS
    private static Map parse(String json) {
        Map result = [:]
        if (json != null && !json.trim().isEmpty()) {
            try {
                Object parsed = new JsonSlurperClassic().parseText(json)
                if (parsed in Map) {
                    result = (Map) parsed
                }
            } catch (JsonException ignored) {
                result = [:]    // not JSON: the caller flags it as unreadable
            }
        }
        return result
    }
}
