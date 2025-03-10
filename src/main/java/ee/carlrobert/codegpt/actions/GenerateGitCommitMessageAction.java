package ee.carlrobert.codegpt.actions;

import static com.intellij.openapi.ui.Messages.OK;
import static java.util.stream.Collectors.joining;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.vcs.commit.CommitWorkflowUi;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.EncodingManager;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.completions.CommitMessageCompletionParameters;
import ee.carlrobert.codegpt.completions.CompletionRequestService;
import ee.carlrobert.codegpt.settings.configuration.CommitMessageTemplate;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import ee.carlrobert.codegpt.util.CommitWorkflowChanges;
import ee.carlrobert.codegpt.util.GitUtil;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import ee.carlrobert.llm.completion.CompletionEventListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import okhttp3.sse.EventSource;
import org.jetbrains.annotations.NotNull;

public class GenerateGitCommitMessageAction extends AnAction {

  public static final int MAX_TOKEN_COUNT_WARNING = 4096;
  private final EncodingManager encodingManager;

  public GenerateGitCommitMessageAction() {
    super(
        CodeGPTBundle.get("action.generateCommitMessage.title"),
        CodeGPTBundle.get("action.generateCommitMessage.description"),
        Icons.DefaultSmall);
    encodingManager = EncodingManager.getInstance();
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    ApplicationManager.getApplication().invokeLater(() -> {
      var commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
      if (commitWorkflowUi == null) {
        event.getPresentation().setVisible(false);
        return;
      }

      var callAllowed = CompletionRequestService.isRequestAllowed();
      event.getPresentation().setEnabled(callAllowed
          && new CommitWorkflowChanges(commitWorkflowUi).isFilesSelected());
      event.getPresentation().setText(CodeGPTBundle.get(callAllowed
          ? "action.generateCommitMessage.title"
          : "action.generateCommitMessage.missingCredentials"));
    });
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    var project = event.getProject();
    if (project == null || project.getBasePath() == null) {
      return;
    }

    var gitDiff = getDiff(event, project);
    var tokenCount = encodingManager.countTokens(gitDiff);
    if (tokenCount > MAX_TOKEN_COUNT_WARNING
        && OverlayUtil.showTokenSoftLimitWarningDialog(tokenCount) != OK) {
      return;
    }

    var commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
    if (commitWorkflowUi != null) {
      CompletionRequestService.getInstance().getCommitMessageAsync(
          new CommitMessageCompletionParameters(
              gitDiff,
              project.getService(CommitMessageTemplate.class).getSystemPrompt()),
          getEventListener(project, commitWorkflowUi));
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  private String getDiff(AnActionEvent event, Project project) {
    var commitWorkflowUi = Optional.ofNullable(event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI))
        .orElseThrow(() -> new IllegalStateException("Could not retrieve commit workflow ui."));
    var changes = new CommitWorkflowChanges(commitWorkflowUi);
    var projectBasePath = project.getBasePath();

    try {
      return ApplicationManager.getApplication().executeOnPooledThread(() -> {
        try {
          var repository = GitUtil.getProjectRepository(project);
          if (repository == null) {
            return "";
          }

          var stagedGitDiff = String.join("\n", GitUtil.getStagedDiff(
              project,
              repository,
              changes.getIncludedVersionedFilePaths()));
          var unstagedGitDiff = String.join("\n", GitUtil.getUnstagedDiff(
              project,
              repository,
              changes.getIncludedUnversionedFilePaths()));
          var newFilesContent =
              getNewFilesDiff(projectBasePath, changes.getIncludedUnversionedFilePaths());
          return Map.of(
                  "Unstaged git diff", unstagedGitDiff,
                  "Staged git diff", stagedGitDiff,
                  "New files", newFilesContent)
              .entrySet().stream()
              .filter(entry -> !entry.getValue().isEmpty())
              .map(entry -> "%s:%n%s".formatted(entry.getKey(), entry.getValue()))
              .collect(joining("\n\n"));
        } catch (VcsException e) {
          throw new RuntimeException("Unable to get staged diff", e);
        }
      }).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private static String getNewFilesDiff(String projectPath, List<String> filePaths) {
    return filePaths.stream()
        .map(pathString -> {
          var filePath = Path.of(pathString);
          var relativePath = Path.of(projectPath).relativize(filePath);
          try {
            return "New file '" + relativePath + "' content:\n" + Files.readString(filePath);
          } catch (IOException ignored) {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .collect(joining("\n"));
  }

  private CompletionEventListener<String> getEventListener(
      Project project,
      CommitWorkflowUi commitWorkflowUi) {
    return new CompletionEventListener<>() {
      private final StringBuilder messageBuilder = new StringBuilder();

      @Override
      public void onMessage(String message, EventSource eventSource) {
        messageBuilder.append(message);
        updateCommitMessage(messageBuilder.toString());
      }

      @Override
      public void onComplete(StringBuilder result) {
        if (messageBuilder.isEmpty()) {
          updateCommitMessage(result.toString());
        }
      }

      private void updateCommitMessage(String message) {
        ApplicationManager.getApplication().invokeLater(() ->
            WriteCommandAction.runWriteCommandAction(project, () ->
                commitWorkflowUi.getCommitMessageUi().setText(message)
            )
        );
      }

      @Override
      public void onError(ErrorDetails error, Throwable ex) {
        Notifications.Bus.notify(new Notification(
            "CodeGPT Notification Group",
            "CodeGPT",
            error.getMessage(),
            NotificationType.ERROR));
      }
    };
  }
}
