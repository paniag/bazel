// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MiddlemanFactory;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.rules.java.JavaUtil;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Helper class for Android IDL processing.
 */
public class AndroidIdlHelper {

  private final RuleContext ruleContext;
  private final AndroidIdlProvider androidIdlProvider;
  private final Collection<Artifact> idls;
  private final Map<Artifact, Artifact> translatedIdlSources;
  private final Artifact idlClassJar;
  private final Artifact idlSourceJar;

  public AndroidIdlHelper(RuleContext ruleContext, Artifact classJar) {
    this.ruleContext = ruleContext;

    checkIdlRootImport(ruleContext);

    idls = getIdlSrcs(ruleContext);

    if (!idls.isEmpty() && !ruleContext.hasErrors()) {
      translatedIdlSources = generateTranslatedIdlArtifacts(ruleContext, idls);
      idlClassJar = createIdlJar(classJar, "-idl.jar");
      idlSourceJar = createIdlJar(classJar, "-idl.srcjar");
    } else {
      translatedIdlSources = ImmutableMap.of();
      idlClassJar = null;
      idlSourceJar = null;
    }

    androidIdlProvider = createAndroidIdlProvider(
        ruleContext, idlClassJar, idlSourceJar);
  }

  public void addTransitiveInfoProviders(RuleConfiguredTargetBuilder builder,
      Artifact classJar, Artifact manifestProtoOutput) {
    if (!idls.isEmpty()) {
      generateAndroidIdlCompilationActions(
          ruleContext, idls, androidIdlProvider, translatedIdlSources);
      createIdlClassJarAction(ruleContext, classJar, translatedIdlSources.values(),
          manifestProtoOutput, idlClassJar, idlSourceJar);
    }
    builder
        .add(AndroidIdlProvider.class, androidIdlProvider)
        .addOutputGroup(
            AndroidSemantics.IDL_JARS_OUTPUT_GROUP, androidIdlProvider.getTransitiveIdlJars());
  }

  public Collection<Artifact> getIdlSources() {
    return idls;
  }

  public Collection<Artifact> getIdlParcelables() {
    return getIdlParcelables(ruleContext);
  }

  public Collection<Artifact> getIdlGeneratedJavaSources() {
    return translatedIdlSources.values();
  }

  @Nullable
  public Artifact getIdlClassJar() {
    return idlClassJar;
  }

  @Nullable
  public Artifact getIdlSourceJar() {
    return idlSourceJar;
  }

  /**
   * Returns the artifact for a jar file containing class files that were generated by
   * annotation processors.
   */
  private Artifact createIdlJar(Artifact outputJar, String suffix) {
    return ruleContext.getDerivedArtifact(
        FileSystemUtils.replaceExtension(outputJar.getRootRelativePath(), suffix),
        outputJar.getRoot());
  }

  private static ImmutableList<Artifact> getIdlParcelables(RuleContext ruleContext) {
    return ruleContext.getRule().isAttrDefined("idl_parcelables", BuildType.LABEL_LIST)
        ? ImmutableList.copyOf(ruleContext.getPrerequisiteArtifacts(
        "idl_parcelables", Mode.TARGET).filter(AndroidRuleClasses.ANDROID_IDL).list())
        : ImmutableList.<Artifact>of();
  }

  private static Collection<Artifact> getIdlSrcs(RuleContext ruleContext) {
    if (!ruleContext.getRule().isAttrDefined("idl_srcs", BuildType.LABEL_LIST)) {
      return ImmutableList.of();
    }
    checkIdlSrcsSamePackage(ruleContext);
    return ruleContext.getPrerequisiteArtifacts(
        "idl_srcs", Mode.TARGET).filter(AndroidRuleClasses.ANDROID_IDL).list();
  }

  private static void checkIdlSrcsSamePackage(RuleContext ruleContext) {
    PathFragment packageName = ruleContext.getLabel().getPackageFragment();
    Collection<Artifact> idls = ruleContext
        .getPrerequisiteArtifacts("idl_srcs", Mode.TARGET)
        .filter(AndroidRuleClasses.ANDROID_IDL)
        .list();
    for (Artifact idl : idls) {
      Label idlLabel = idl.getOwner();
      if (!packageName.equals(idlLabel.getPackageFragment())) {
        ruleContext.attributeError("idl_srcs", "do not import '" + idlLabel + "' directly. "
            + "You should either move the file to this package or depend on "
            + "an appropriate rule there");
      }
    }
  }

  private static ImmutableMap<Artifact, Artifact> generateTranslatedIdlArtifacts(
      RuleContext ruleContext, Collection<Artifact> idls) {
    ImmutableMap.Builder<Artifact, Artifact> outputJavaSources = ImmutableMap.builder();
    String ruleName = ruleContext.getRule().getName();
    // for each aidl file use aggregated preprocessed files to generate Java code
    for (Artifact idl : idls) {
      // Reconstruct the package tree under <rule>_aidl to avoid a name conflict
      // if the same AIDL files are used in multiple targets.
      PathFragment javaOutputPath = FileSystemUtils.replaceExtension(
          new PathFragment(ruleName + "_aidl").getRelative(idl.getRootRelativePath()),
          ".java");
      Artifact output = ruleContext.getPackageRelativeArtifact(
          javaOutputPath, ruleContext.getConfiguration().getGenfilesDirectory());
      outputJavaSources.put(idl, output);
    }
    return outputJavaSources.build();
  }

  private static void generateAndroidIdlCompilationActions(
      RuleContext ruleContext,
      Collection<Artifact> idls,
      AndroidIdlProvider transitiveIdlImportData,
      Map<Artifact, Artifact> translatedIdlSources) {
    AndroidSdkProvider sdk = AndroidSdkProvider.fromRuleContext(ruleContext);
    Set<Artifact> preprocessedIdls = new LinkedHashSet<>();
    List<String> preprocessedArgs = new ArrayList<>();

    // add imports
    for (String idlImport : transitiveIdlImportData.getTransitiveIdlImportRoots()) {
      preprocessedArgs.add("-I" + idlImport);
    }

    // preprocess each aidl file
    preprocessedArgs.add("-p" + sdk.getFrameworkAidl().getExecPathString());
    String ruleName = ruleContext.getRule().getName();
    for (Artifact idl : idls) {
      // Reconstruct the package tree under <rule>_aidl to avoid a name conflict
      // if the source AIDL files are also generated.
      PathFragment preprocessedPath = new PathFragment(ruleName + "_aidl")
          .getRelative(idl.getRootRelativePath());
      Artifact preprocessed = ruleContext.getPackageRelativeArtifact(
          preprocessedPath, ruleContext.getConfiguration().getGenfilesDirectory());
      preprocessedIdls.add(preprocessed);
      preprocessedArgs.add("-p" + preprocessed.getExecPathString());

      createAndroidIdlPreprocessAction(ruleContext, idl, preprocessed);
    }

    // aggregate all preprocessed aidl files
    MiddlemanFactory middlemanFactory = ruleContext.getAnalysisEnvironment().getMiddlemanFactory();
    Artifact preprocessedIdlsMiddleman = middlemanFactory.createAggregatingMiddleman(
        ruleContext.getActionOwner(), "AndroidIDLMiddleman", preprocessedIdls,
        ruleContext.getConfiguration().getMiddlemanDirectory());

    for (Artifact idl : translatedIdlSources.keySet()) {
      createAndroidIdlAction(ruleContext, idl,
          transitiveIdlImportData.getTransitiveIdlImports(),
          preprocessedIdlsMiddleman, translatedIdlSources.get(idl), preprocessedArgs);
    }
  }

  /**
   * Creates the idl class jar action.
   */
  private static void createIdlClassJarAction(
      RuleContext ruleContext,
      Artifact classJar,
      Iterable<Artifact> generatedIdlJavaFiles,
      Artifact manifestProtoOutput,
      Artifact idlClassJar,
      Artifact idlSourceJar) {
    ruleContext.registerAction(new SpawnAction.Builder()
        .addInput(manifestProtoOutput)
        .addInput(classJar)
        .addInputs(generatedIdlJavaFiles)
        .addOutput(idlClassJar)
        .addOutput(idlSourceJar)
        .setExecutable(ruleContext.getExecutablePrerequisite("$idlclass", Mode.HOST))
        .setCommandLine(CustomCommandLine.builder()
            .addExecPath("--manifest_proto", manifestProtoOutput)
            .addExecPath("--class_jar", classJar)
            .addExecPath("--output_class_jar", idlClassJar)
            .addExecPath("--output_source_jar", idlSourceJar)
            .add("--temp_dir").addPath(idlTempDir(ruleContext, idlClassJar))
            .addExecPaths(generatedIdlJavaFiles)
            .build())
        .useParameterFile(ParameterFileType.SHELL_QUOTED)
        .setProgressMessage("Building idl jars " + idlClassJar.prettyPrint())
        .setMnemonic("AndroidIdlJars")
        .build(ruleContext));
  }

  private static PathFragment idlTempDir(RuleContext ruleContext, Artifact outputJar) {
    String basename = FileSystemUtils.removeExtension(outputJar.getExecPath().getBaseName());
    return ruleContext.getConfiguration().getBinDirectory().getExecPath()
        .getRelative(ruleContext.getUniqueDirectory("_idl"))
        .getRelative(basename + "_temp");
  }

  private static void createAndroidIdlPreprocessAction(RuleContext ruleContext,
      Artifact idl, Artifact preprocessed) {
    AndroidSdkProvider sdk = AndroidSdkProvider.fromRuleContext(ruleContext);
    ruleContext.registerAction(new SpawnAction.Builder()
        .setExecutable(sdk.getAidl())
        // Note the below may be an overapproximation of the actual runfiles, due to "conditional
        // artifacts" (see Runfiles.PruningManifest).
        // TODO(bazel-team): When using getFilesToRun(), the middleman is
        // not expanded. Fix by providing code to expand and use getFilesToRun here.
        .addInput(idl)
        .addOutput(preprocessed)
        .addArgument("--preprocess")
        .addArgument(preprocessed.getExecPathString())
        .addArgument(idl.getExecPathString())
        .setProgressMessage("Android IDL preprocessing")
        .setMnemonic("AndroidIDLPreprocess")
        .build(ruleContext));
  }

  private static void createAndroidIdlAction(RuleContext ruleContext,
      Artifact idl, Iterable<Artifact> idlImports, Artifact preprocessedIdls,
      Artifact output, List<String> preprocessedArgs) {
    AndroidSdkProvider sdk = AndroidSdkProvider.fromRuleContext(ruleContext);
    ruleContext.registerAction(new SpawnAction.Builder()
        .setExecutable(sdk.getAidl())
        .addInput(idl)
        .addInputs(idlImports)
        .addInput(preprocessedIdls)
        .addInput(sdk.getFrameworkAidl())
        .addOutput(output)
        .addArgument("-b") // Fail if trying to compile a parcelable.
        .addArguments(preprocessedArgs)
        .addArgument(idl.getExecPathString())
        .addArgument(output.getExecPathString())
        .setProgressMessage("Android IDL generation")
        .setMnemonic("AndroidIDLGnerate")
        .build(ruleContext));
  }

  /**
   * Returns the union of "idl_srcs" and "idl_parcelables", i.e. all .aidl files
   * provided by this library that contribute to .aidl --> .java compilation.
   */
  private static Collection<Artifact> getIdlImports(RuleContext ruleContext) {
    return ImmutableList.<Artifact>builder()
        .addAll(getIdlParcelables(ruleContext))
        .addAll(getIdlSrcs(ruleContext))
        .build();
  }

  private static AndroidIdlProvider createAndroidIdlProvider(RuleContext ruleContext,
      @Nullable Artifact idlClassJar, @Nullable Artifact idlSourceJar) {
    NestedSetBuilder<String> rootsBuilder = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> importsBuilder = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> jarsBuilder = NestedSetBuilder.stableOrder();
    if (idlClassJar != null) {
      jarsBuilder.add(idlClassJar);
    }
    if (idlSourceJar != null) {
      jarsBuilder.add(idlSourceJar);
    }

    for (AndroidIdlProvider dep : ruleContext.getPrerequisites(
        "deps", Mode.TARGET, AndroidIdlProvider.class)) {
      rootsBuilder.addTransitive(dep.getTransitiveIdlImportRoots());
      importsBuilder.addTransitive(dep.getTransitiveIdlImports());
      jarsBuilder.addTransitive(dep.getTransitiveIdlJars());
    }

    Collection<Artifact> idlImports = getIdlImports(ruleContext);
    if (!hasExplicitlySpecifiedIdlImportRoot(ruleContext)) {
      for (Artifact idlImport : idlImports) {
        PathFragment javaRoot = JavaUtil.getJavaRoot(idlImport.getExecPath());
        if (javaRoot == null) {
          ruleContext.ruleError("Cannot determine java/javatests root for import "
              + idlImport.getExecPathString());
        } else {
          rootsBuilder.add(javaRoot.toString());
        }
      }
    } else {
      PathFragment pkgFragment = ruleContext.getLabel().getPackageFragment();
      Set<PathFragment> idlImportRoots = new HashSet<>();
      for (Artifact idlImport : idlImports) {
        idlImportRoots.add(idlImport.getRoot().getExecPath()
            .getRelative(pkgFragment)
            .getRelative(getIdlImportRoot(ruleContext)));
      }
      for (PathFragment idlImportRoot : idlImportRoots) {
        rootsBuilder.add(idlImportRoot.toString());
      }
    }
    importsBuilder.addAll(idlImports);

    return new AndroidIdlProvider(rootsBuilder.build(),
        importsBuilder.build(), jarsBuilder.build());
  }

  private static void checkIdlRootImport(RuleContext ruleContext) {
    if (hasExplicitlySpecifiedIdlImportRoot(ruleContext)
        && !hasExplicitlySpecifiedIdlSrcsOrParcelables(ruleContext)) {
      ruleContext.attributeError("idl_import_root",
          "Neither idl_srcs nor idl_parcelables were specified, "
              + "but 'idl_import_root' attribute was set");
    }
  }

  private static boolean hasExplicitlySpecifiedIdlImportRoot(RuleContext ruleContext) {
    return ruleContext.getRule().isAttributeValueExplicitlySpecified("idl_import_root");
  }

  private static boolean hasExplicitlySpecifiedIdlSrcsOrParcelables(RuleContext ruleContext) {
    return ruleContext.getRule().isAttributeValueExplicitlySpecified("idl_srcs")
        || ruleContext.getRule().isAttributeValueExplicitlySpecified("idl_parcelables");
  }

  private static String getIdlImportRoot(RuleContext ruleContext) {
    return ruleContext.attributes().get("idl_import_root", Type.STRING);
  }
}