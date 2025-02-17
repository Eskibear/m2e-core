/*******************************************************************************
 * Copyright (c) 2013, 2022 Igor Fedorenko
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *      Igor Fedorenko - initial API and implementation
 *      Christoph Läubrich - move creation MavenExecutionRequest from MavenImpl->MavenExecutionContext
 *******************************************************************************/

package org.eclipse.m2e.core.internal.embedder;

import static org.eclipse.m2e.core.internal.M2EUtils.copyProperties;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.transfer.TransferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;

import org.codehaus.plexus.classworlds.realm.ClassRealm;

import org.apache.maven.Maven;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.DependencyContext;
import org.apache.maven.lifecycle.internal.MojoExecutor;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.session.scope.internal.SessionScope;
import org.apache.maven.settings.Settings;

import org.eclipse.m2e.core.embedder.ICallable;
import org.eclipse.m2e.core.embedder.IComponentLookup;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.embedder.IMavenConfiguration;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.Messages;
import org.eclipse.m2e.core.project.IMavenProjectFacade;


/**
 * @since 1.4
 */
public class MavenExecutionContext implements IMavenExecutionContext {

  private static final String CTX_PREFIX = MavenExecutionContext.class.getName();

  private static final String CTX_LOCALREPOSITORY = CTX_PREFIX + "/localRepository";

  private static final String CTX_MAVENSESSION = CTX_PREFIX + "/mavenSession";

  private static final String CTX_REPOSITORYSESSION = CTX_PREFIX + "/repositorySession";

  private static final ThreadLocal<Deque<MavenExecutionContext>> threadLocal = new ThreadLocal<>();

  private final MavenImpl maven;

  private MavenExecutionRequest request;

  // TODO maybe delegate to parent context
  private Map<String, Object> context;

  private final File basedir;

  private IMavenProjectFacade projectFacade;

  public MavenExecutionContext(MavenImpl maven, IMavenProjectFacade projectFacade) {
    this.maven = maven;
    this.projectFacade = projectFacade;
    if(projectFacade != null) {
      File basedir;
      IFile pom = projectFacade.getPom();
      if(pom != null && pom.getLocation() != null) {
        basedir = pom.getLocation().toFile();
      } else {
        basedir = null;
      }
      this.basedir = basedir == null ? null : (basedir.isDirectory() ? basedir : basedir.getParentFile());
    } else {
      this.basedir = null;
    }
  }

  @Override
  public MavenExecutionRequest getExecutionRequest() throws CoreException {
    if(request != null && context != null) {
      return new ReadonlyMavenExecutionRequest(request);
    }
    if(request == null) {
      request = newExecutionRequest();
    }
    return request;
  }

  protected MavenExecutionRequest newExecutionRequest() throws CoreException {
    MavenExecutionRequest request = null;
    Deque<MavenExecutionContext> stack = threadLocal.get();
    if(stack != null && !stack.isEmpty()) {
      MavenExecutionRequest parent = stack.peek().request;
      if(parent == null) {
        throw new IllegalStateException(); //
      }
      request = DefaultMavenExecutionRequest.copy(parent);
    }
    if(request == null) {
      request = createExecutionRequest(maven.getMavenConfiguration(), maven, maven);
      request.setBaseDirectory(basedir);
    }
    request.setMultiModuleProjectDirectory(PlexusContainerManager.computeMultiModuleProjectDirectory(basedir));

    return request;
  }

  static MavenExecutionRequest createExecutionRequest(IMavenConfiguration mavenConfiguration,
      IComponentLookup lookup, IMaven maven)
      throws CoreException {
    MavenExecutionRequest request = new DefaultMavenExecutionRequest();

    // this causes problems with unexpected "stale project configuration" error markers
    // need to think how to manage ${maven.build.timestamp} properly inside workspace
    //request.setStartTime( new Date() );

    if(mavenConfiguration.getGlobalSettingsFile() != null) {
      request.setGlobalSettingsFile(new File(mavenConfiguration.getGlobalSettingsFile()));
    }
    File userSettingsFile = SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE;
    if(mavenConfiguration.getUserSettingsFile() != null) {
      userSettingsFile = new File(mavenConfiguration.getUserSettingsFile());
    }
    request.setUserSettingsFile(userSettingsFile);

    Settings settings = maven.getSettings(); //TODO if we could adapt IMavenConfiguration -> Settings we could save the IMaven reference here 
    //and settings are actually derived from IMavenConfiguration
    try {
      request = lookup.lookup(MavenExecutionRequestPopulator.class).populateFromSettings(request, settings);
    } catch(MavenExecutionRequestPopulationException ex) {
      throw new CoreException(Status.error(Messages.MavenImpl_error_no_exec_req, ex));
    }

    String localRepositoryPath = settings.getLocalRepository();
    if(localRepositoryPath == null) {
      localRepositoryPath = RepositorySystem.defaultUserLocalRepository.getAbsolutePath();
    }

    ArtifactRepository localRepository;
    try {
      localRepository = lookup.lookup(RepositorySystem.class).createLocalRepository(new File(localRepositoryPath));
    } catch(InvalidRepositoryException ex) {
      throw new AssertionError("Should never happen!", ex);
    }
    request.setLocalRepository(localRepository);
    request.setLocalRepositoryPath(localRepository.getBasedir());
    request.setOffline(mavenConfiguration.isOffline());

    request.getUserProperties().put("m2e.version", MavenPluginActivator.getVersion()); //$NON-NLS-1$
    request.getUserProperties().put(ConfigurationProperties.USER_AGENT, MavenPluginActivator.getUserAgent());

    MavenExecutionContext.populateSystemProperties(request);

    request.setCacheNotFound(true);
    request.setCacheTransferError(true);

    request.setGlobalChecksumPolicy(mavenConfiguration.getGlobalChecksumPolicy());
    return request;
  }

  @Override
  public <V> V execute(ICallable<V> callable, IProgressMonitor monitor) throws CoreException {
    return execute(null, callable, monitor);
  }

  @Override
  public <V> V execute(MavenProject project, ICallable<V> callable, IProgressMonitor monitor) throws CoreException {
    Deque<MavenExecutionContext> stack = threadLocal.get();
    if(stack == null) {
      stack = new ArrayDeque<>();
      threadLocal.set(stack);
    }
    final MavenExecutionContext parent = stack.peek();

    if(this == parent) {
      // shortcut the setup logic, this is nested invocation of the same context
      return executeBare(project, callable, monitor);
    }

    // remember original configuration to "pop" the session stack properly
    final MavenExecutionRequest origRequest = request;
    final Map<String, Object> origContext = context;

    if(request == null && parent != null) {
      this.request = parent.request;
      this.context = new HashMap<>(parent.context);
    } else {
      this.context = new HashMap<>();
      if(request == null) {
        request = newExecutionRequest();
      }
      try {
        maven.lookup(MavenExecutionRequestPopulator.class).populateDefaults(request);
      } catch(MavenExecutionRequestPopulationException ex) {
        throw new CoreException(Status.error(Messages.MavenImpl_error_read_config, ex));
      }
      populateSystemProperties(request);
      setValue(CTX_LOCALREPOSITORY, request.getLocalRepository());
      final FilterRepositorySystemSession repositorySession = maven.createRepositorySession(request);
      setValue(CTX_REPOSITORYSESSION, repositorySession);
      if(parent != null) {
        repositorySession.setData(parent.getRepositorySession().getData());
      }
      final MavenExecutionResult result = new DefaultMavenExecutionResult();
      setValue(CTX_MAVENSESSION, new MavenSession(maven.getPlexusContainer(), repositorySession, request, result));
    }

    final LegacySupport legacySupport = maven.lookup(LegacySupport.class);
    final MavenSession origLegacySession = legacySupport.getSession(); // TODO validate == origSession

    stack.push(this);

    final MavenSession session = getSession();
    legacySupport.setSession(session);
    final SessionScope sessionScope = maven.lookup(SessionScope.class);
    sessionScope.enter();
    sessionScope.seed(MavenSession.class, session);

    try {
      return executeBare(project, callable, monitor);
    } finally {
      sessionScope.exit();
      stack.pop();
      if(stack.isEmpty()) {
        threadLocal.set(null); // TODO decide if this is useful
      }
      legacySupport.setSession(origLegacySession);
      request = origRequest;
      context = origContext;
    }
  }

  @Override
  public void execute(MavenProject project, MojoExecution execution, IProgressMonitor monitor) throws CoreException {
    execute(project, (context, pm) -> {
      executeMojo(context.getSession(), execution);
      return null;
    }, monitor);
  }

  @Override
  public MavenExecutionResult execute(MavenExecutionRequest request) {
    try {
      return execute((innerContext, monitor) -> {

        EventSpyDispatcher eventSpyDispatcher = maven.lookup(EventSpyDispatcher.class);
        try {
          //notify about the start of the request ...
          eventSpyDispatcher.onEvent(request);
          //execute the request
          MavenExecutionResult result = maven.lookup(Maven.class).execute(request);
          // notify about the results
          eventSpyDispatcher.onEvent(result);
          return result;
        } finally {
          //free up resources
          eventSpyDispatcher.close();
        }
      }, new NullProgressMonitor());
    } catch(CoreException ex) {
      return new DefaultMavenExecutionResult().addException(ex);
    }
  }

  private void executeMojo(MavenSession session, MojoExecution execution) {
    Map<MavenProject, Set<Artifact>> artifacts = new HashMap<>();
    Map<MavenProject, MavenProjectMutableState> snapshots = new HashMap<>();
    for(MavenProject project : session.getProjects()) {
      artifacts.put(project, new LinkedHashSet<>(project.getArtifacts()));
      snapshots.put(project, MavenProjectMutableState.takeSnapshot(project));
    }
    try {
      MojoExecutor mojoExecutor = maven.lookup(MojoExecutor.class);
      DependencyContext dependencyContext = mojoExecutor.newDependencyContext(session, List.of(execution));
      mojoExecutor.ensureDependenciesAreResolved(execution.getMojoDescriptor(), session, dependencyContext);
      maven.lookup(BuildPluginManager.class).executeMojo(session, execution);
    } catch(Exception ex) {
      session.getResult().addException(ex);
    } finally {
      for(MavenProject project : session.getProjects()) {
        project.setArtifactFilter(null);
        project.setResolvedArtifacts(null);
        project.setArtifacts(artifacts.get(project));
        MavenProjectMutableState snapshot = snapshots.get(project);
        if(snapshot != null) {
          snapshot.restore(project);
        }
      }
    }
  }

  private <V> V executeBare(MavenProject project, ICallable<V> callable, IProgressMonitor monitor) throws CoreException {
    final MavenSession mavenSession = getSession();
    final FilterRepositorySystemSession repositorySession = getRepositorySession();
    final TransferListener origTransferListener = repositorySession.setTransferListener(maven
        .createArtifactTransferListener(monitor));
    final MavenProject origProject = mavenSession.getCurrentProject();
    final List<MavenProject> origProjects = mavenSession.getProjects();
    final ClassLoader origTCCL = Thread.currentThread().getContextClassLoader();
    try {
      if(project == null && projectFacade != null) {
        project = projectFacade.getMavenProject();
      }
      if(project != null) {
        mavenSession.setCurrentProject(project);
        mavenSession.setProjects(Collections.singletonList(project));
      }
      return callable.call(this, IProgressMonitor.nullSafe(monitor));
    } finally {
      Thread.currentThread().setContextClassLoader(origTCCL);
      repositorySession.setTransferListener(origTransferListener);
      if(project != null) {
        mavenSession.setCurrentProject(origProject);
        mavenSession.setProjects(origProjects != null ? origProjects : Collections.<MavenProject> emptyList());
      }
    }
  }

  @Override
  public MavenSession getSession() {
    if(context == null) {
      throw new IllegalStateException();
    }
    return getValue(CTX_MAVENSESSION);
  }

  @Override
  public ArtifactRepository getLocalRepository() {
    if(context == null) {
      throw new IllegalStateException();
    }
    return getValue(CTX_LOCALREPOSITORY);
  }

  @Override
  public FilterRepositorySystemSession getRepositorySession() {
    if(context == null) {
      throw new IllegalStateException();
    }
    return getValue(CTX_REPOSITORYSESSION);
  }

  public static MavenExecutionContext getThreadContext() {
    return getThreadContext(true);
  }

  /**
   * @since 1.5
   */
  public static MavenExecutionContext getThreadContext(boolean innermost) {
    final Deque<MavenExecutionContext> stack = threadLocal.get();
    return stack != null ? (innermost ? stack.peekFirst() : stack.peekLast()) : null;
  }

  public static void populateSystemProperties(MavenExecutionRequest request) {
    // temporary solution for https://issues.sonatype.org/browse/MNGECLIPSE-1607
    // oddly, there are no unit tests that fail if this is commented out
    if(request.getSystemProperties() == null || request.getSystemProperties().isEmpty()) {
      Properties systemProperties = new Properties();
      EnvironmentUtils.addEnvVars(systemProperties);
      copyProperties(systemProperties, System.getProperties());
      MavenProperties.setProperties(systemProperties);
      request.setSystemProperties(systemProperties);
    }
  }

  /*
   * <rant>Maven core does not provide good separation between session state, i.e. caches, settings, etc, and project
   * building configuration, i.e. if dependencies should be resolve, resolution leniency, etc. On top of that, there is
   * no easy way to create new populated ProjectBuildingRequest instances. Otherwise this method would not be
   * needed.</rant>
   */
  @Override
  public ProjectBuildingRequest newProjectBuildingRequest() {
    DefaultProjectBuildingRequest projectBuildingRequest = new DefaultProjectBuildingRequest();
    projectBuildingRequest.setLocalRepository(getLocalRepository());
    projectBuildingRequest.setRepositorySession(getRepositorySession());
    projectBuildingRequest.setSystemProperties(request.getSystemProperties());
    projectBuildingRequest.setUserProperties(request.getUserProperties());
    projectBuildingRequest.setRemoteRepositories(request.getRemoteRepositories());
    projectBuildingRequest.setPluginArtifactRepositories(request.getPluginArtifactRepositories());
    projectBuildingRequest.setActiveProfileIds(request.getActiveProfiles());
    projectBuildingRequest.setInactiveProfileIds(request.getInactiveProfiles());
    projectBuildingRequest.setProfiles(request.getProfiles());
    projectBuildingRequest.setProcessPlugins(true);
    projectBuildingRequest.setBuildStartTime(request.getStartTime());
    return projectBuildingRequest;
  }

  /**
   * Suspends current Maven execution context, if any. Returns suspended context or {@code null} if there was no context
   * associated with the current thread.
   *
   * @see #resume(Deque)
   * @since 1.5
   */
  public static Deque<MavenExecutionContext> suspend() {
    Deque<MavenExecutionContext> queue = threadLocal.get();
    threadLocal.set(null);
    return queue;
  }

  /**
   * Resumes Maven execution context suspended with {@link #suspend()}.
   *
   * @see #resume(Deque)
   * @since 1.5
   */
  public static void resume(Deque<MavenExecutionContext> queue) {
    if(threadLocal.get() != null) {
      throw new IllegalStateException();
    }
    threadLocal.set(queue);
  }

  /**
   * @since 1.5
   */
  @SuppressWarnings("unchecked")
  public <T> T getValue(String key) {
    return (T) context.get(key);
  }

  /**
   * @since 1.5
   */
  public <T> void setValue(String key, T value) {
    context.put(key, value);
  }

  @Override
  public IComponentLookup getComponentLookup() {
    if(context == null) {
      throw new IllegalStateException();
    }
    if(projectFacade == null) {
      return maven;
    }
    IComponentLookup projectComponentLookup = projectFacade.getComponentLookup();
    MavenProject mavenProject = projectFacade.getMavenProject();
    if(mavenProject == null) {
      return projectComponentLookup;
    }
    //project scoped lookup...
    return new IComponentLookup() {
      @Override
      public <T> T lookup(Class<T> clazz) throws CoreException {
        Thread thread = Thread.currentThread();
        ClassLoader ccl = thread.getContextClassLoader();
        try {
          ClassLoader projectRealm = mavenProject.getClassRealm();
          if(projectRealm != null) {
            thread.setContextClassLoader(projectRealm);
          }
          return projectComponentLookup.lookup(clazz);
        } finally {
          thread.setContextClassLoader(ccl);
        }
      }

      @Override
      public <C> Collection<C> lookupCollection(Class<C> type) throws CoreException {
        Thread thread = Thread.currentThread();
        ClassLoader ccl = thread.getContextClassLoader();
        try {
          ClassRealm projectRealm = mavenProject.getClassRealm();
          if(projectRealm != null) {
            thread.setContextClassLoader(projectRealm);
          }
          return projectComponentLookup.lookupCollection(type);
        } finally {
          thread.setContextClassLoader(ccl);
        }
      }

    };
  }


}
