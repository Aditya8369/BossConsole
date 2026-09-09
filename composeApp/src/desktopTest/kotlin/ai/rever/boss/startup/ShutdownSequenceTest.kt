package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutdownSequenceTest {
    @Test
    fun executeRunsAllStepsInOrderEvenWhenExceptionsOccur() {
        val executionLog = mutableListOf<String>()
        var lockReleased = false

        val steps =
            listOf(
                ShutdownStep("step 1") {
                    executionLog.add("step1")
                },
                ShutdownStep("failing step 2") {
                    executionLog.add("step2")
                    error("Simulated failure in step 2")
                },
                ShutdownStep("step 3") {
                    executionLog.add("step3")
                },
            )

        ShutdownSequence.execute(
            steps = steps,
            releaseLock = {
                lockReleased = true
            },
        )

        assertEquals(listOf("step1", "step2", "step3"), executionLog)
        assertTrue(lockReleased)
    }

    @Test
    fun defaultStepsContainsExpectedNamedSteps() {
        val steps = ShutdownSequence.defaultSteps()
        val stepNames = steps.map { it.name }

        assertTrue(stepNames.any { it.contains("Last Session") })
        assertTrue(stepNames.any { it.contains("browser engine") })
        assertTrue(stepNames.any { it.contains("logger") })
        assertTrue(stepNames.any { it.contains("plugin store") })
    }
}
