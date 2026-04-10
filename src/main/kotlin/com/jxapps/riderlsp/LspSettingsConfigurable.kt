// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.options.Configurable
import javax.swing.*

class LspSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enabledCheckbox: JCheckBox? = null
    private var portField: JTextField? = null
    private var bindField: JTextField? = null

    override fun getDisplayName(): String = "Rider LSP Server"

    override fun createComponent(): JComponent {
        val settings = LspSettings.getInstance()

        enabledCheckbox = JCheckBox("Enable LSP Server", settings.enabled)
        portField = JTextField(settings.port.toString(), 6)
        bindField = JTextField(settings.bindAddress, 15)

        panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            add(enabledCheckbox)
            add(Box.createVerticalStrut(10))

            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("Port: "))
                add(portField)
                add(Box.createHorizontalGlue())
            })
            add(Box.createVerticalStrut(5))

            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("Bind Address: "))
                add(bindField)
                add(Box.createHorizontalGlue())
            })
            add(Box.createVerticalStrut(10))

            add(JLabel("<html><i>Changes take effect after restarting the IDE.</i></html>"))
            add(Box.createVerticalGlue())
        }

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = LspSettings.getInstance()
        return enabledCheckbox?.isSelected != settings.enabled ||
            portField?.text?.toIntOrNull() != settings.port ||
            bindField?.text != settings.bindAddress
    }

    override fun apply() {
        val settings = LspSettings.getInstance()
        settings.enabled = enabledCheckbox?.isSelected ?: true
        settings.port = portField?.text?.toIntOrNull() ?: 9999
        settings.bindAddress = bindField?.text ?: "127.0.0.1"
    }

    override fun reset() {
        val settings = LspSettings.getInstance()
        enabledCheckbox?.isSelected = settings.enabled
        portField?.text = settings.port.toString()
        bindField?.text = settings.bindAddress
    }

    override fun disposeUIResources() {
        panel = null
        enabledCheckbox = null
        portField = null
        bindField = null
    }
}
