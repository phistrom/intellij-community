// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.commit

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.SingletonNotificationManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.progress.withBackgroundProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbService.DumbModeListener
import com.intellij.openapi.project.DumbService.isDumb
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.StringUtil.capitalize
import com.intellij.openapi.util.text.StringUtil.toLowerCase
import com.intellij.openapi.vcs.*
import com.intellij.openapi.vcs.VcsBundle.message
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.CommitExecutor
import com.intellij.openapi.vcs.changes.actions.DefaultCommitExecutorAction
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.LOCAL_CHANGES
import com.intellij.openapi.vcs.checkin.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.containers.nullize
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.commit.AbstractCommitWorkflow.Companion.getCommitExecutors
import kotlinx.coroutines.*
import org.jetbrains.annotations.Nls
import java.lang.Runnable
import kotlin.properties.Delegates.observable

private val LOG = logger<NonModalCommitWorkflowHandler<*, *>>()

abstract class NonModalCommitWorkflowHandler<W : NonModalCommitWorkflow, U : NonModalCommitWorkflowUi> :
  AbstractCommitWorkflowHandler<W, U>() {

  abstract override val amendCommitHandler: NonModalAmendCommitHandler

  private var areCommitOptionsCreated = false

  private val coroutineScope =
    CoroutineScope(CoroutineName("commit workflow") + Dispatchers.EDT + SupervisorJob())

  private var isCommitChecksResultUpToDate: RecentCommitChecks by observable(RecentCommitChecks.UNKNOWN) { _, oldValue, newValue ->
    if (oldValue == newValue) return@observable
    updateDefaultCommitActionName()
  }

  private val checkinErrorNotifications = SingletonNotificationManager(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION.displayId,
                                                                       NotificationType.ERROR)

  private var pendingPostCommitChecks: PendingPostCommitChecks? = null

  protected fun setupCommitHandlersTracking() {
    CheckinHandlerFactory.EP_NAME.addChangeListener(Runnable { commitHandlersChanged() }, this)
    VcsCheckinHandlerFactory.EP_NAME.addChangeListener(Runnable { commitHandlersChanged() }, this)
  }

  private fun commitHandlersChanged() {
    if (workflow.isExecuting) return

    commitOptions.saveState()
    disposeCommitOptions()

    initCommitHandlers()
  }

  override fun vcsesChanged() {
    initCommitHandlers()
    workflow.initCommitExecutors(getCommitExecutors(project, workflow.vcses) + RunCommitChecksExecutor)

    updateDefaultCommitActionEnabled()
    updateDefaultCommitActionName()
    ui.setPrimaryCommitActions(createPrimaryCommitActions())
    ui.setCustomCommitActions(createCommitExecutorActions())
  }

  protected fun setupDumbModeTracking() {
    if (isDumb(project)) ui.commitProgressUi.isDumbMode = true
    project.messageBus.connect(this).subscribe(DumbService.DUMB_MODE, object : DumbModeListener {
      override fun enteredDumbMode() {
        ui.commitProgressUi.isDumbMode = true
      }

      override fun exitDumbMode() {
        ui.commitProgressUi.isDumbMode = false
      }
    })
  }

  override fun executionStarted() = updateDefaultCommitActionEnabled()
  override fun executionEnded() = updateDefaultCommitActionEnabled()

  override fun updateDefaultCommitActionName() {
    val isAmend = amendCommitHandler.isAmendCommitMode
    val isSkipCommitChecks = willSkipCommitChecks()
    ui.defaultCommitActionName = getDefaultCommitActionName(workflow.vcses, isAmend, isSkipCommitChecks)
  }

  private fun getCommitActionTextForNotification(
    executor: CommitExecutor?,
    isSkipCommitChecks: Boolean
  ): @Nls(capitalization = Nls.Capitalization.Sentence) String {
    val isAmend = amendCommitHandler.isAmendCommitMode
    val actionText: @Nls String = getActionTextWithoutEllipsis(workflow.vcses, executor, isAmend, isSkipCommitChecks)
    return capitalize(toLowerCase(actionText))
  }

  fun updateDefaultCommitActionEnabled() {
    ui.isDefaultCommitActionEnabled = isReady()
  }

  protected open fun isReady() = workflow.vcses.isNotEmpty() && !workflow.isExecuting && !amendCommitHandler.isLoading

  override fun isExecutorEnabled(executor: CommitExecutor): Boolean = super.isExecutorEnabled(executor) && isReady()

  private fun createPrimaryCommitActions(): List<AnAction> {
    val group = ActionManager.getInstance().getAction(VcsActions.PRIMARY_COMMIT_EXECUTORS_GROUP) as ActionGroup
    return group.getChildren(null).toList()
  }

  private fun createCommitExecutorActions(): List<AnAction> {
    val group = ActionManager.getInstance().getAction(VcsActions.COMMIT_EXECUTORS_GROUP) as ActionGroup
    val executors = workflow.commitExecutors.filter { it.useDefaultAction() }
    return group.getChildren(null).toList() +
           executors.map { DefaultCommitExecutorAction(it) }
  }

  override fun checkCommit(sessionInfo: CommitSessionInfo): Boolean {
    val superCheckResult = super.checkCommit(sessionInfo)
    val executorWithoutChangesAllowed = sessionInfo.executor?.areChangesRequired() == false
    ui.commitProgressUi.isEmptyChanges = !amendCommitHandler.isAmendWithoutChangesAllowed() && !executorWithoutChangesAllowed && isCommitEmpty()
    ui.commitProgressUi.isEmptyMessage = getCommitMessage().isBlank()
    return superCheckResult &&
           !ui.commitProgressUi.isEmptyChanges &&
           !ui.commitProgressUi.isEmptyMessage
  }

  /**
   * Subscribe to VFS and documents changes to reset commit checks results
   */
  protected fun setupCommitChecksResultTracking() {
    fun areFilesAffectsCommitChecksResult(files: Collection<VirtualFile>): Boolean {
      val vcsManager = ProjectLevelVcsManager.getInstance(project)
      val filesFromVcs = files.filter { vcsManager.getVcsFor(it) != null }.nullize() ?: return false

      val changeListManager = ChangeListManager.getInstance(project)
      val fileIndex = ProjectRootManagerEx.getInstanceEx(project).fileIndex
      return filesFromVcs.any {
        fileIndex.isInContent(it) && changeListManager.getStatus(it) != FileStatus.IGNORED
      }
    }

    // reset commit checks on VFS updates
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: MutableList<out VFileEvent>) {
        if (isCommitChecksResultUpToDate == RecentCommitChecks.UNKNOWN) {
          return
        }
        val updatedFiles = events.mapNotNull { it.file }
        if (areFilesAffectsCommitChecksResult(updatedFiles)) {
          resetCommitChecksResult()
        }
      }
    })

    // reset commit checks on documents modification (e.g. user typed in the editor)
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        if (isCommitChecksResultUpToDate == RecentCommitChecks.UNKNOWN) {
          return
        }
        val file = FileDocumentManager.getInstance().getFile(event.document)
        if (file != null && areFilesAffectsCommitChecksResult(listOf(file))) {
          resetCommitChecksResult()
        }
      }
    }, this)
  }

  private fun willSkipCommitChecks() = willSkipEarlyCommitChecks() || willSkipModificationCommitChecks()

  private fun willSkipEarlyCommitChecks() = isCommitChecksResultUpToDate == RecentCommitChecks.EARLY_FAILED ||
                                            isCommitChecksResultUpToDate == RecentCommitChecks.MODIFICATIONS_FAILED

  private fun willSkipModificationCommitChecks() = isCommitChecksResultUpToDate == RecentCommitChecks.MODIFICATIONS_FAILED


  protected fun resetCommitChecksResult() {
    isCommitChecksResultUpToDate = RecentCommitChecks.UNKNOWN
    hideCommitChecksFailureNotification()
  }

  override fun beforeCommitChecksStarted(sessionInfo: CommitSessionInfo) {
    super.beforeCommitChecksStarted(sessionInfo)
    hideCommitChecksFailureNotification()
  }

  override fun beforeCommitChecksEnded(sessionInfo: CommitSessionInfo, result: CommitChecksResult) {
    hideCommitChecksFailureNotification()
    super.beforeCommitChecksEnded(sessionInfo, result)
    if (result.shouldCommit) {
      ui.commitProgressUi.clearCommitCheckFailures()
    }

    if (result is CommitChecksResult.Failed ||
        result is CommitChecksResult.ExecutionError) {
      val executor = sessionInfo.executor
      val failures = ui.commitProgressUi.getCommitCheckFailures()
      val commitActionText = getCommitActionTextForNotification(executor, false)
      val commitAnywayActionText = getCommitActionTextForNotification(executor, true)
      val title = message("commit.checks.failed.notification.title", commitActionText)
      val description = getCommitCheckFailureDescription(failures)
      checkinErrorNotifications.notify(title, description, project) {
        it.setDisplayId(VcsNotificationIdsHolder.COMMIT_CHECKS_FAILED)
        it.addAction(
          NotificationAction.createExpiring(commitAnywayActionText) { _, _ ->
            if (!workflow.isExecuting) {
              executorCalled(executor)
            }
          })
        appendShowDetailsNotificationActions(it, failures)
      }
    }

    if (result is CommitChecksResult.OnlyChecks && !result.checksPassed) {
      val failures = ui.commitProgressUi.getCommitCheckFailures()
      val commitActionText = getCommitActionTextForNotification(null, false)
      val title = message("commit.checks.failed.notification.title", commitActionText)
      val description = getCommitCheckFailureDescription(failures)
      checkinErrorNotifications.notify(title, description, project) {
        it.setDisplayId(VcsNotificationIdsHolder.COMMIT_CHECKS_ONLY_FAILED)
        appendShowDetailsNotificationActions(it, failures)
      }
    }
  }

  private fun getCommitCheckFailureDescription(failures: List<CommitCheckFailure>): @NlsContexts.NotificationContent String {
    return failures.filterIsInstance<CommitCheckFailure.WithDescription>().joinToString("<br>") { it.text }
  }

  private fun appendShowDetailsNotificationActions(notification: Notification, failures: List<CommitCheckFailure>) {
    for (failure in failures.filterIsInstance<CommitCheckFailure.WithDetails>()) {
      notification.addAction(NotificationAction.create(failure.viewDetailsActionText) { _, _ -> failure.viewDetails() })
    }

    val hasGenericFailure = failures.any { it !is CommitCheckFailure.WithDetails }
    if (hasGenericFailure) {
      notification.addAction(NotificationAction.create(message("commit.checks.failed.notification.show.details.action")) { _, _ ->
        showCommitCheckFailuresPanel()
      })
    }
  }

  private fun showCommitCheckFailuresPanel() {
    val toolWindow = ChangesViewContentManager.getToolWindowFor(project, LOCAL_CHANGES)
    toolWindow?.activate {
      ChangesViewContentManager.getInstance(project).selectContent(LOCAL_CHANGES)
    }
  }

  override fun doExecuteSession(sessionInfo: CommitSessionInfo, commitInfo: DynamicCommitInfo): Boolean {
    if (!sessionInfo.isVcsCommit) {
      return workflow.executeSession(sessionInfo, commitInfo)
    }

    workflow.asyncSession(coroutineScope, sessionInfo) {
      pendingPostCommitChecks = null

      val isOnlyRunCommitChecks = commitContext.isOnlyRunCommitChecks
      commitContext.isOnlyRunCommitChecks = false

      val skipEarlyCommitChecks = !isOnlyRunCommitChecks && willSkipEarlyCommitChecks()
      val skipModificationCommitChecks = !isOnlyRunCommitChecks && willSkipModificationCommitChecks()
      resetCommitChecksResult()

      ui.commitProgressUi.runWithProgress(isOnlyRunCommitChecks) {
        val failure = runNonModalBeforeCommitChecks(commitInfo, skipEarlyCommitChecks, skipModificationCommitChecks)
        handleCommitProblem(failure, isOnlyRunCommitChecks)
      }
    }

    return true
  }

  private suspend fun runNonModalBeforeCommitChecks(commitInfo: DynamicCommitInfo,
                                                    skipEarlyCommitChecks: Boolean,
                                                    skipModificationCommitChecks: Boolean): NonModalCommitChecksFailure? {
    try {
      val handlers = workflow.commitHandlers
      val commitChecks = handlers
        .map { it.asCommitCheck(commitInfo) }
        .filter { it.isEnabled() }
        .groupBy { it.getExecutionOrder() }

      val earlyChecks = commitChecks[CommitCheck.ExecutionOrder.EARLY].orEmpty()
      val modificationChecks = commitChecks[CommitCheck.ExecutionOrder.MODIFICATION].orEmpty()
      val lateChecks = commitChecks[CommitCheck.ExecutionOrder.LATE].orEmpty()
      val postCommitChecks = commitChecks[CommitCheck.ExecutionOrder.POST_COMMIT].orEmpty()
      @Suppress("DEPRECATION") val metaHandlers = handlers.filterIsInstance<CheckinMetaHandler>()

      if (!skipEarlyCommitChecks) {
        runEarlyCommitChecks(commitInfo, earlyChecks)?.let { return it }
      }

      if (!skipModificationCommitChecks) {
        runModificationCommitChecks(commitInfo, modificationChecks, metaHandlers)?.let { return it }
      }

      runLateCommitChecks(commitInfo, lateChecks)?.let { return it }

      if (postCommitChecks.isNotEmpty()) {
        if (Registry.`is`("vcs.non.modal.post.commit.checks") &&
            commitInfo.executor?.requiresSyncCommitChecks() != true) {
          pendingPostCommitChecks = PendingPostCommitChecks(commitInfo.asStaticInfo(), postCommitChecks)
        }
        else {
          runSyncPostCommitChecks(commitInfo, postCommitChecks)?.let { return it }
        }
      }

      return null // checks passed
    }
    catch (ce: CancellationException) {
      // Do not report error on cancellation
      throw ce
    }
    catch (e: Throwable) {
      LOG.warn(Throwable(e))
      reportCommitCheckFailure(CommitProblem.createError(e))
      return NonModalCommitChecksFailure.ERROR
    }
  }

  private suspend fun runEarlyCommitChecks(commitInfo: DynamicCommitInfo, commitChecks: List<CommitCheck>): NonModalCommitChecksFailure? {
    val problems = mutableListOf<CommitProblem>()
    for (commitCheck in commitChecks) {
      problems += AbstractCommitWorkflow.runCommitCheck(project, commitCheck, commitInfo) ?: continue
    }
    if (problems.isEmpty()) return null

    problems.forEach { reportCommitCheckFailure(it) }
    return NonModalCommitChecksFailure.EARLY_FAILED
  }

  private suspend fun runModificationCommitChecks(commitInfo: DynamicCommitInfo,
                                                  commitChecks: List<CommitCheck>,
                                                  @Suppress("DEPRECATION")
                                                  metaHandlers: List<CheckinMetaHandler>): NonModalCommitChecksFailure? {
    if (metaHandlers.isEmpty() && commitChecks.isEmpty()) return null

    return workflow.runModificationCommitChecks underChangelist@{
      AbstractCommitWorkflow.runMetaHandlers(metaHandlers)

      for (commitCheck in commitChecks) {
        val problem = AbstractCommitWorkflow.runCommitCheck(project, commitCheck, commitInfo) ?: continue
        reportCommitCheckFailure(problem)
        return@underChangelist NonModalCommitChecksFailure.MODIFICATIONS_FAILED
      }

      FileDocumentManager.getInstance().saveAllDocuments()
      return@underChangelist null
    }
  }

  private suspend fun runLateCommitChecks(commitInfo: DynamicCommitInfo, commitChecks: List<CommitCheck>): NonModalCommitChecksFailure? {
    for (commitCheck in commitChecks) {
      val problem = AbstractCommitWorkflow.runCommitCheck(project, commitCheck, commitInfo) ?: continue

      val solution = problem.showModalSolution(project, commitInfo)
      if (solution == CheckinHandler.ReturnResult.COMMIT) continue

      reportCommitCheckFailure(problem)
      return NonModalCommitChecksFailure.ABORTED
    }
    return null
  }

  private suspend fun runSyncPostCommitChecks(commitInfo: DynamicCommitInfo,
                                              commitChecks: List<CommitCheck>): NonModalCommitChecksFailure? {
    val problems = mutableListOf<CommitProblem>()
    for (commitCheck in commitChecks) {
      problems += AbstractCommitWorkflow.runCommitCheck(project, commitCheck, commitInfo) ?: continue
    }
    if (problems.isEmpty()) return null

    val onlyDetailedProblem = problems.singleOrNull() as? CommitProblemWithDetails
    if (onlyDetailedProblem != null) {
      val commit = MessageDialogBuilder.yesNo(message("checkin.commit.checks.failed"),
                                              problems.joinToString("<br>") { it.text })
        .icon(UIUtil.getErrorIcon())
        .yesText(commitInfo.commitActionText)
        .noText(StringUtil.toTitleCase(onlyDetailedProblem.showDetailsAction))
        .ask(project)
      if (commit) {
        return null
      }
      else {
        onlyDetailedProblem.showDetails(project)
        return NonModalCommitChecksFailure.ABORTED
      }
    }
    else {
      val commit = MessageDialogBuilder.yesNo(message("checkin.commit.checks.failed"),
                                              problems.joinToString("<br>") { it.text })
        .icon(UIUtil.getErrorIcon())
        .yesText(commitInfo.commitActionText)
        .noText(StringUtil.toTitleCase(message("commit.checks.failed.notification.show.details.action")))
        .ask(project)
      if (commit) {
        showCommitCheckFailuresPanel()
        return null
      }
      else {
        return NonModalCommitChecksFailure.ABORTED
      }
    }
  }

  private fun runPostCommitChecksTask(commitInfo: StaticCommitInfo, commitChecks: List<CommitCheck>) {
    val scope = CoroutineScope(CoroutineName("post commit checks") + Dispatchers.IO)
    scope.launch {
      withBackgroundProgressIndicator(project, message("post.commit.checks.progress.text")) {
        val postCommitInfo = createPostCommitInfo(commitInfo)
        withContext(Dispatchers.EDT) {
          val problems = runAsyncPostCommitChecks(commitChecks, postCommitInfo)
          if (problems.isEmpty()) return@withContext

          reportPostCommitChecksFailure(problems)
        }
      }
    }
  }

  private suspend fun runAsyncPostCommitChecks(commitChecks: List<CommitCheck>,
                                               postCommitInfo: StaticCommitInfo): MutableList<CommitProblem> {
    val problems = mutableListOf<CommitProblem>()

    if (isDumb(project)) {
      if (commitChecks.any { !DumbService.isDumbAware(it) }) {
        problems += TextCommitProblem(message("before.checkin.post.commit.error.dumb.mode"))
      }
    }

    for (commitCheck in commitChecks) {
      problems += AbstractCommitWorkflow.runCommitCheck(project, commitCheck, postCommitInfo) ?: continue
    }
    return problems
  }

  private fun createPostCommitInfo(commitInfo: StaticCommitInfo): StaticCommitInfo {
    val changeConverters = commitInfo.affectedVcses.mapNotNull { it.checkinEnvironment?.postCommitChangeConverter }
    if (changeConverters.isEmpty()) LOG.warn("Post-commit change converters not found for ${commitInfo.affectedVcses}")

    val commitContext = commitInfo.commitContext
    commitContext.isPostCommitCheck = true

    var staticChanges = commitInfo.committedChanges
    for (changeConverter in changeConverters) {
      staticChanges = changeConverter.convertChangesAfterCommit(staticChanges, commitContext)
    }

    return StaticCommitInfo(commitContext, commitInfo.executor, commitInfo.commitActionText,
                            staticChanges, commitInfo.affectedVcses, commitInfo.commitMessage)
  }

  private fun reportCommitCheckFailure(problem: CommitProblem) {
    val checkFailure = when (problem) {
      is UnknownCommitProblem -> CommitCheckFailure.Unknown
      is CommitProblemWithDetails -> CommitCheckFailure.WithDetails(problem.text, problem.showDetailsAction) {
        problem.showDetails(project)
      }
      else -> CommitCheckFailure.WithDescription(problem.text)
    }
    ui.commitProgressUi.addCommitCheckFailure(checkFailure)
  }

  private fun reportPostCommitChecksFailure(problems: List<CommitProblem>) {
    val notification = VcsNotifier.IMPORTANT_ERROR_NOTIFICATION
      .createNotification(message("post.commit.checks.failed.notification.title"),
                          problems.joinToString("<br>") { it.text },
                          NotificationType.ERROR)
      .setDisplayId(VcsNotificationIdsHolder.POST_COMMIT_CHECKS_FAILED)

    for (problem in problems.filterIsInstance<CommitProblemWithDetails>()) {
      notification.addAction(NotificationAction.createSimple(problem.showDetailsAction) {
        problem.showDetails(project)
      })
    }

    notification.notify(project)
  }

  private fun handleCommitProblem(failure: NonModalCommitChecksFailure?, isOnlyRunCommitChecks: Boolean): CommitChecksResult {
    val checksPassed = failure == null
    val aborted = failure == NonModalCommitChecksFailure.ABORTED

    if (checksPassed) {
      if (isOnlyRunCommitChecks) {
        isCommitChecksResultUpToDate = RecentCommitChecks.PASSED
      }
      else {
        isCommitChecksResultUpToDate = RecentCommitChecks.UNKNOWN // We are going to commit, remembering the result is not needed.
      }
    }
    else if (failure == NonModalCommitChecksFailure.EARLY_FAILED) {
      isCommitChecksResultUpToDate = RecentCommitChecks.EARLY_FAILED
    }
    else if (failure == NonModalCommitChecksFailure.MODIFICATIONS_FAILED) {
      isCommitChecksResultUpToDate = RecentCommitChecks.MODIFICATIONS_FAILED
    }
    else {
      isCommitChecksResultUpToDate = RecentCommitChecks.FAILED
    }

    if (aborted) {
      return CommitChecksResult.Cancelled
    }
    else if (isOnlyRunCommitChecks) {
      return CommitChecksResult.OnlyChecks(checksPassed)
    }
    else if (checksPassed) {
      return CommitChecksResult.Passed
    }
    else {
      return CommitChecksResult.Failed()
    }
  }

  override fun dispose() {
    hideCommitChecksFailureNotification()
    coroutineScope.cancel()
    super.dispose()
  }

  fun hideCommitChecksFailureNotification() {
    checkinErrorNotifications.clear()
  }

  fun showCommitOptions(isFromToolbar: Boolean, dataContext: DataContext) =
    ui.showCommitOptions(ensureCommitOptions(), getDefaultCommitActionName(workflow.vcses), isFromToolbar, dataContext)

  override fun saveCommitOptionsOnCommit(): Boolean {
    ensureCommitOptions()
    // restore state in case settings were changed via configurable
    commitOptions.allOptions
      .filter { it is UnnamedConfigurable }
      .forEach { it.restoreState() }
    return super.saveCommitOptionsOnCommit()
  }

  private fun ensureCommitOptions(): CommitOptions {
    if (!areCommitOptionsCreated) {
      areCommitOptionsCreated = true

      workflow.initCommitOptions(createCommitOptions())
      commitOptions.restoreState()

      commitOptionsCreated()
    }
    return commitOptions
  }

  protected open fun commitOptionsCreated() = Unit

  protected fun disposeCommitOptions() {
    workflow.disposeCommitOptions()
    areCommitOptionsCreated = false
  }

  override fun getState(): CommitWorkflowHandlerState {
    val isAmend = amendCommitHandler.isAmendCommitMode
    val isSkipCommitChecks = willSkipCommitChecks()
    return CommitWorkflowHandlerState(isAmend, isSkipCommitChecks)
  }

  protected open inner class CommitStateCleaner : CommitterResultHandler {
    override fun onSuccess() = resetState()
    override fun onCancel() = Unit
    override fun onFailure() = resetState()

    protected open fun resetState() {
      disposeCommitOptions()

      workflow.clearCommitContext()
      initCommitHandlers()

      resetCommitChecksResult()
      updateDefaultCommitActionName()
    }
  }

  protected inner class PostCommitChecksRunner : CommitterResultHandler {
    override fun onSuccess() {
      pendingPostCommitChecks?.let { runPostCommitChecksTask(it.commitInfo, it.commitChecks) }
      pendingPostCommitChecks = null
    }

    override fun onCancel() {
      pendingPostCommitChecks = null
    }

    override fun onFailure() {
      pendingPostCommitChecks = null
    }
  }
}

private class PendingPostCommitChecks(val commitInfo: StaticCommitInfo, val commitChecks: List<CommitCheck>)

private enum class NonModalCommitChecksFailure { EARLY_FAILED, MODIFICATIONS_FAILED, ABORTED, ERROR }

private enum class RecentCommitChecks { UNKNOWN, PASSED, EARLY_FAILED, MODIFICATIONS_FAILED, FAILED }
