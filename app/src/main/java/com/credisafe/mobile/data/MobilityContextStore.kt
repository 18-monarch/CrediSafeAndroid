package com.credisafe.mobile.data

import com.credisafe.mobile.domain.MobilitySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MobilityContextStore {
    private val _state = MutableStateFlow(MobilitySnapshot())
    val state: StateFlow<MobilitySnapshot> = _state

    fun set(snapshot: MobilitySnapshot) {
        _state.value = snapshot
    }

    fun reset() {
        _state.value = MobilitySnapshot()
    }
}
