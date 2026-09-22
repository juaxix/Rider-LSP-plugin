// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

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

            add(JLabel("<html><i>Changes are applied to all open projects immediately.</i></html>"))
            add(Box.createVerticalGlue())
        }

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = LspSettings.getInstance()
        return enabledCheckbox?.isSelected != settings.enabled ||
            portField?.text?.trim()?.toIntOrNull() != settings.port ||
            bindField?.text?.trim() != settings.bindAddress
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val portText = portField?.text?.trim() ?: "9999"
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            throw ConfigurationException("Invalid port '$portText'. Port must be a number between 1 and 65535.")
        }

        val bindAddress = bindField?.text?.trim() ?: "127.0.0.1"
        if (bindAddress.isBlank()) {
            throw ConfigurationException("Bind address cannot be empty.")
        }
        try {
            java.net.InetAddress.getByName(bindAddress)
        } catch (e: Exception) {
            throw ConfigurationException("Invalid bind address '$bindAddress'. Must be a valid IP address or hostname.")
        }

        val settings = LspSettings.getInstance()
        val enabled = enabledCheckbox?.isSelected ?: true
        settings.enabled = enabled
        settings.port = port
        settings.bindAddress = bindAddress

        // Apply to running servers without an IDE restart.
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val manager = LspServerManager.getInstance(project)
            if (enabled) manager.restart() else manager.stop()
        }
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
