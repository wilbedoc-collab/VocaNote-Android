package com.re2o.recorder

/** Pure policy for reconciling Activity memory with service-owned durable state. */
object RecordingUiReconciliationPolicy {
    enum class Render { KEEP, IDLE, RECORDING, STOPPING, FINALIZING }

    data class Decision(
        val isRecording: Boolean,
        val isStopping: Boolean,
        val runTicker: Boolean,
        val render: Render
    )

    fun reconcile(
        uiIsRecording: Boolean,
        uiIsStopping: Boolean,
        durableIsRecording: Boolean,
        durableStopPending: Boolean,
        isHomeScreen: Boolean
    ): Decision {
        val isStopping = durableStopPending || (durableIsRecording && uiIsStopping)
        val render = when {
            !isHomeScreen -> Render.KEEP
            durableStopPending && !durableIsRecording -> Render.FINALIZING
            isStopping -> Render.STOPPING
            durableIsRecording -> Render.RECORDING
            uiIsRecording || uiIsStopping -> Render.IDLE
            else -> Render.KEEP
        }
        return Decision(
            isRecording = durableIsRecording,
            isStopping = isStopping,
            runTicker = durableIsRecording && !isStopping,
            render = render
        )
    }
}
