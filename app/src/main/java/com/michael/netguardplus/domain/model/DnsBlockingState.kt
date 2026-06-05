package com.michael.netguardplus.domain.model

sealed interface DnsBlockingState {
    data object Idle : DnsBlockingState
    data object Running : DnsBlockingState
    data class Error(val message: String) : DnsBlockingState
}
