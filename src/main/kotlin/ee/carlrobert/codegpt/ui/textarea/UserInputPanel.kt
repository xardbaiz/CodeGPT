package ee.carlrobert.codegpt.ui.textarea

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.Project
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.actions.AttachImageAction
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.ModelComboBoxAction
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.IconActionButton
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.GitCommitActionItem
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel
import git4idea.GitCommit
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

class UserInputPanel(
    private val project: Project,
    private val totalTokensPanel: TotalTokensPanel,
    private val onSubmit: (String, List<AppliedActionInlay>?) -> Unit,
    private val onStop: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private const val CORNER_RADIUS = 16
    }

    val text: String
        get() = promptTextField.text

    private val promptTextField = PromptTextField(project, ::updateUserTokens, ::handleSubmit)
    private val submitButton = IconActionButton(
        object : AnAction(
            CodeGPTBundle.get("smartTextPane.submitButton.title"),
            CodeGPTBundle.get("smartTextPane.submitButton.description"),
            Icons.Send
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                handleSubmit(promptTextField.text)
            }
        }
    )
    private val stopButton = IconActionButton(
        object : AnAction(
            CodeGPTBundle.get("smartTextPane.stopButton.title"),
            CodeGPTBundle.get("smartTextPane.stopButton.description"),
            AllIcons.Actions.Suspend
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                onStop()
            }
        }
    ).apply { isEnabled = false }
    private val imageActionSupported = AtomicBooleanProperty(isImageActionSupported())

    init {
        background = UIUtil.getTextFieldBackground()
        add(promptTextField, BorderLayout.CENTER)
        add(getFooter(), BorderLayout.SOUTH)
    }

    fun setSubmitEnabled(enabled: Boolean) {
        submitButton.isEnabled = enabled
        stopButton.isEnabled = !enabled
    }

    override fun requestFocus() {
        invokeLater {
            promptTextField.requestFocusInWindow()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val area = Area(Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
        val roundedRect = RoundRectangle2D.Float(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            CORNER_RADIUS.toFloat(),
            CORNER_RADIUS.toFloat()
        )
        area.intersect(Area(roundedRect))

        g2.clip = area

        g2.color = background
        g2.fill(area)

        super.paintComponent(g2)
        g2.dispose()
    }


    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBUI.CurrentTheme.ActionButton.focusedBorder()
        if (promptTextField.isFocusOwner) {
            g2.stroke = BasicStroke(1.5F)
        }
        g2.drawRoundRect(0, 0, width - 1, height - 1, CORNER_RADIUS, CORNER_RADIUS)
        g2.dispose()
    }

    override fun getInsets(): Insets = JBUI.insets(4)

    private fun handleSubmit(text: String, appliedInlays: List<AppliedActionInlay>? = emptyList()) {
        if (text.isNotEmpty()) {
            onSubmit(text, appliedInlays)
        }
    }

    private fun updateUserTokens(text: String) {
        totalTokensPanel.updateUserPromptTokens(text)
    }

    private fun getFooter(): JPanel {
        val attachImageLink = AnActionLink(CodeGPTBundle.get("shared.image"), AttachImageAction())
            .apply {
                icon = AllIcons.FileTypes.Image
                font = JBUI.Fonts.smallFont()
            }
        val modelComboBox = ModelComboBoxAction(
            project,
            {
                imageActionSupported.set(isImageActionSupported())
                // TODO: Implement a proper session management
                if (service<ConversationsState>().state?.currentConversation?.messages?.isNotEmpty() == true) {
                    service<ConversationService>().startConversation()
                    project.service<ChatToolWindowContentManager>().createNewTabPanel()
                }
            },
            service<GeneralSettings>().state.selectedService
        ).createCustomComponent(ActionPlaces.UNKNOWN)

        return panel {
            twoColumnsRow({
                cell(modelComboBox).gap(RightGap.SMALL)
                cell(attachImageLink).visibleIf(imageActionSupported)
            }, {
                panel {
                    row {
                        cell(submitButton).gap(RightGap.SMALL)
                        cell(stopButton)
                    }
                }.align(AlignX.RIGHT)
            })
        }.andTransparent()
    }

    private fun isImageActionSupported(): Boolean {
        return when (service<GeneralSettings>().state.selectedService) {
            ServiceType.CUSTOM_OPENAI,
            ServiceType.ANTHROPIC,
            ServiceType.AZURE,
            ServiceType.OLLAMA -> true

            ServiceType.CODEGPT -> {
                listOf(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gemini-pro-1.5",
                    "claude-3-opus",
                    "claude-3.5-sonnet"
                ).contains(
                    service<CodeGPTServiceSettings>()
                        .state
                        .chatCompletionSettings
                        .model
                )
            }

            ServiceType.OPENAI -> {
                listOf(
                    OpenAIChatCompletionModel.GPT_4_VISION_PREVIEW.code,
                    OpenAIChatCompletionModel.GPT_4_O.code,
                    OpenAIChatCompletionModel.GPT_4_O_MINI.code
                ).contains(service<OpenAISettings>().state.model)
            }

            else -> false
        }
    }

    fun addSelection(fileName: String, selectionModel: SelectionModel) {
        promptTextField.addInlayElement(
            "code",
            "$fileName (${selectionModel.selectionStartPosition?.line}:${selectionModel.selectionEndPosition?.line})",
            fileName = fileName,
            tooltipText = selectionModel.selectedText
        )
        promptTextField.requestFocusInWindow()
        selectionModel.removeSelection()
    }

    fun addCommitReferences(gitCommits: List<GitCommit>) {
        runInEdt {
            if (promptTextField.text.isEmpty()) {
                promptTextField.text = if (gitCommits.size == 1) {
                    "Explain the commit: "
                } else {
                    "Explain the commits: "
                }
            }

            gitCommits.forEach {
                promptTextField.addInlayElement(
                    "commit",
                    it.id.toShortString(),
                    GitCommitActionItem(project, it)
                )
            }
            promptTextField.requestFocusInWindow()
        }
    }
}
