package com.credisafe.mobile.service
import com.credisafe.mobile.domain.LiveTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
object TripSession{private val _state=MutableStateFlow(LiveTelemetry());val state=_state.asStateFlow();fun set(v:LiveTelemetry){_state.value=v};fun update(f:(LiveTelemetry)->LiveTelemetry){_state.value=f(_state.value)}}
