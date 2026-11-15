package kr.ddakta.pickup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickupTripServiceTest {
    @Test fun stoppingWaitsForAnInFlightUpdateBeforeFinishing() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var service: PickupTripService
        lateinit var scope: CoroutineScope
        lateinit var worker: Job
        val stopSharing = PickupTripService::class.java.getDeclaredMethod("stopSharing").apply {
            isAccessible = true
        }

        instrumentation.runOnMainSync {
            // No onCreate call or appointment: this exercises shutdown without device services or network calls.
            service = PickupTripService()
            scope = PickupTripService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
            }.get(service) as CoroutineScope
            worker = scope.launch(Dispatchers.IO) {
                // A blocking HTTP request also finishes independently of coroutine cancellation.
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                }
            }
            PickupTripService::class.java.getDeclaredField("worker").apply {
                isAccessible = true
            }.set(service, worker)
        }

        try {
            withTimeout(5_000) { started.await() }
            var shutdownJobs = emptyList<Job>()
            instrumentation.runOnMainSync {
                stopSharing.invoke(service)
                shutdownJobs = scope.coroutineContext[Job]!!.children.filter { it !== worker }.toList()
            }
            assertEquals("Shutdown must wait until the in-flight update finishes", 1, shutdownJobs.size)
            val shutdown = shutdownJobs.single()
            assertTrue(worker.isCancelled)
            assertFalse(worker.isCompleted)
            assertTrue(shutdown.isActive)

            instrumentation.runOnMainSync {
                stopSharing.invoke(service)
                assertEquals(listOf(shutdown), scope.coroutineContext[Job]!!.children.filter { it !== worker }.toList())
            }

            release.complete(Unit)
            withTimeout(5_000) {
                worker.join()
                shutdown.join()
            }
            assertTrue(shutdown.isCompleted)
        } finally {
            release.complete(Unit)
            withTimeout(5_000) { worker.join() }
            instrumentation.runOnMainSync { scope.cancel() }
        }
    }
}
