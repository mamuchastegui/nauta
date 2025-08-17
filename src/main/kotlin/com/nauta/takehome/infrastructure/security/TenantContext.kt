package com.nauta.takehome.infrastructure.security

import org.springframework.stereotype.Component

@Component
class TenantContext {
    private val tenantHolder = ThreadLocal<String>()

    fun setCurrentTenantId(tenantId: String) {
        tenantHolder.set(tenantId)
    }

    fun getCurrentTenantId(): String? {
        return tenantHolder.get()
    }

    fun clear() {
        tenantHolder.remove()
    }
}
