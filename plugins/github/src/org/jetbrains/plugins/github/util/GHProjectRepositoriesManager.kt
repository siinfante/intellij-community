// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.util

import com.intellij.collaboration.async.CompletableFutureUtil.errorOnEdt
import com.intellij.collaboration.async.CompletableFutureUtil.successOnEdt
import com.intellij.collaboration.auth.AccountsListener
import com.intellij.collaboration.hosting.GitHostingUrlUtil
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.SingleAlarm
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import org.jetbrains.annotations.CalledInAny
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.pullrequest.GHPRStatisticsCollector
import kotlin.properties.Delegates.observable

@Service
class GHProjectRepositoriesManager(private val project: Project) : Disposable {
  private val updateAlarm = SingleAlarm(task = ::doUpdateRepositories, delay = 50, parentDisposable = this)

  private val accountManager: GHAccountManager
    get() = service()

  var knownRepositories by observable(emptySet<GHGitRepositoryMapping>()) { _, oldValue, newValue ->
    if (oldValue != newValue) {
      ApplicationManager.getApplication().messageBus.syncPublisher(LIST_CHANGES_TOPIC).repositoryListChanged(newValue, project)
    }
  }
    private set

  private val serversFromDiscovery = HashSet<GithubServerPath>()

  init {
    accountManager.addListener(this, object : AccountsListener<GithubAccount> {
      override fun onAccountListChanged(old: Collection<GithubAccount>, new: Collection<GithubAccount>) = runInEdt {
        updateRepositories()
      }
    })
    updateRepositories()
  }

  fun findKnownRepositories(repository: GitRepository): List<GHGitRepositoryMapping> {
    return knownRepositories.filter {
      it.gitRemoteUrlCoordinates.repository == repository
    }
  }

  @CalledInAny
  private fun updateRepositories() {
    if (ApplicationManager.getApplication().isUnitTestMode) {
      doUpdateRepositories()
    }
    else {
      updateAlarm.request()
    }
  }

  //TODO: execute on pooled thread - need to make GithubAccountManager ready
  @RequiresEdt
  private fun doUpdateRepositories() {
    LOG.debug("Repository list update started")
    val gitRepositories = project.service<GitRepositoryManager>().repositories
    if (gitRepositories.isEmpty()) {
      knownRepositories = emptySet()
      LOG.debug("No repositories found")
      return
    }

    val remotes = gitRepositories.flatMap { repo ->
      repo.remotes.flatMap { remote ->
        remote.urls.mapNotNull { url ->
          GitRemoteUrlCoordinates(url, remote, repo)
        }
      }
    }
    LOG.debug("Found remotes: $remotes")

    val authenticatedServers = accountManager.accounts.map { it.server }
    val servers = mutableListOf<GithubServerPath>().apply {
      add(GithubServerPath.DEFAULT_SERVER)
      addAll(authenticatedServers)
      addAll(serversFromDiscovery)
    }

    val repositories = HashSet<GHGitRepositoryMapping>()
    for (remote in remotes) {
      val repository = servers.find { it.matches(remote.url) }?.let { GHGitRepositoryMapping.create(it, remote) }
      if (repository != null) {
        repositories.add(repository)
      }
      else {
        scheduleEnterpriseServerDiscovery(remote)
      }
    }
    LOG.debug("New list of known repos: $repositories")
    knownRepositories = repositories

    for (server in authenticatedServers) {
      if (server.isGithubDotCom) {
        continue
      }
      service<GHEnterpriseServerMetadataLoader>().loadMetadata(server).successOnEdt {
        GHPRStatisticsCollector.logEnterpriseServerMeta(project, server, it)
      }
    }
  }

  @RequiresEdt
  private fun scheduleEnterpriseServerDiscovery(remote: GitRemoteUrlCoordinates) {
    val uri = GitHostingUrlUtil.getUriFromRemoteUrl(remote.url)
    LOG.debug("Extracted URI $uri from remote ${remote.url}")
    if (uri == null) return

    val host = uri.host ?: return
    val path = uri.path ?: return
    val pathParts = path.removePrefix("/").split('/').takeIf { it.size >= 2 } ?: return
    val serverSuffix = if (pathParts.size == 2) null else pathParts.subList(0, pathParts.size - 2).joinToString("/", "/")

    val server = GithubServerPath(false, host, null, serverSuffix)
    val serverHttp = GithubServerPath(true, host, null, serverSuffix)
    val server8080 = GithubServerPath(true, host, 8080, serverSuffix)
    LOG.debug("Scheduling GHE server discovery for $server, $serverHttp and $server8080")

    val serverManager = service<GHEnterpriseServerMetadataLoader>()
    serverManager.loadMetadata(server).successOnEdt {
      LOG.debug("Found GHE server at $server")
      serversFromDiscovery.add(server)
      invokeLater(runnable = ::doUpdateRepositories)
    }.errorOnEdt {
      serverManager.loadMetadata(serverHttp).successOnEdt {
        LOG.debug("Found GHE server at $serverHttp")
        serversFromDiscovery.add(serverHttp)
        invokeLater(runnable = ::doUpdateRepositories)
      }.errorOnEdt {
        serverManager.loadMetadata(server8080).successOnEdt {
          LOG.debug("Found GHE server at $server8080")
          serversFromDiscovery.add(server8080)
          invokeLater(runnable = ::doUpdateRepositories)
        }
      }
    }
  }

  fun addRepositoryListChangedListener(disposable: Disposable, listener: () -> Unit) {
    ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(LIST_CHANGES_TOPIC, object : ListChangeListener {
      override fun repositoryListChanged(newList: Set<GHGitRepositoryMapping>, project: Project) = listener()
    })
  }

  internal class RemoteUrlsListener(private val project: Project) : VcsRepositoryMappingListener, GitRepositoryChangeListener {
    override fun mappingChanged() = runInEdt(project) { updateRepositories(project) }
    override fun repositoryChanged(repository: GitRepository) = runInEdt(project) { updateRepositories(project) }
  }

  interface ListChangeListener {
    fun repositoryListChanged(newList: Set<GHGitRepositoryMapping>, project: Project)
  }

  companion object {
    private val LOG = logger<GHProjectRepositoriesManager>()

    @JvmField
    @Topic.AppLevel
    val LIST_CHANGES_TOPIC = Topic(ListChangeListener::class.java, Topic.BroadcastDirection.NONE)

    private inline fun runInEdt(project: Project, crossinline runnable: () -> Unit) {
      val application = ApplicationManager.getApplication()
      if (application.isDispatchThread) runnable()
      else application.invokeLater({ runnable() }) { project.isDisposed }
    }

    private fun updateRepositories(project: Project) {
      try {
        if (!project.isDisposed) {
          project.service<GHProjectRepositoriesManager>().updateRepositories()
        }
      }
      catch (e: Exception) {
        LOG.info("Error occurred while updating repositories", e)
      }
    }
  }

  override fun dispose() {}
}