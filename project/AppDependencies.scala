import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  private lazy val bootstrapVersion    = "10.7.0"
  private lazy val hmrcMongoVersion    = "2.13.0"
  private lazy val commonDomainVersion = "1.4.0"
  private lazy val appDomainVersion    = "1.6.0"
  private lazy val orgDomainVersion    = "1.9.0"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-backend-play-30"                  % bootstrapVersion,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-play-30"                         % hmrcMongoVersion,
    "org.typelevel"               %% "cats-core"                                  % "2.13.0",
    "commons-codec"               %  "commons-codec"                              % "1.16.0",
    "uk.gov.hmrc"                 %% "api-platform-common-domain"                 % commonDomainVersion,
    "uk.gov.hmrc"                 %% "api-platform-application-domain"            % appDomainVersion,
    "uk.gov.hmrc"                 %% "api-platform-organisation-domain"           % orgDomainVersion,
    "com.github.pureconfig"       %% "pureconfig"                                 % "0.17.7"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-test-play-30"                     % bootstrapVersion,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-test-play-30"                    % hmrcMongoVersion,
    "uk.gov.hmrc"                 %% "api-platform-common-domain-fixtures"        % commonDomainVersion,
    "uk.gov.hmrc"                 %% "api-platform-application-domain-fixtures"   % appDomainVersion,
    "uk.gov.hmrc"                 %% "api-platform-organisation-domain-fixtures"  % orgDomainVersion
  )
  .map(_ % "test")
}
