package com.zippush.app

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Resets the process-singleton SessionState around every test that
 * opens the activity, so state cannot leak between test classes (the
 * singleton outlives each activity instance, which is exactly its
 * production purpose).
 */
class SessionStateTestRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                SessionState.resetForTest()
                try {
                    base.evaluate()
                } finally {
                    SessionState.resetForTest()
                }
            }
        }
    }
}
