package io.github.tayguara.ci

import com.cloudbees.groovy.cps.NonCPS

/** Slot arithmetic for the blue-green deploy. Pure functions, no Jenkins steps. */
class BlueGreen {

    private static final List<String> COLORS = ['blue', 'green']

    /**
     * Turns the output of `readlink current` (relative or absolute) into a color.
     * Empty output means nothing is live yet and gives null.
     */
    @NonCPS
    static String colorOf(String readlinkOutput) {
        String text = readlinkOutput == null ? '' : readlinkOutput.trim()
        if (text.isEmpty()) {
            return null
        }
        String last = text.split('/').toList().findAll { String s -> !s.isEmpty() }.last()
        return validColor(last)
    }

    @NonCPS
    static String other(String color) {
        return validColor(color) == 'blue' ? 'green' : 'blue'
    }

    /** The slot a deploy writes to, or a rollback switches to. Both are the idle slot. */
    @NonCPS
    static String target(String live, boolean rollback) {
        if (live == null) {
            if (rollback) {
                throw new IllegalArgumentException('Rollback: nothing is live yet')
            }
            return 'blue'
        }
        return other(live)
    }

    @NonCPS
    private static String validColor(String color) {
        if (!COLORS.contains(color)) {
            throw new IllegalArgumentException("Unknown slot '${color}': expected blue or green".toString())
        }
        return color
    }
}
