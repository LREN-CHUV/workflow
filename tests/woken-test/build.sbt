import sbt.ExclusionRule
import sbtassembly.MergeStrategy

// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `woken-test` =
  project
    .in(file("."))
    .enablePlugins(AutomateHeaderPlugin)
    .settings(settings)
    .settings(
      Seq(
        libraryDependencies ++= Seq(
          library.akkaActor,
          library.akkaRemote,
          library.akkaCluster,
          library.akkaClusterTools,
          library.akkaSlf4j,
          library.akkaHttp,
          library.akkaHttpJson,
          library.sprayJson,
          library.kamon,
          library.kamonAkka,
          library.kamonAkkaHttp,
          library.kamonAkkaRemote,
          library.kamonPrometheus,
          library.kamonZipkin,
          library.kamonSystemMetrics,
          library.kamonSigar,
          library.wokenMessages,
          library.scalaCheck   % Test,
          library.scalaTest    % Test,
          library.akkaTestkit  % Test
        ),
        includeFilter in (Test, unmanagedResources) := "*.json" || "*.xml" || "*.conf",
        fork in Test := false,
        parallelExecution in Test := false
      )
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      val scalaCheck      = "1.14.0"
      val scalaTest       = "3.0.7"
      val akka            = "2.5.22"
      val akkaHttp        = "10.1.8"
      val kamon           = "1.1.6"
      val kamonAkka       = "1.1.3"
      val kamonAkkaRemote = "1.1.0"
      val kamonAkkaHttp   = "1.1.1"
      val kamonPrometheus = "1.1.1"
      val kamonReporter   = "1.0.0"
      val kamonSystemMetrics = "2.2.2"
      val kamonSigar      = "1.6.6-rev002"
      val sprayJson       = "1.3.5"
      val config          = "1.3.3"
      val wokenMessages   = "3.0.14"
    }
    object ExclusionRules {
      val excludeLogback = ExclusionRule(organization = "ch.qos.logback", name = "logback-classic")
    }
    val scalaCheck: ModuleID   = "org.scalacheck"    %% "scalacheck"   % Version.scalaCheck
    val scalaTest: ModuleID    = "org.scalatest"     %% "scalatest"    % Version.scalaTest
    val akkaActor: ModuleID    = "com.typesafe.akka" %% "akka-actor"   % Version.akka
    val akkaRemote: ModuleID   = "com.typesafe.akka" %% "akka-remote"  % Version.akka
    val akkaCluster: ModuleID  = "com.typesafe.akka" %% "akka-cluster" % Version.akka
    val akkaClusterTools: ModuleID = "com.typesafe.akka" %% "akka-cluster-tools" % Version.akka
    val akkaSlf4j: ModuleID    = "com.typesafe.akka" %% "akka-slf4j"   % Version.akka
    val akkaTestkit: ModuleID  = "com.typesafe.akka" %% "akka-testkit" % Version.akka
    val akkaHttp: ModuleID     = "com.typesafe.akka" %% "akka-http" % Version.akkaHttp
    val akkaHttpJson: ModuleID = "com.typesafe.akka" %% "akka-http-spray-json" % Version.akkaHttp

    // Kamon
    val kamon: ModuleID        = "io.kamon" %% "kamon-core" % Version.kamon excludeAll ExclusionRules.excludeLogback
    val kamonAkka: ModuleID    = "io.kamon" %% "kamon-akka-2.5" % Version.kamonAkka excludeAll ExclusionRules.excludeLogback
    val kamonAkkaRemote: ModuleID = "io.kamon" %% "kamon-akka-remote-2.5" % Version.kamonAkkaRemote excludeAll ExclusionRules.excludeLogback
    val kamonAkkaHttp: ModuleID = "io.kamon" %% "kamon-akka-http-2.5" % Version.kamonAkkaHttp excludeAll ExclusionRules.excludeLogback
    val kamonSystemMetrics: ModuleID = "io.kamon" %% "kamon-system-metrics" % Version.kamonSystemMetrics excludeAll ExclusionRules.excludeLogback
    val kamonPrometheus: ModuleID = "io.kamon" %% "kamon-prometheus" % Version.kamonPrometheus excludeAll ExclusionRules.excludeLogback
    val kamonZipkin: ModuleID  =  "io.kamon" %% "kamon-zipkin" % Version.kamonReporter excludeAll ExclusionRules.excludeLogback
    val kamonSigar: ModuleID   = "io.kamon"           % "sigar-loader" % Version.kamonSigar

    val sprayJson: ModuleID    = "io.spray"          %% "spray-json"   % Version.sprayJson
    val config: ModuleID       = "com.typesafe"       % "config"       % Version.config
    val wokenMessages: ModuleID = "ch.chuv.lren.woken" %% "woken-messages" % Version.wokenMessages
  }

resolvers += "HBPMedical Bintray Repo" at "https://dl.bintray.com/hbpmedical/maven/"
resolvers += "opendatagroup maven" at "http://repository.opendatagroup.com/maven"

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings = commonSettings ++ scalafmtSettings

lazy val commonSettings =
  Seq(
    scalaVersion := "2.11.12",
    organization in ThisBuild := "ch.chuv.lren.woken",
    organizationName in ThisBuild := "LREN CHUV for Human Brain Project",
    homepage in ThisBuild := Some(url(s"https://github.com/HBPMedical/${name.value}/#readme")),
    licenses in ThisBuild := Seq("AGPL-3.0" ->
      url(s"https://github.com/LREN-CHUV/${name.value}/blob/${version.value}/LICENSE")),
    startYear in ThisBuild := Some(2017),
    description in ThisBuild := "Woken - integration tests",
    developers in ThisBuild := List(
      Developer("ludovicc", "Ludovic Claude", "@ludovicc", url("https://github.com/ludovicc"))
    ),
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      //"-Xlint", -- disabled due to Scala bug, waiting for 2.12.5
      "-Yno-adapted-args",
      //"-Ywarn-dead-code", -- disabled due to Scala bug, waiting for 2.12.5
      //"-Ywarn-value-discard", -- disabled due to Scala bug, waiting for 2.12.5
      "-Ypartial-unification",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8"
    ),
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
    unmanagedSourceDirectories.in(Compile) := Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value),
    wartremoverWarnings in (Compile, compile) ++= Warts.unsafe,
    fork in run := true,
    test in assembly := {},
    fork in Test := false,
    parallelExecution in Test := false
  )

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := true,
    scalafmtOnCompile.in(Sbt) := false,
    scalafmtVersion := "1.5.1"
  )

// Create a new MergeStrategy for aop.xml files
val aopMerge: MergeStrategy = new MergeStrategy {
  val name = "aopMerge"
  import scala.xml._
  import scala.xml.dtd._

  def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
    val dt = DocType("aspectj", PublicID("-//AspectJ//DTD//EN", "http://www.eclipse.org/aspectj/dtd/aspectj.dtd"), Nil)
    val file = MergeStrategy.createMergeTarget(tempDir, path)
    val xmls: Seq[Elem] = files.map(XML.loadFile)
    val aspectsChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "aspects" \ "_")
    val weaverChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "weaver" \ "_")
    val options: String = xmls.map(x => (x \\ "aspectj" \ "weaver" \ "@options").text).mkString(" ").trim
    val weaverAttr = if (options.isEmpty) Null else new UnprefixedAttribute("options", options, Null)
    val aspects = new Elem(null, "aspects", Null, TopScope, false, aspectsChildren: _*)
    val weaver = new Elem(null, "weaver", weaverAttr, TopScope, false, weaverChildren: _*)
    val aspectj = new Elem(null, "aspectj", Null, TopScope, false, aspects, weaver)
    XML.save(file.toString, aspectj, "UTF-8", xmlDecl = false, dt)
    IO.append(file, IO.Newline.getBytes(IO.defaultCharset))
    Right(Seq(file -> path))
  }
}

// Use defaultMergeStrategy with a case for aop.xml
// I like this better than the inline version mentioned in assembly's README
val customMergeStrategy: String => MergeStrategy = {
  case PathList("META-INF", "aop.xml") =>
    aopMerge
  case s =>
    MergeStrategy.defaultMergeStrategy(s)
}
