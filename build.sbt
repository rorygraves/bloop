import build.BuildImplementation.BuildDefaults

// Tell bloop to aggregate source deps (benchmark) config files in the same bloop config dir
bloopAggregateSourceDependencies in Global := true

bloopExportJarClassifiers in ThisBuild := Some(Set("sources"))

/***************************************************************************************************/
/*                      This is the build definition of the source deps                            */
/***************************************************************************************************/
val benchmarkBridge = project
  .in(file(".benchmark-bridge-compilation"))
  .aggregate(BenchmarkBridgeCompilation)
  .disablePlugins(ScriptedPlugin)
  .settings(
    releaseEarly := { () },
    skip in publish := true,
    bloopGenerate in Compile := None,
    bloopGenerate in Test := None,
  )

/***************************************************************************************************/
/*                            This is the build definition of the wrapper                          */
/***************************************************************************************************/
import build.Dependencies

val backend = project
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-backend",
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := BloopBackendInfoKeys,
    buildInfoObject := "BloopScalaInfo",
    libraryDependencies ++= List(
      Dependencies.zinc,
      Dependencies.nailgun,
      Dependencies.scalazCore,
      Dependencies.scalazConcurrent,
      Dependencies.coursier,
      Dependencies.coursierCache,
      Dependencies.coursierScalaz,
      Dependencies.libraryManagement,
      Dependencies.configDirectories,
      Dependencies.sourcecode,
      Dependencies.sbtTestInterface,
      Dependencies.sbtTestAgent,
      Dependencies.monix,
      Dependencies.directoryWatcher
    )
  )

val jsonConfig210 = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-config",
    target := (file("config") / "target" / "json-config-2.10").getAbsoluteFile,
    scalaVersion := Scala210Version,
    // We compile in both so that the maven integration can be tested locally
    publishLocal := publishLocal.dependsOn(publishM2).value,
    libraryDependencies ++= {
      List(
        Dependencies.circeParser,
        Dependencies.circeCore,
        Dependencies.circeGeneric,
        compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
        Dependencies.scalacheck % Test,
      )
    }
  )

// Needs to be called `jsonConfig` because of naming conflict with sbt universe...
val jsonConfig212 = project
  .in(file("config"))
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-config",
    target := (file("config") / "target" / "json-config-2.12").getAbsoluteFile,
    scalaVersion := Keys.scalaVersion.in(backend).value,
    // We compile in both so that the maven integration can be tested locally
    publishLocal := publishLocal.dependsOn(publishM2).value,
    libraryDependencies ++= {
      List(
        Dependencies.circeParser,
        Dependencies.circeDerivation,
        Dependencies.scalacheck % Test,
      )
    }
  )

import build.BuildImplementation.jvmOptions
// For the moment, the dependency is fixed
lazy val frontend: Project = project
  .dependsOn(backend, backend % "test->test", jsonConfig212)
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(testSettings, assemblySettings, releaseSettings, integrationTestSettings)
  .settings(
    name := s"bloop-frontend",
    mainClass in Compile in run := Some("bloop.Cli"),
    buildInfoPackage := "bloop.internal.build",
    buildInfoKeys := bloopInfoKeys(nativeBridge, jsBridge06, jsBridge10),
    javaOptions in run ++= jvmOptions,
    javaOptions in Test ++= jvmOptions,
    libraryDependencies += Dependencies.graphviz % Test,
    fork in run := true,
    fork in Test := true,
    parallelExecution in test := false,
    libraryDependencies ++= List(
      Dependencies.scalazCore,
      Dependencies.bsp,
      Dependencies.monix,
      Dependencies.caseApp,
      Dependencies.nuprocess,
      Dependencies.ipcsocket % Test
    ),
    dependencyOverrides += Dependencies.shapeless
  )

val benchmarks = project
  .dependsOn(frontend % "compile->test", BenchmarkBridgeCompilation % "compile->jmh")
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(BuildInfoPlugin, JmhPlugin)
  .settings(benchmarksSettings(frontend))
  .settings(
    skip in publish := true,
  )

val integrations = file("integrations")

lazy val sbtBloop10 = project
  .enablePlugins(ScriptedPlugin)
  .in(integrations / "sbt-bloop")
  .settings(BuildDefaults.scriptedSettings)
  .settings(sbtPluginSettings("1.1.4", jsonConfig212))
  .dependsOn(jsonConfig212)

// Let's remove scripted for 0.13, we only test 1.0
lazy val sbtBloop013 = project
  .disablePlugins(ScriptedPlugin)
  .in(integrations / "sbt-bloop")
  .settings(scalaVersion := Scala210Version)
  .settings(sbtPluginSettings("0.13.17", jsonConfig210))
  .dependsOn(jsonConfig210)

val mavenBloop = project
  .in(integrations / "maven-bloop")
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig210)
  .settings(name := "maven-bloop", scalaVersion := Scala210Version)
  .settings(BuildDefaults.mavenPluginBuildSettings)

val gradleBloop = project
  .in(file("integrations") / "gradle-bloop")
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig212)
  .settings(name := "gradle-bloop")
  .settings(BuildDefaults.gradlePluginBuildSettings)

val millBloop = project
  .in(integrations / "mill-bloop")
  .disablePlugins(ScriptedPlugin)
  .dependsOn(jsonConfig212)
  .settings(name := "mill-bloop")
  .settings(BuildDefaults.millModuleBuildSettings)

val docs = project
  .in(file("website"))
  .enablePlugins(HugoPlugin, GhpagesPlugin, ScriptedPlugin)
  .settings(
    name := "bloop-website",
    skip in publish := true,
    websiteSettings
  )

lazy val jsBridge06 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-0.6")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-js-bridge-0.6",
    libraryDependencies += Dependencies.scalaJsTools06
  )

lazy val jsBridge10 = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scalajs-1.0")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-js-bridge-1.0",
    libraryDependencies ++= List(
      Dependencies.scalaJsLinker10,
      Dependencies.scalaJsLogging10,
      Dependencies.scalaJsIO10,
    )
  )

lazy val nativeBridge = project
  .dependsOn(frontend % Provided, frontend % "test->test")
  .in(file("bridges") / "scala-native")
  .disablePlugins(ScriptedPlugin)
  .settings(testSettings)
  .settings(
    name := "bloop-native-bridge",
    libraryDependencies += Dependencies.scalaNativeTools,
    javaOptions in Test ++= jvmOptions,
    fork in Test := true,
  )

val allProjects =
  Seq(backend, benchmarks, frontend, jsonConfig210, jsonConfig212, sbtBloop013, sbtBloop10, mavenBloop, gradleBloop, millBloop, nativeBridge, jsBridge06, jsBridge10)
val allProjectReferences = allProjects.map(p => LocalProject(p.id))
val bloop = project
  .in(file("."))
  .disablePlugins(ScriptedPlugin)
  .aggregate(allProjectReferences: _*)
  .settings(
    releaseEarly := { () },
    skip in publish := true,
    crossSbtVersions := Seq("1.1.0", "0.13.16")
  )

/***************************************************************************************************/
/*                      This is the corner for all the command definitions                         */
/***************************************************************************************************/
val publishLocalCmd = Keys.publishLocal.key.label

// Runs the scripted tests to setup integration tests
// ! This is used by the benchmarks too !
addCommandAlias(
  "install",
  Seq(
    s"${jsonConfig210.id}/$publishLocalCmd",
    s"${jsonConfig212.id}/$publishLocalCmd",
    s"${sbtBloop013.id}/$publishLocalCmd",
    s"${sbtBloop10.id}/$publishLocalCmd",
    s"${mavenBloop.id}/$publishLocalCmd",
    s"${gradleBloop.id}/$publishLocalCmd",
    s"${backend.id}/$publishLocalCmd",
    s"${frontend.id}/$publishLocalCmd",
    s"${nativeBridge.id}/$publishLocalCmd",
    s"${jsBridge06.id}/$publishLocalCmd",
    s"${jsBridge10.id}/$publishLocalCmd"
  ).mkString(";", ";", "")
)

val releaseEarlyCmd = releaseEarly.key.label

val allBloopReleases = List(
  s"${backend.id}/$releaseEarlyCmd",
  s"${frontend.id}/$releaseEarlyCmd",
  s"${jsonConfig210.id}/$releaseEarlyCmd",
  s"${jsonConfig212.id}/$releaseEarlyCmd",
  s"${sbtBloop013.id}/$releaseEarlyCmd",
  s"${sbtBloop10.id}/$releaseEarlyCmd",
  s"${mavenBloop.id}/$releaseEarlyCmd",
  s"${gradleBloop.id}/$releaseEarlyCmd",
  s"${millBloop.id}/$releaseEarlyCmd",
  s"${nativeBridge.id}/$releaseEarlyCmd",
  s"${jsBridge06.id}/$releaseEarlyCmd",
  s"${jsBridge10.id}/$releaseEarlyCmd"
)

val allReleaseActions = allBloopReleases ++ List("sonatypeReleaseAll")
addCommandAlias("releaseBloop", allReleaseActions.mkString(";", ";", ""))
