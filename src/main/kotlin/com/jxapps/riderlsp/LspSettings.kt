// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "com.jxapps.riderlsp.LspSettings",
    storages = [Storage("RiderLspSettings.xml")]
)
class LspSettings : PersistentStateComponent<LspSettings.State> {

    data class State(
        var port: Int = 9999,
        var enabled: Boolean = true,
        var bindAddress: String = "127.0.0.1"
    )

    private var myState = State()

    var port: Int
        get() = myState.port
        set(value) { myState.port = value }

    var enabled: Boolean
        get() = myState.enabled
        set(value) { myState.enabled = value }

    var bindAddress: String
        get() = myState.bindAddress
        set(value) { myState.bindAddress = value }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): LspSettings =
            ApplicationManager.getApplication().getService(LspSettings::class.java)
    }
}
