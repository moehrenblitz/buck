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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * Abstract implementation of a {@link BuildRule} that can be cached. If its current {@link RuleKey}
 * matches the one on disk, then it has no work to do. It should also try to fetch its output from
 * an {@link ArtifactCache} to avoid doing any computation.
 */
@Beta
public abstract class AbstractCachingBuildRule extends AbstractBuildRule
    implements BuildRule {

  private final Buildable buildable;

  /**
   * Lock used to ensure that the logic to kick off a build is performed at most once.
   */
  private final AtomicBoolean hasBuildStarted;

  /**
   * This is the value returned by {@link #build(BuildContext)}.
   * This is initialized by the constructor and marked as final because {@link #build(BuildContext)}
   * must always return the same value.
   */
  private final SettableFuture<BuildRuleSuccess> buildRuleResult;

  /** @see Buildable#getInputsToCompareToOutput()  */
  private Iterable<Path> inputsToCompareToOutputs;

  protected AbstractCachingBuildRule(Buildable buildable, BuildRuleParams params) {
    super(params);
    this.buildable = Preconditions.checkNotNull(buildable);
    this.hasBuildStarted = new AtomicBoolean(false);
    this.buildRuleResult = SettableFuture.create();
  }

  protected AbstractCachingBuildRule(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
    this.hasBuildStarted = new AtomicBoolean(false);
    this.buildRuleResult = SettableFuture.create();
    this.buildable = Preconditions.checkNotNull(getBuildable());
  }

  /**
   * This rule is designed to be used for precondition checks in subclasses. For example, before
   * running the tests associated with a build rule, it is reasonable to do a sanity check to
   * ensure that the rule has been built.
   */
  protected final synchronized boolean isRuleBuilt() {
    return MoreFutures.isSuccess(buildRuleResult);
  }

  @Override
  public BuildRuleSuccess.Type getBuildResultType() {
    Preconditions.checkState(isRuleBuilt());
    try {
      return buildRuleResult.get().getType();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Iterable<Path> getInputs() {
    if (inputsToCompareToOutputs == null) {
      inputsToCompareToOutputs = MorePaths.asPaths(
          buildable.getInputsToCompareToOutput());
    }
    return inputsToCompareToOutputs;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    // For a rule that lists its inputs via a "srcs" argument, this may seem redundant, but it is
    // not. Here, the inputs are specified as InputRules, which means that the _contents_ of the
    // files will be hashed. In the case of .set("srcs", srcs), the list of strings itself will be
    // hashed. It turns out that we need both of these in order to construct a RuleKey correctly.
    builder = super.appendToRuleKey(builder)
        .setInputs("buck.inputs", getInputs().iterator());
    // TODO(simons): Rename this when no Buildables extend this class.
    return buildable.appendDetailsToRuleKey(builder);
  }

  @Override
  public final ListenableFuture<BuildRuleSuccess> build(final BuildContext context) {
    // We use hasBuildStarted as a lock so that we can minimize how much we need to synchronize.
    synchronized(hasBuildStarted) {
      if (hasBuildStarted.get()) {
        return buildRuleResult;
      } else {
        hasBuildStarted.set(true);
      }
    }

    // Build all of the deps first and then schedule a callback for this rule to build itself once
    // all of those rules are done building.
    try {
      // Invoke every dep's build() method and create an uber-ListenableFuture that represents the
      // successful completion of all deps.
      List<ListenableFuture<BuildRuleSuccess>> builtDeps =
          Lists.newArrayListWithCapacity(getDeps().size());
      for (BuildRule dep : getDeps()) {
        builtDeps.add(dep.build(context));
      }
      ListenableFuture<List<BuildRuleSuccess>> allBuiltDeps = Futures.allAsList(builtDeps);

      // Schedule this rule to build itself once all of the deps are built.
      Futures.addCallback(allBuiltDeps,
          new FutureCallback<List<BuildRuleSuccess>>() {

            private final BuckEventBus eventBus = context.getEventBus();

            private final OnDiskBuildInfo onDiskBuildInfo = context.createOnDiskBuildInfoFor(
                getBuildTarget());

            /**
             * It is imperative that:
             * <ol>
             *   <li>The {@link BuildInfoRecorder} is not constructed until all of the
             *       {@link Buildable}'s {@code deps} are guaranteed to be built. This ensures that
             *       the {@link RuleKey} will be available before the {@link BuildInfoRecorder} is
             *       constructed.
             *       <p>
             *       This is why a {@link Supplier} is used.
             *   <li>Only one {@link BuildInfoRecorder} is created per {@link Buildable}. This
             *       ensures that all build-related information for a {@link Buildable} goes though
             *       a single recorder, whose data will be persisted in {@link #onSuccess(List)}.
             *       <p>
             *       This is why {@link Suppliers#memoize(Supplier)} is used.
             * </ol>
             */
            private final Supplier<BuildInfoRecorder> buildInfoRecorder = Suppliers.memoize(
                new Supplier<BuildInfoRecorder>() {
                  @Override
                  public BuildInfoRecorder get() {
                    AbstractBuildRule buildRule = AbstractCachingBuildRule.this;
                    RuleKey ruleKey;
                    RuleKey ruleKeyWithoutDeps;
                    try {
                      ruleKey = buildRule.getRuleKey();
                      ruleKeyWithoutDeps = buildRule.getRuleKeyWithoutDeps();
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }

                    return context.createBuildInfoRecorder(
                        buildRule.getBuildTarget(), ruleKey, ruleKeyWithoutDeps);
                  }
                });

            private boolean startOfBuildWasRecordedOnTheEventBus = false;

            @Override
            public void onSuccess(List<BuildRuleSuccess> deps) {
              // Record the start of the build.
              eventBus.post(BuildRuleEvent.started(AbstractCachingBuildRule.this));
              startOfBuildWasRecordedOnTheEventBus = true;

              try {
                BuildResult result = buildOnceDepsAreBuilt(
                    context, onDiskBuildInfo, buildInfoRecorder.get());
                if (result.isSuccess()) {
                  recordBuildRuleSuccess(result);
                } else {
                  recordBuildRuleFailure(result);
                }
              } catch (IOException e) {
                onFailure(e);
              }
            }

            private void recordBuildRuleSuccess(BuildResult result) {
              // Make sure that all of the local files have the same values they would as if the
              // rule had been built locally.
              if (result.success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
                try {
                  buildInfoRecorder.get().writeMetadataToDisk();
                } catch (IOException e) {
                  onFailure(e);
                }
              }

              // Give the rule a chance to populate its internal data structures now that all of the
              // files should be in a valid state.
              if (result.success.shouldInitializeFromDiskAfterBuilding()) {
                initializeFromDisk(onDiskBuildInfo);
              }

              // Only now that the rule should be in a completely valid state, resolve the future.
              BuildRuleSuccess buildRuleSuccess = new BuildRuleSuccess(
                  AbstractCachingBuildRule.this, result.success);
              buildRuleResult.set(buildRuleSuccess);

              // Do the post to the event bus immediately after the future is set so that the
              // build time measurement is as accurate as possible.
              eventBus.post(BuildRuleEvent.finished(AbstractCachingBuildRule.this,
                  result.status,
                  result.cacheResult,
                  Optional.of(result.success)));

              // Finally, upload to the artifact cache.
              if (result.success.shouldUploadResultingArtifact()) {
                buildInfoRecorder.get().performUploadToArtifactCache(context.getArtifactCache(),
                    eventBus);
              }
            }

            @Override
            public void onFailure(Throwable failure) {
              recordBuildRuleFailure(new BuildResult(failure));
            }

            private void recordBuildRuleFailure(BuildResult result) {
              // TODO(mbolin): Delete all genfiles and metadata, as they are not guaranteed to be
              // valid at this point?

              // Note that startOfBuildWasRecordedOnTheEventBus will be false if onSuccess() was
              // never invoked.
              if (startOfBuildWasRecordedOnTheEventBus) {
                eventBus.post(BuildRuleEvent.finished(AbstractCachingBuildRule.this,
                    result.status,
                    result.cacheResult,
                    Optional.<BuildRuleSuccess.Type>absent()));
              }

              // It seems possible (albeit unlikely) that something could go wrong in
              // recordBuildRuleSuccess() after buildRuleResult has been resolved such that Buck
              // would attempt to resolve the future again, which would fail.
              buildRuleResult.setException(result.failure);
            }
          },
          context.getExecutor());
    } catch (Throwable failure) {
      // This is a defensive catch block: if buildRuleResult is never satisfied, then Buck will
      // hang because a callback that is waiting for this rule's future to complete will never be
      // executed.
      buildRuleResult.setException(failure);
    }

    return buildRuleResult;
  }

  /**
   * This method is invoked once all of this rule's dependencies are built.
   * <p>
   * This method should be executed on a fresh Runnable in BuildContext's ListeningExecutorService,
   * so there is no reason to schedule new work in a new Runnable.
   * <p>
   * All exit paths through this method should resolve {@link #buildRuleResult} before exiting. To
   * that end, this method should never throw an exception, or else Buck will hang waiting for
   * {@link #buildRuleResult} to be resolved.
   */
  private BuildResult buildOnceDepsAreBuilt(final BuildContext context,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder) throws IOException {
    // Compute the current RuleKey and compare it to the one stored on disk.
    RuleKey ruleKey = getRuleKey();
    Optional<RuleKey> cachedRuleKey = onDiskBuildInfo.getRuleKey();

    // If the RuleKeys match, then there is nothing to build.
    if (ruleKey.equals(cachedRuleKey.orNull())) {
      context.logBuildInfo("[UNCHANGED %s]", getFullyQualifiedName());
      return new BuildResult(BuildRuleSuccess.Type.MATCHING_RULE_KEY,
          CacheResult.LOCAL_KEY_UNCHANGED_HIT);
    }

    // Deciding whether we need to rebuild is tricky business. We want to rebuild as little as
    // possible while always being sound.
    //
    // For java_library rules that depend only on their first-order deps,
    // they only need to rebuild themselves if any of the following conditions hold:
    // (1) The definition of the build rule has changed.
    // (2) Any of the input files (which includes resources as well as .java files) have changed.
    // (3) The ABI of any of its dependent java_library rules has changed.
    //
    // For other types of build rules, we have to be more conservative when rebuilding. In those
    // cases, we rebuild if any of the following conditions hold:
    // (1) The definition of the build rule has changed.
    // (2) Any of the input files have changed.
    // (3) Any of the RuleKeys of this rule's deps have changed.
    //
    // Because a RuleKey for a rule will change if any of its transitive deps have changed, that
    // means a change in one of the leaves can result in almost all rules being rebuilt, which is
    // slow. Fortunately, we limit the effects of this when building Java code when checking the ABI
    // of deps instead of the RuleKey for deps.
    if (this instanceof AbiRule) {
      AbiRule abiRule = (AbiRule)this;

      RuleKey ruleKeyNoDeps = getRuleKeyWithoutDeps();
      Optional<RuleKey> cachedRuleKeyNoDeps = onDiskBuildInfo.getRuleKeyWithoutDeps();
      if (ruleKeyNoDeps.equals(cachedRuleKeyNoDeps.orNull())) {
        // The RuleKey for the definition of this build rule and its input files has not changed.
        // Therefore, if the ABI of its deps has not changed, there is nothing to rebuild.
        Sha1HashCode abiKeyForDeps = abiRule.getAbiKeyForDeps();
        Optional<Sha1HashCode> cachedAbiKeyForDeps = onDiskBuildInfo.getHash(
            AbiRule.ABI_KEY_FOR_DEPS_ON_DISK_METADATA);
        if (abiKeyForDeps.equals(cachedAbiKeyForDeps.orNull())) {
          // Re-copy the ABI metadata.
          // TODO(mbolin): This seems really bad: there could be other metadata to copy, too?
          buildInfoRecorder.addMetadata(
              AbiRule.ABI_KEY_ON_DISK_METADATA,
              onDiskBuildInfo.getValue(AbiRule.ABI_KEY_ON_DISK_METADATA).get());
          buildInfoRecorder.addMetadata(
              AbiRule.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
              cachedAbiKeyForDeps.get().getHash());
          return new BuildResult(BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
              CacheResult.LOCAL_KEY_UNCHANGED_HIT);
        }
      }
    }

    // Before deciding to build, check the ArtifactCache.
    // The fetched file is now a ZIP file, so it needs to be unzipped.
    CacheResult cacheResult = tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
        buildInfoRecorder,
        context.getArtifactCache(),
        context.getProjectRoot(),
        context);

    // Run the steps to build this rule since it was not found in the cache.
    if (cacheResult.isSuccess()) {
      return new BuildResult(BuildRuleSuccess.Type.FETCHED_FROM_CACHE, cacheResult);
    }

    // The only remaining option is to build locally.
    try {
      executeCommandsNowThatDepsAreBuilt(context, onDiskBuildInfo, buildInfoRecorder);
    } catch (IOException | StepFailedException e) {
      return new BuildResult(e);
    }

    // Given that the Buildable has built successfully, record that the output file has been
    // written, assuming it has one.
    // TODO(mbolin): Buildable.getSteps() should use BuildableContext such that Buildable is
    // responsible for invoking recordArtifact() itself. Once that is done, this call to
    // recordArtifact() should be deleted.
    String pathToOutputFile = buildable.getPathToOutputFile();
    if (pathToOutputFile != null && pathToOutputFile.startsWith(BuckConstant.GEN_DIR)) {
      String prefix = BuckConstant.GEN_DIR + '/' + getBuildTarget().getBasePathWithSlash();
      Path pathToArtifact = Paths.get(pathToOutputFile.substring(prefix.length()));
      buildInfoRecorder.recordArtifact(pathToArtifact);
    }

    return new BuildResult(BuildRuleSuccess.Type.BUILT_LOCALLY, CacheResult.MISS);
  }

  private CacheResult tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
      BuildInfoRecorder buildInfoRecorder,
      ArtifactCache artifactCache,
      Path projectRoot,
      BuildContext buildContext) {
    // Create a temp file whose extension must be ".zip" for Filesystems.newFileSystem() to infer
    // that we are creating a zip-based FileSystem.
    File zipFile;
    try {
      zipFile = File.createTempFile(getFullyQualifiedName().replace('/', '_'), ".zip");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // TODO(mbolin): Change ArtifactCache.fetch() so that it returns a File instead of takes one.
    // Then we could download directly from Cassandra into the on-disk cache and unzip it from
    // there.
    CacheResult cacheResult = buildInfoRecorder.fetchArtifactForBuildable(zipFile, artifactCache);
    if (!cacheResult.isSuccess()) {
      zipFile.delete();
      return cacheResult;
    }

    // We unzip the file in the root of the project directory.
    // Ideally, the following would work:
    //
    // Path pathToZip = Paths.get(zipFile.getAbsolutePath());
    // FileSystem fs = FileSystems.newFileSystem(pathToZip, /* loader */ null);
    // Path root = Iterables.getOnlyElement(fs.getRootDirectories());
    // MoreFiles.copyRecursively(root, projectRoot);
    //
    // Unfortunately, this does not appear to work, in practice, because MoreFiles fails when trying
    // to resolve a Path for a zip entry against a file Path on disk.

    // TODO(user, simons): Make this work on Windows. A custom implementation of unzip in Java
    // needs to be heavily tested to ensure that it works on all platforms in a multithreaded
    // environment. In my testing of the unzip executable, this has not been an issue, so this level
    // of fidelity needs to be maintained.
    ProcessBuilder processBuilder = new ProcessBuilder(
        "unzip", "-o", "-qq", zipFile.getAbsolutePath());
    processBuilder.directory(projectRoot.toFile());

    try {
      ProcessExecutor executor = buildContext.createProcessExecutorForUnzippingArtifact();
      Process process = processBuilder.start();
      ProcessExecutor.Result result = executor.execute(process,
          /* shouldPrintStdOut */ false,
          /* shouldPrintStdErr */ false,
          /* isSilent */ false);
      int exitCode = result.getExitCode();
      if (exitCode != 0) {
        // In the wild, we have seen some inexplicable failures during this step. For now, we try to
        // give the user as much information as we can to debug the issue, but return false so that
        // Buck will fall back on doing a local build.
        buildContext.getEventBus().post(LogEvent.warning(
            "Failed to unzip the artifact for %s at %s.\n" +
            "The rule will be built locally, but here is the output of the failed unzip call:\n" +
            "Exit code: %s\n" +
            "STDOUT:\n%s\n" +
            "STDERR:\n%s\n",
            getBuildTarget(),
            zipFile.getAbsolutePath(),
            exitCode,
            result.getStdout(),
            result.getStderr()));
        return CacheResult.MISS;
      }
    } catch (IOException e) {
      return CacheResult.MISS;
    }

    // We only delete the ZIP file when it has been unzipped successfully. Otherwise, we leave it
    // around for debugging purposes.
    zipFile.delete();
    return cacheResult;
  }

  /**
   * Execute the commands for this build rule. Requires all dependent rules are already built
   * successfully.
   */
  private void executeCommandsNowThatDepsAreBuilt(BuildContext context,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder)
      throws IOException, StepFailedException {
    context.logBuildInfo("[BUILDING %s]", getFullyQualifiedName());

    // Get and run all of the commands.
    BuildableContext buildableContext = new DefaultBuildableContext(onDiskBuildInfo,
        buildInfoRecorder);
    List<Step> steps = buildable.getBuildSteps(context, buildableContext);
    StepRunner stepRunner = context.getStepRunner();
    for (Step step : steps) {
      stepRunner.runStepForBuildTarget(step, getBuildTarget());
    }
  }

  /**
   * For a rule that is read from the build cache, it may have fields that would normally be
   * populated by executing the steps returned by
   * {@link Buildable#getBuildSteps(BuildContext, BuildableContext)}. Because
   * {@link Buildable#getBuildSteps(BuildContext, BuildableContext)} is not invoked for cached rules, a rule
   * may need to implement this method to populate those fields in some other way. For a cached
   * rule, this method will be invoked just before the future returned by
   * {@link #build(BuildContext)} is resolved.
   * @param onDiskBuildInfo can be used to read metadata from disk to help initialize the rule.
   */
  protected void initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {}

  /**
   * This is a union type that represents either a success or a failure. This exists so that
   * {@link #buildOnceDepsAreBuilt(BuildContext, OnDiskBuildInfo, BuildInfoRecorder)} can return a
   * strongly typed value.
   */
  private static class BuildResult {

    private final BuildRuleStatus status;
    private final CacheResult cacheResult;

    @Nullable private final BuildRuleSuccess.Type success;
    @Nullable private final Throwable failure;

    BuildResult(BuildRuleSuccess.Type success, CacheResult cacheResult) {
      this.status = BuildRuleStatus.SUCCESS;
      this.cacheResult = Preconditions.checkNotNull(cacheResult);
      this.success = Preconditions.checkNotNull(success);
      this.failure = null;
    }

    BuildResult(Throwable failure) {
      this.status = BuildRuleStatus.FAIL;
      this.cacheResult = CacheResult.MISS;
      this.success = null;
      this.failure = Preconditions.checkNotNull(failure);
    }

    boolean isSuccess() {
      return status == BuildRuleStatus.SUCCESS;
    }
  }
}
