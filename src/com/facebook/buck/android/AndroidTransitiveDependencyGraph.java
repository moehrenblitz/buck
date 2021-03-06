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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.PrebuiltJarRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;

public class AndroidTransitiveDependencyGraph {

  private final Supplier<ImmutableSortedSet<BuildRule>> rulesToTraverseForTransitiveDeps;
  private final BuildTarget buildTargetForTheRootOfTheTraversal;

  public AndroidTransitiveDependencyGraph(final AndroidBinaryRule buildRule) {
    // Because AndroidBinaryRule#getDeps() includes items such as its keystore, we use
    // AndroidBinaryRule#getClasspathDeps() instead to ensure we only traverse the rules that
    // the user specified as the "deps" argument in the build file.
    this(buildRule.getClasspathDeps(), buildRule.getBuildTarget());
  }

  /**
   * As we move towards a world designed around {@link Buildable}s rather than {@link BuildRule}s,
   * we will likely need to specify the {@link BuildTarget} independently.
   */
  private AndroidTransitiveDependencyGraph(ImmutableSortedSet<BuildRule> deps,
      BuildTarget buildTarget) {
    this.rulesToTraverseForTransitiveDeps = Suppliers.ofInstance(Preconditions.checkNotNull(deps));
    this.buildTargetForTheRootOfTheTraversal = Preconditions.checkNotNull(buildTarget);
  }

  public static AndroidTransitiveDependencyGraph createForAndroidManifest(
      AndroidManifest androidManifest, DependencyGraph graph) {
    BuildTarget buildTarget = androidManifest.getBuildTarget();
    BuildRule rule = graph.findBuildRuleByTarget(buildTarget);
    return new AndroidTransitiveDependencyGraph(rule.getDeps(), buildTarget);
  }

  public AndroidDexTransitiveDependencies findDexDependencies(
      ImmutableList<HasAndroidResourceDeps> androidResourceDeps,
      ImmutableSet<BuildRule> buildRulesToExcludeFromDex) {
    // These are paths that will be dex'ed. They may be either directories of compiled .class files,
    // or paths to compiled JAR files.
    final ImmutableSet.Builder<String> pathsToDexBuilder = ImmutableSet.builder();

    // Paths to the classfiles to not dex.
    final ImmutableSet.Builder<String> noDxPathsBuilder = ImmutableSet.builder();

    // These are paths to third-party jars that may contain resources that must be included in the
    // final APK.
    final ImmutableSet.Builder<String> pathsToThirdPartyJarsBuilder = ImmutableSet.builder();

    UberRDotJavaUtil.AndroidResourceDetails details =
        createAndroidResourceDetails(androidResourceDeps);

    // Update pathsToDex.
    ImmutableSet<Map.Entry<JavaLibraryRule, String>> classpath =
        Classpaths.getClasspathEntries(rulesToTraverseForTransitiveDeps.get()).entries();
    for (Map.Entry<JavaLibraryRule, String> entry : classpath) {
      if (!buildRulesToExcludeFromDex.contains(entry.getKey())) {
        pathsToDexBuilder.add(entry.getValue());
      } else {
        noDxPathsBuilder.add(entry.getValue());
      }
    }

    // Visit all of the transitive dependencies to populate the above collections.
    new AbstractDependencyVisitor(rulesToTraverseForTransitiveDeps.get()) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        // We need to include the transitive closure of the compiled .class files when dex'ing, as
        // well as the third-party jars that they depend on.
        // Update pathsToThirdPartyJars.
        if (rule instanceof PrebuiltJarRule) {
          PrebuiltJarRule prebuiltJarRule = (PrebuiltJarRule) rule;
          pathsToThirdPartyJarsBuilder.add(prebuiltJarRule.getBinaryJar());
        }
        return maybeVisitAllDeps(rule, rule.getProperties().is(LIBRARY));
      }
    }.start();

    // Include the directory of compiled R.java files on the classpath.
    ImmutableSet<String> rDotJavaPackages = details.rDotJavaPackages;
    if (!rDotJavaPackages.isEmpty()) {
      pathsToDexBuilder.add(UberRDotJavaUtil.getPathToCompiledRDotJavaFiles(buildTargetForTheRootOfTheTraversal));
    }

    ImmutableSet<String> noDxPaths = noDxPathsBuilder.build();

    // Filter out the classpath entries to exclude from dex'ing, if appropriate
    Set<String> classpathEntries = Sets.difference(pathsToDexBuilder.build(), noDxPaths);
    // Classpath entries that should be excluded from dexing should also be excluded from
    // pathsToThirdPartyJars because their resources should not end up in main APK. If they do,
    // the pre-dexed library may try to load a resource from the main APK rather than from within
    // the pre-dexed library (even though the resource is available in both locations). This
    // causes a significant performance regression, as the resource may take more than one second
    // longer to load.
    Set<String> pathsToThirdPartyJars =
        Sets.difference(pathsToThirdPartyJarsBuilder.build(), noDxPaths);

    return new AndroidDexTransitiveDependencies(classpathEntries,
        pathsToThirdPartyJars,
        noDxPaths);
  }

  public AndroidTransitiveDependencies findDependencies(
      ImmutableList<HasAndroidResourceDeps> androidResourceDeps) {

    // Paths to assets/ directories that should be included in the final APK.
    final ImmutableSet.Builder<String> assetsDirectories = ImmutableSet.builder();

    // Paths to native libs directories (often named libs/) that should be included as raw files
    // directories in the final APK.
    final ImmutableSet.Builder<String> nativeLibsDirectories = ImmutableSet.builder();

    // Paths to native libs directories that are to be treated as assets and so should be included
    // as raw files under /assets/lib/ directory in the APK.
    final ImmutableSet.Builder<String> nativeLibAssetsDirectories = ImmutableSet.builder();

    // Path to the module's manifest file
    final ImmutableSet.Builder<String> manifestFiles = ImmutableSet.builder();

    // Path to the module's proguard_config
    final ImmutableSet.Builder<String> proguardConfigs = ImmutableSet.builder();

    UberRDotJavaUtil.AndroidResourceDetails details =
        createAndroidResourceDetails(androidResourceDeps);

    // Visit all of the transitive dependencies to populate the above collections.
    new AbstractDependencyVisitor(rulesToTraverseForTransitiveDeps.get()) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        // We need to include the transitive closure of the compiled .class files when dex'ing, as
        // well as the third-party jars that they depend on.
        // Update pathsToThirdPartyJars.
        if (rule.getBuildable() instanceof NativeLibraryBuildable) {
          NativeLibraryBuildable nativeLibraryRule = (NativeLibraryBuildable) rule.getBuildable();
          if (nativeLibraryRule.isAsset()) {
            nativeLibAssetsDirectories.add(nativeLibraryRule.getLibraryPath());
          } else {
            nativeLibsDirectories.add(nativeLibraryRule.getLibraryPath());
          }

          // In the rare event that a PrebuiltNativeLibraryBuildRule has deps, it is likely another
          // NativeLibraryRule that will need to be included in the final APK, so traverse the deps.
          if (rule.getBuildable() instanceof PrebuiltNativeLibrary) {
            return rule.getDeps();
          }
        } else if (rule instanceof AndroidResourceRule) {
          AndroidResourceRule androidRule = (AndroidResourceRule) rule;
          String assetsDirectory = androidRule.getAssets();
          if (assetsDirectory != null) {
            assetsDirectories.add(assetsDirectory);
          }
          String manifestFile = androidRule.getManifestFile();
          if (manifestFile != null) {
            manifestFiles.add(manifestFile);
          }
        } else if (rule instanceof DefaultJavaLibraryRule) {
          DefaultJavaLibraryRule defaultJavaLibraryRule = (DefaultJavaLibraryRule)rule;
          Optionals.addIfPresent(defaultJavaLibraryRule.getProguardConfig(), proguardConfigs);

          if (rule instanceof AndroidLibraryRule) {
            AndroidLibraryRule androidLibraryRule = (AndroidLibraryRule)rule;
            Optionals.addIfPresent(androidLibraryRule.getManifestFile(), manifestFiles);
          }
        }
        return maybeVisitAllDeps(rule, rule.getProperties().is(LIBRARY));
      }
    }.start();

    return new AndroidTransitiveDependencies(assetsDirectories.build(),
        nativeLibsDirectories.build(),
        nativeLibAssetsDirectories.build(),
        manifestFiles.build(),
        details.resDirectories,
        details.rDotJavaPackages,
        proguardConfigs.build());
  }

  private UberRDotJavaUtil.AndroidResourceDetails createAndroidResourceDetails(
      ImmutableList<HasAndroidResourceDeps> androidResourceDeps) {
    // This is not part of the AbstractDependencyVisitor traversal because
    // AndroidResourceRule.getAndroidResourceDeps() does a topological sort whereas
    // AbstractDependencyVisitor does only a breadth-first search.
    return new UberRDotJavaUtil.AndroidResourceDetails(androidResourceDeps);
  }
}
