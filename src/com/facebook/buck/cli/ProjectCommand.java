/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.ProjectGenerator;
import com.facebook.buck.apple.SchemeActionType;
import com.facebook.buck.apple.WorkspaceAndProjectGenerator;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaFileParser;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.intellij.IjProject;
import com.facebook.buck.java.intellij.IntellijConfig;
import com.facebook.buck.java.intellij.Project;
import com.facebook.buck.js.ReactNativeBuckConfig;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.AssociatedTargetNodePredicate;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import org.kohsuke.args4j.Option;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

public class ProjectCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final Predicate<TargetNode<?>> ANNOTATION_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          if (input.getType() != JavaLibraryDescription.TYPE) {
            return false;
          }
          JavaLibraryDescription.Arg arg = ((JavaLibraryDescription.Arg) input.getConstructorArg());
          return !arg.annotationProcessors.get().isEmpty();
        }
      };

  private static final String XCODE_PROCESS_NAME = "Xcode";

  public enum Ide {
    INTELLIJ,
    XCODE;

    public static Ide fromString(String string) {
      switch (Ascii.toLowerCase(string)) {
        case "intellij":
          return Ide.INTELLIJ;
        case "xcode":
          return Ide.XCODE;
        default:
          throw new HumanReadableException("Invalid ide value %s.", string);
      }
    }

  }

  private static final Ide DEFAULT_IDE_VALUE = Ide.INTELLIJ;
  private static final boolean DEFAULT_READ_ONLY_VALUE = false;
  private static final boolean DEFAULT_DISABLE_R_JAVA_IDEA_GENERATOR = false;

  @Option(
      name = "--combined-project",
      usage = "Generate an xcode project of a target and its dependencies.")
  private boolean combinedProject;

  @Option(
      name = "--build-with-buck",
      usage = "Use Buck to build the generated project instead of delegating the build to the IDE.")
  private boolean buildWithBuck;

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
      name = "--without-tests",
      usage = "When generating a project slice, exclude tests that test the code in that slice")
  private boolean withoutTests = false;

  @Option(
      name = "--combine-test-bundles",
      usage = "Combine multiple ios/osx test targets into the same bundle if they have identical " +
          "settings")
  private boolean combineTestBundles = false;

  @Option(
      name = "--ide",
      usage = "The type of IDE for which to generate a project. Defaults to 'intellij' if not " +
          "specified in .buckconfig.")
  @Nullable
  private Ide ide = null;

  @Option(
      name = "--read-only",
      usage = "If true, generate project files read-only. Defaults to '" +
          DEFAULT_READ_ONLY_VALUE + "' if not specified in .buckconfig. (Only " +
          "applies to generated Xcode projects.)")
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Option(
      name = "--dry-run",
      usage = "Instead of actually generating the project, only print out the targets that " +
          "would be included.")
  private boolean dryRun = false;

  @Option(
      name = "--disable-r-java-idea-generator",
      usage = "Turn off auto generation of R.java by Android IDEA plugin." +
          " You can specify disable_r_java_idea_generator = true" +
          " in .buckconfig/project section")
  private boolean androidAutoGenerateDisabled = DEFAULT_DISABLE_R_JAVA_IDEA_GENERATOR;

  @Option(
      name = "--experimental-ij-generation",
      usage = "Enables the experimental IntelliJ project generator.")
  private boolean experimentalIntelliJProjectGenerationEnabled = false;

  public boolean getCombinedProject() {
    return combinedProject;
  }

  public boolean getDryRun() {
    return dryRun;
  }

  public boolean getCombineTestBundles() {
    return combineTestBundles;
  }

  public boolean shouldProcessAnnotations() {
    return processAnnotations;
  }

  public ImmutableMap<Path, String> getBasePathToAliasMap(BuckConfig buckConfig) {
    return buckConfig.getBasePathToAliasMap();
  }

  public JavaPackageFinder getJavaPackageFinder(BuckConfig buckConfig) {
    return buckConfig.createDefaultJavaPackageFinder();
  }

  public Optional<String> getPathToDefaultAndroidManifest(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "default_android_manifest");
  }

  public Optional<String> getPathToPostProcessScript(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "post_process");
  }

  public boolean getReadOnly(BuckConfig buckConfig) {
    if (readOnly) {
      return readOnly;
    }
    return buckConfig.getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  public boolean isAndroidAutoGenerateDisabled(BuckConfig buckConfig) {
    if (androidAutoGenerateDisabled) {
      return androidAutoGenerateDisabled;
    }
    return buckConfig.getBooleanValue(
        "project",
        "disable_r_java_idea_generator",
        DEFAULT_DISABLE_R_JAVA_IDEA_GENERATOR);
  }

  /**
   * Returns true if Buck should prompt to kill a running IDE before changing its files,
   * false otherwise.
   */
  public boolean getIdePrompt(BuckConfig buckConfig) {
    return buckConfig.getBooleanValue("project", "ide_prompt", true);
  }

  public Ide getIde(BuckConfig buckConfig) {
    if (ide != null) {
      return ide;
    } else {
      Optional<Ide> ide = buckConfig.getValue("project", "ide").transform(
          new Function<String, Ide>() {
            @Override
            public Ide apply(String input) {
              return Ide.fromString(input);
            }
          });
      return ide.or(DEFAULT_IDE_VALUE);
    }
  }

  public boolean isWithTests() {
    return !withoutTests;
  }

  private List<String> getInitialTargets(BuckConfig buckConfig) {
    Optional<String> initialTargets = buckConfig.getValue("project", "initial_targets");
    return initialTargets.isPresent()
        ? Lists.newArrayList(Splitter.on(' ').trimResults().split(initialTargets.get()))
        : ImmutableList.<String>of();
  }

  public boolean hasInitialTargets(BuckConfig buckConfig) {
    return !getInitialTargets(buckConfig).isEmpty();
  }

  public BuildCommand createBuildCommandOptionsWithInitialTargets(
      BuckConfig buckConfig,
      List<String> additionalInitialTargets) {
    List<String> initialTargets;
    if (additionalInitialTargets.isEmpty()) {
      initialTargets = getInitialTargets(buckConfig);
    } else {
      initialTargets = Lists.newArrayList();
      initialTargets.addAll(getInitialTargets(buckConfig));
      initialTargets.addAll(additionalInitialTargets);
    }

    BuildCommand buildCommand = new BuildCommand();
    buildCommand.setArguments(initialTargets);
    return buildCommand;
  }

  public boolean isExperimentalIntelliJProjectGenerationEnabled() {
    return experimentalIntelliJProjectGenerationEnabled;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (getIde(params.getBuckConfig()) == ProjectCommand.Ide.XCODE) {
      checkForAndKillXcodeIfRunning(params, getIdePrompt(params.getBuckConfig()));
    }

    Pair<ImmutableSet<BuildTarget>, TargetGraph> results = null;
    try {
       results = params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  params.getRepository().getFilesystem().getIgnorePaths(),
                  getArguments()),
              new ParserConfig(params.getBuckConfig()),
              params.getBuckEventBus(),
              params.getConsole(),
              params.getEnvironment(),
              getEnableProfiling());
    } catch (BuildTargetException | BuildFileParseException | HumanReadableException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    ImmutableSet<BuildTarget> passedInTargetsSet = results.getFirst();
    ProjectGraphParser projectGraphParser = ProjectGraphParsers.createProjectGraphParser(
        params.getParser(),
        new ParserConfig(params.getBuckConfig()),
        params.getBuckEventBus(),
        params.getConsole(),
        params.getEnvironment(),
        getEnableProfiling());

    TargetGraph projectGraph = projectGraphParser.buildTargetGraphForTargetNodeSpecs(
        getTargetNodeSpecsForIde(
            getIde(params.getBuckConfig()),
            passedInTargetsSet,
            params.getRepository().getFilesystem().getIgnorePaths(),
            isExperimentalIntelliJProjectGenerationEnabled()));

    ProjectPredicates projectPredicates = ProjectPredicates.forIde(getIde(params.getBuckConfig()));

    ImmutableSet<BuildTarget> graphRoots;
    if (!passedInTargetsSet.isEmpty()) {
      ImmutableSet<BuildTarget> supplementalGraphRoots = ImmutableSet.of();
      if (getIde(params.getBuckConfig()) == Ide.INTELLIJ &&
          !isExperimentalIntelliJProjectGenerationEnabled()) {
        supplementalGraphRoots = getRootBuildTargetsForIntelliJ(
            getIde(params.getBuckConfig()),
            projectGraph,
            projectPredicates);
      }
      graphRoots = Sets.union(passedInTargetsSet, supplementalGraphRoots).immutableCopy();
    } else {
      graphRoots = getRootsFromPredicate(
          projectGraph,
          projectPredicates.getProjectRootsPredicate());
    }

    TargetGraphAndTargets targetGraphAndTargets = createTargetGraph(
        projectGraph,
        graphRoots,
        projectGraphParser,
        projectPredicates.getAssociatedProjectPredicate(),
        isWithTests(),
        getIde(params.getBuckConfig()),
        params.getRepository().getFilesystem().getIgnorePaths(),
        isExperimentalIntelliJProjectGenerationEnabled());

    if (getDryRun()) {
      for (TargetNode<?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
        params.getConsole().getStdOut().println(targetNode.toString());
      }

      return 0;
    }

    switch (getIde(params.getBuckConfig())) {
      case INTELLIJ:
        return runIntellijProjectGenerator(
            params,
            projectGraph,
            targetGraphAndTargets,
            passedInTargetsSet);
      case XCODE:
        return runXcodeProjectGenerator(
            params,
            targetGraphAndTargets,
            passedInTargetsSet);
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  public static ImmutableSet<BuildTarget> getRootBuildTargetsForIntelliJ(
      ProjectCommand.Ide ide,
      TargetGraph projectGraph,
      ProjectPredicates projectPredicates) {
    if (ide != ProjectCommand.Ide.INTELLIJ) {
      return ImmutableSet.of();
    }
    return getRootsFromPredicate(
        projectGraph,
        Predicates.and(
            new Predicate<TargetNode<?>>() {

              @Override
              public boolean apply(TargetNode<?> input) {
                return input.getBuildTarget() != null &&
                    input.getBuildTarget().getBasePathWithSlash().isEmpty();
              }
            },
            projectPredicates.getProjectRootsPredicate()
        )
    );
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runExperimentalIntellijProjectGenerator(
      CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets) throws IOException, InterruptedException {
    ActionGraph actionGraph = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer(),
        params.getFileHashCache()).apply(targetGraphAndTargets.getTargetGraph());
    BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(ImmutableSet.copyOf(actionGraph.getNodes()));
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);

    JavacOptions javacOptions = new JavaBuckConfig(params.getBuckConfig())
        .getDefaultJavacOptions(new ProcessExecutor(params.getConsole()));

    IjProject project = new IjProject(
        targetGraphAndTargets,
        getJavaPackageFinder(params.getBuckConfig()),
        JavaFileParser.createJavaFileParser(javacOptions),
        buildRuleResolver,
        sourcePathResolver,
        params.getRepository().getFilesystem());

    ImmutableSet<BuildTarget> requiredBuildTargets = project.write();

    if (!requiredBuildTargets.isEmpty()) {
      BuildCommand buildCommand = new BuildCommand();
      buildCommand.setArguments(
          FluentIterable.from(requiredBuildTargets)
              .transform(Functions.toStringFunction())
              .toList());
      return buildCommand.run(params);
    }

    return 0;
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(
      CommandRunnerParams params,
      TargetGraph projectGraph,
      TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws IOException, InterruptedException {
    if (isExperimentalIntelliJProjectGenerationEnabled()) {
      return runExperimentalIntellijProjectGenerator(params, targetGraphAndTargets);
    }
    // Create an ActionGraph that only contains targets that can be represented as IDE
    // configuration files.
    ActionGraph actionGraph = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer(),
        params.getFileHashCache()).apply(targetGraphAndTargets.getTargetGraph());

    try (ExecutionContext executionContext = createExecutionContext(params)) {
      Project project = new Project(
          new SourcePathResolver(
              new BuildRuleResolver(ImmutableSet.copyOf(actionGraph.getNodes()))),
          FluentIterable
              .from(actionGraph.getNodes())
              .filter(ProjectConfig.class)
              .toSortedSet(Ordering.natural()),
          actionGraph,
          getBasePathToAliasMap(params.getBuckConfig()),
          getJavaPackageFinder(params.getBuckConfig()),
          executionContext,
          new FilesystemBackedBuildFileTree(
              params.getRepository().getFilesystem(),
              new ParserConfig(params.getBuckConfig()).getBuildFileName()),
          params.getRepository().getFilesystem(),
          getPathToDefaultAndroidManifest(params.getBuckConfig()),
          new IntellijConfig(params.getBuckConfig()),
          getPathToPostProcessScript(params.getBuckConfig()),
          new PythonBuckConfig(
              params.getBuckConfig(),
              new ExecutableFinder()).getPythonInterpreter(),
          params.getObjectMapper(),
          isAndroidAutoGenerateDisabled(params.getBuckConfig()));

      File tempDir = Files.createTempDir();
      File tempFile = new File(tempDir, "project.json");
      int exitCode;
      try {
        exitCode = project.createIntellijProject(
            tempFile,
            executionContext.getProcessExecutor(),
            !passedInTargetsSet.isEmpty(),
            params.getConsole().getStdOut(),
            params.getConsole().getStdErr());
        if (exitCode != 0) {
          return exitCode;
        }

        List<String> additionalInitialTargets = ImmutableList.of();
        if (shouldProcessAnnotations()) {
          try {
            additionalInitialTargets = getAnnotationProcessingTargets(
                projectGraph,
                passedInTargetsSet);
          } catch (BuildTargetException | BuildFileParseException e) {
            throw new HumanReadableException(e);
          }
        }

        // Build initial targets.
        if (hasInitialTargets(params.getBuckConfig()) ||
            !additionalInitialTargets.isEmpty()) {
          BuildCommand buildCommand = createBuildCommandOptionsWithInitialTargets(
              params.getBuckConfig(),
              additionalInitialTargets);


          exitCode = buildCommand.runWithoutHelp(params);
          if (exitCode != 0) {
            return exitCode;
          }
        }
      } finally {
        // Either leave project.json around for debugging or delete it on exit.
        if (params.getConsole().getVerbosity().shouldPrintOutput()) {
          params.getConsole().getStdErr().printf(
              "project.json was written to %s",
              tempFile.getAbsolutePath());
        } else {
          tempFile.delete();
          tempDir.delete();
        }
      }

      if (passedInTargetsSet.isEmpty()) {
        String greenStar = params.getConsole().getAnsi().asHighlightedSuccessText(" * ");
        params.getConsole().getStdErr().printf(
            params.getConsole().getAnsi().asHighlightedSuccessText("=== Did you know ===") + "\n" +
                greenStar + "You can run `buck project <target>` to generate a minimal project " +
                "just for that target.\n" +
                greenStar + "This will make your IDE faster when working on large projects.\n" +
                greenStar + "See buck project --help for more info.\n" +
                params.getConsole().getAnsi().asHighlightedSuccessText(
                    "--=* Knowing is half the battle!") + "\n");
      }

      return 0;
    }
  }

  ImmutableList<String> getAnnotationProcessingTargets(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> buildTargets;
    if (!passedInTargetsSet.isEmpty()) {
      buildTargets = passedInTargetsSet;
    } else {
      buildTargets = getRootsFromPredicate(
          projectGraph,
          ANNOTATION_PREDICATE);
    }
    return FluentIterable
        .from(buildTargets)
        .transform(Functions.toStringFunction())
        .toList();
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(
      final CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws IOException, InterruptedException {
    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (getReadOnly(params.getBuckConfig())) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (isWithTests()) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }

    boolean combinedProject = getCombinedProject();
    ImmutableSet<BuildTarget> targets;
    if (passedInTargetsSet.isEmpty()) {
      targets = FluentIterable
          .from(targetGraphAndTargets.getProjectRoots())
          .transform(HasBuildTarget.TO_TARGET)
          .toSet();
    } else {
      targets = passedInTargetsSet;
    }
    if (combinedProject) {
      optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS);
    } else {
      optionsBuilder.addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS);
    }
    LOG.debug("Generating workspace for config targets %s", targets);
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    ImmutableSet<TargetNode<?>> testTargetNodes = targetGraphAndTargets.getAssociatedTests();
    ImmutableSet<TargetNode<AppleTestDescription.Arg>> groupableTests = getCombineTestBundles()
        ? AppleBuildRules.filterGroupableTests(testTargetNodes)
        : ImmutableSet.<TargetNode<AppleTestDescription.Arg>>of();
    ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder = ImmutableSet.builder();
    for (final BuildTarget inputTarget : targets) {
      TargetNode<?> inputNode = Preconditions.checkNotNull(
          targetGraphAndTargets.getTargetGraph().get(inputTarget));
      XcodeWorkspaceConfigDescription.Arg workspaceArgs;
      BuildRuleType type = inputNode.getType();
      if (type == XcodeWorkspaceConfigDescription.TYPE) {
        TargetNode<XcodeWorkspaceConfigDescription.Arg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(inputNode);
        workspaceArgs = castedWorkspaceNode.getConstructorArg();
      } else if (canGenerateImplicitWorkspaceForType(type)) {
        workspaceArgs = createImplicitWorkspaceArgs(inputNode);
      } else {
        throw new HumanReadableException(
            "%s must be a xcode_workspace_config, apple_bundle, or apple_library",
            inputNode);
      }
      WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
          params.getRepository().getFilesystem(),
          new ReactNativeBuckConfig(params.getBuckConfig()),
          targetGraphAndTargets.getTargetGraph(),
          workspaceArgs,
          inputTarget,
          optionsBuilder.build(),
          combinedProject,
          buildWithBuck,
          super.getOptions(),
          new ParserConfig(params.getBuckConfig()).getBuildFileName(),
          new Function<TargetNode<?>, Path>() {
            @Nullable
            @Override
            public Path apply(TargetNode<?> input) {
              TargetGraphToActionGraph targetGraphToActionGraph = new TargetGraphToActionGraph(
                  params.getBuckEventBus(),
                  new BuildTargetNodeToBuildRuleTransformer(),
                  params.getFileHashCache());
              TargetGraph subgraph = targetGraphAndTargets.getTargetGraph().getSubgraph(
                  ImmutableSet.of(
                      input));
              ActionGraph actionGraph = Preconditions.checkNotNull(
                  targetGraphToActionGraph.apply(subgraph));
              BuildRule rule = Preconditions.checkNotNull(
                  actionGraph.findBuildRuleByTarget(input.getBuildTarget()));
              return rule.getPathToOutput();
            }
          });
      generator.setGroupableTests(groupableTests);
      generator.generateWorkspaceAndDependentProjects(projectGenerators);
      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.debug(
          "Required build targets for workspace %s: %s",
          inputTarget,
          requiredBuildTargetsForWorkspace);
      requiredBuildTargetsBuilder.addAll(requiredBuildTargetsForWorkspace);
    }

    int exitCode = 0;
    ImmutableSet<BuildTarget> requiredBuildTargets = requiredBuildTargetsBuilder.build();
    if (!requiredBuildTargets.isEmpty()) {
      BuildCommand buildCommand = new BuildCommand();
      buildCommand.setArguments(
          FluentIterable.from(requiredBuildTargets)
              .transform(Functions.toStringFunction())
              .toList());
      exitCode = buildCommand.runWithoutHelp(params);
    }
    return exitCode;
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescription.Arg> castToXcodeWorkspaceTargetNode(
      TargetNode<?> targetNode) {
    Preconditions.checkArgument(targetNode.getType() == XcodeWorkspaceConfigDescription.TYPE);
    return (TargetNode<XcodeWorkspaceConfigDescription.Arg>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(CommandRunnerParams params, boolean enablePrompt)
      throws InterruptedException, IOException {
    Optional<ProcessManager> processManager = params.getProcessManager();
    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    if (enablePrompt && canPrompt()) {
      if (
          prompt(
              params,
              "Xcode is currently running. Buck will modify files Xcode currently has " +
                  "open, which can cause it to become unstable.\n\n" +
                  "Kill Xcode and continue?")) {
        processManager.get().killProcess(XCODE_PROCESS_NAME);
      } else {
        params.getConsole().getStdOut().println(
            params.getConsole().getAnsi().asWarningText(
                "Xcode is running. Generated projects might be lost or corrupted if Xcode " +
                    "currently has them open."));
      }
      params.getConsole().getStdOut().format(
          "To disable this prompt in the future, add the following to %s: \n\n" +
              "[project]\n" +
              "  ide_prompt = false\n\n",
          params.getRepository().getFilesystem()
              .getRootPath()
              .resolve(BuckConfig.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME)
              .toAbsolutePath());
    } else {
      LOG.debug(
          "Xcode is running, but cannot prompt to kill it (enabled %s, can prompt %s)",
          enablePrompt, canPrompt());
    }
  }

  private boolean canPrompt() {
    return System.console() != null;
  }

  private boolean prompt(CommandRunnerParams params, String prompt) throws IOException {
    Preconditions.checkState(canPrompt());

    LOG.debug("Displaying prompt %s..", prompt);
    params
        .getConsole()
        .getStdOut()
        .print(params.getConsole().getAnsi().asWarningText(prompt + " [Y/n] "));

    Optional<String> result;
    try (InputStreamReader stdinReader = new InputStreamReader(System.in, Charsets.UTF_8);
         BufferedReader bufferedStdinReader = new BufferedReader(stdinReader)) {
      result = Optional.fromNullable(bufferedStdinReader.readLine());
    }
    LOG.debug("Result of prompt: [%s]", result);
    return result.isPresent() &&
        (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph,
      Predicate<TargetNode<?>> rootsPredicate) {
    return FluentIterable
        .from(projectGraph.getNodes())
        .filter(rootsPredicate)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
  }

  private static Iterable<? extends TargetNodeSpec> getTargetNodeSpecsForIde(
      ProjectCommand.Ide ide,
      Collection<BuildTarget> passedInBuildTargets,
      ImmutableSet<Path> ignoreDirs,
      boolean experimentalProjectGenerationEnabled
  ) {
    if ((ide == ProjectCommand.Ide.XCODE || experimentalProjectGenerationEnabled) &&
        !passedInBuildTargets.isEmpty()) {
      return Iterables.transform(
          passedInBuildTargets,
          BuildTargetSpec.TO_BUILD_TARGET_SPEC);
    } else {
      return ImmutableList.of(
          TargetNodePredicateSpec.of(
              Predicates.<TargetNode<?>>alwaysTrue(),
              BuildFileSpec.fromRecursivePath(Paths.get(""), ignoreDirs)));
    }
  }

  private static TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> graphRoots,
      ProjectGraphParser projectGraphParser,
      AssociatedTargetNodePredicate associatedProjectPredicate,
      boolean isWithTests,
      ProjectCommand.Ide ide,
      ImmutableSet<Path> ignoreDirs,
      boolean experimentalProjectGenerationEnabled
  )
      throws IOException, InterruptedException {

    TargetGraph resultProjectGraph;
    ImmutableSet<BuildTarget> explicitTestTargets;

    if (isWithTests) {
      explicitTestTargets = TargetGraphAndTargets.getExplicitTestTargets(
          graphRoots,
          projectGraph);
      resultProjectGraph =
          projectGraphParser.buildTargetGraphForTargetNodeSpecs(
              getTargetNodeSpecsForIde(
                  ide,
                  Sets.union(graphRoots, explicitTestTargets),
                  ignoreDirs,
                  experimentalProjectGenerationEnabled));
    } else {
      resultProjectGraph = projectGraph;
      explicitTestTargets = ImmutableSet.of();
    }

    return TargetGraphAndTargets.create(
        graphRoots,
        resultProjectGraph,
        associatedProjectPredicate,
        isWithTests,
        explicitTestTargets);
  }

  private boolean canGenerateImplicitWorkspaceForType(BuildRuleType type) {
    // We weren't given a workspace target, but we may have been given something that could
    // still turn into a workspace (for example, a library or an actual app rule). If that's the
    // case we still want to generate a workspace.
    return type == AppleBundleDescription.TYPE ||
        type == AppleLibraryDescription.TYPE;
  }

  /**
   * @param sourceTargetNode - The TargetNode which will act as our fake workspaces `src_target`
   * @return Workspace Args that describe a generic Xcode workspace containing `src_target` and its
   * tests
   */
  private XcodeWorkspaceConfigDescription.Arg createImplicitWorkspaceArgs(
      TargetNode<?> sourceTargetNode) {
    XcodeWorkspaceConfigDescription.Arg workspaceArgs = new XcodeWorkspaceConfigDescription.Arg();
    workspaceArgs.srcTarget = Optional.of(sourceTargetNode.getBuildTarget());
    workspaceArgs.actionConfigNames = Optional.of(ImmutableMap.<SchemeActionType, String>of());
    workspaceArgs.extraTests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    workspaceArgs.extraTargets = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    workspaceArgs.workspaceName = Optional.absent();
    workspaceArgs.extraSchemes = Optional.of(ImmutableSortedMap.<String, BuildTarget>of());
    workspaceArgs.isRemoteRunnable = Optional.absent();
    return workspaceArgs;
  }

  @Override
  public String getShortDescription() {
    return "generates project configuration files for an IDE";
  }

}
