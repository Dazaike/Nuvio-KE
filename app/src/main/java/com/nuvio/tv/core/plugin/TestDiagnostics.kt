package com.nuvio.tv.core.plugin

import android.util.Log
import java.util.Collections

private const val TAG = "TestDiagnostics"
private const val MAX_STEPS = 60
private const val MAX_SCRIPT_LINES = 40
private const val MAX_LINE_CHARS = 300

/**
 * Status lines collected during a scraper test run: what the manager did ([steps]) and what the
 * script printed to its console ([scriptLines]). Both are bounded, so a noisy script cannot fill
 * the screen or memory. Writers may be on different threads.
 */
class TestDiagnostics {
    private val stepList: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scriptList: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val steps: List<String> get() = synchronized(stepList) { stepList.toList() }
    val scriptLines: List<String> get() = synchronized(scriptList) { scriptList.toList() }

    fun addStep(step: String) {
        if (stepList.size < MAX_STEPS) stepList.add(step.take(MAX_LINE_CHARS))
        Log.d(TAG, step)
    }

    fun addScriptLine(line: String) {
        if (scriptList.size < MAX_SCRIPT_LINES) scriptList.add(line.take(MAX_LINE_CHARS))
    }
}
