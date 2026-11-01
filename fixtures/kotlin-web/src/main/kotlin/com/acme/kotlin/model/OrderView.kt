package com.acme.kotlin.model

data class OrderView(val id: Long) {

    enum class Status {
        NEW, PAID
    }
}
