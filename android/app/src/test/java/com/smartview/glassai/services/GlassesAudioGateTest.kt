package com.smartview.glassai.services

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GlassesAudioGateTest {
    @Test fun featureWaitsUntilWakeRecorderHasReleased() = runBlocking {
        val wake = requireNotNull(GlassesAudioGate.tryAcquireWake())
        val feature = async(start = CoroutineStart.UNDISPATCHED) { GlassesAudioGate.acquireFeature() }
        assertFalse(feature.isCompleted)
        assertEquals(1, GlassesAudioGate.featureRequests.value)
        assertNull(GlassesAudioGate.tryAcquireWake())
        wake.close()
        val lease = withTimeout(1000) { feature.await() }
        assertNull(GlassesAudioGate.tryAcquireWake())
        lease.close()
        requireNotNull(GlassesAudioGate.tryAcquireWake()).close()
    }

    @Test fun cancelledWaiterDoesNotLeaveWakePermanentlyPaused() = runBlocking {
        val wake = requireNotNull(GlassesAudioGate.tryAcquireWake())
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { GlassesAudioGate.acquireFeature() }
        waiter.cancelAndJoin()
        assertEquals(0, GlassesAudioGate.featureRequests.value)
        wake.close()
        requireNotNull(GlassesAudioGate.tryAcquireWake()).close()
    }

    @Test fun featuresAreSerializedAndCloseIsIdempotent() = runBlocking {
        val first = GlassesAudioGate.acquireFeature()
        val second = async(start = CoroutineStart.UNDISPATCHED) { GlassesAudioGate.acquireFeature() }
        assertEquals(2, GlassesAudioGate.featureRequests.value)
        first.close()
        first.close()
        val lease = withTimeout(1000) { second.await() }
        assertEquals(1, GlassesAudioGate.featureRequests.value)
        assertNull(GlassesAudioGate.tryAcquireWake())
        lease.close()
        assertEquals(0, GlassesAudioGate.featureRequests.value)
    }
}
