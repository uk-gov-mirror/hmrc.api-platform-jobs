/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apiplatformjobs.connectors

import java.net.URLEncoder.encode
import java.util.UUID

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import play.api.http.Status
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.{ApplicationResponse, PaginatedApplicationLastUseResponse, ThirdPartyApplicationConnectorConfig, toDomain}
import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

class ThirdPartyApplicationConnectorModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ThirdPartyApplicationConnector]).annotatedWith(Names.named("tpa-production")).to(classOf[ProductionThirdPartyApplicationConnector])
    bind(classOf[ThirdPartyApplicationConnector]).annotatedWith(Names.named("tpa-sandbox")).to(classOf[SandboxThirdPartyApplicationConnector])
  }
}

abstract class ThirdPartyApplicationConnector(implicit val ec: ExecutionContext) {
  val ISODateFormatter: DateTimeFormatter = ISODateTimeFormat.dateTime()

  protected val httpClient: HttpClient
  protected val proxiedHttpClient: ProxiedHttpClient
  val serviceBaseUrl: String
  val useProxy: Boolean
  val bearerToken: String
  val apiKey: String

  def http: HttpClient = if (useProxy) proxiedHttpClient.withHeaders(bearerToken, apiKey) else httpClient

  def fetchApplicationsByEmail(email: String)(implicit hc: HeaderCarrier): Future[Seq[String]] = {
    http.GET[Seq[ApplicationResponse]](s"$serviceBaseUrl/developer/applications", Seq("emailAddress" -> email)).map(_.map(_.id))
  }

  def removeCollaborator(applicationId: String, emailAddress: String)(implicit hc: HeaderCarrier): Future[Int] = {
    http.DELETE[HttpResponse](s"$serviceBaseUrl/application/$applicationId/collaborator/${urlEncode(emailAddress)}?notifyCollaborator=false&adminsToEmail=")
      .map(_.status)
  }

  def applicationsLastUsedBefore(lastUseDate: DateTime): Future[List[ApplicationUsageDetails]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    http.GET[PaginatedApplicationLastUseResponse](
      url = s"$serviceBaseUrl/applications",
      queryParams = Seq("lastUseBefore" -> ISODateFormatter.withZoneUTC().print(lastUseDate)))
      .map(page => toDomain(page.applications))
  }

  def deleteApplication(applicationId: UUID): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    http.POSTEmpty(s"$serviceBaseUrl/application/${applicationId.toString}/delete")
      .map(_.status == Status.NO_CONTENT)
  }

  private def urlEncode(str: String, encoding: String = "UTF-8"): String = encode(str, encoding)
}

object ThirdPartyApplicationConnector {
  def toDomain(applications: List[ApplicationLastUseDate]): List[ApplicationUsageDetails] =
    applications.map(app => {
      val admins =
        app.collaborators
          .filter(_.role == "ADMINISTRATOR")
          .map(_.emailAddress)

      ApplicationUsageDetails(app.id, app.name, admins, app.createdOn, app.lastAccess)
    })


  private[connectors] case class ApplicationResponse(id: String)
  private[connectors] case class Collaborator(emailAddress: String, role: String)
  private[connectors] case class ApplicationLastUseDate(id: UUID,
                                                        name: String,
                                                        collaborators: Set[Collaborator],
                                                        createdOn: DateTime,
                                                        lastAccess: Option[DateTime])
  private[connectors] case class PaginatedApplicationLastUseResponse(applications: List[ApplicationLastUseDate],
                                                                     page: Int,
                                                                     pageSize: Int,
                                                                     total: Int,
                                                                     matching: Int)

  case class ThirdPartyApplicationConnectorConfig(
    applicationSandboxBaseUrl: String, applicationSandboxUseProxy: Boolean, applicationSandboxBearerToken: String, applicationSandboxApiKey: String,
    applicationProductionBaseUrl: String, applicationProductionUseProxy: Boolean, applicationProductionBearerToken: String, applicationProductionApiKey: String
  )

  object JsonFormatters {
    import org.joda.time.DateTime
    import play.api.libs.json.JodaWrites._
    import play.api.libs.json._

    implicit val dateTimeWriter: Writes[DateTime] = JodaDateTimeNumberWrites

    implicit val dateTimeReader: Reads[DateTime] = {
      case JsNumber(n) => JsSuccess(new DateTime(n.toLong))
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.time"))))
    }

    implicit val dateTimeFormat: Format[DateTime] = Format(dateTimeReader, dateTimeWriter)

    implicit val formatApplicationResponse: Format[ApplicationResponse] = Json.format[ApplicationResponse]
    implicit val formatCollaborator: Format[Collaborator] = Json.format[Collaborator]
    implicit val formatApplicationLastUseDate: Format[ApplicationLastUseDate] = Json.format[ApplicationLastUseDate]
    implicit val formatPaginatedApplicationLastUseDate: Format[PaginatedApplicationLastUseResponse] = Json.format[PaginatedApplicationLastUseResponse]
  }
}

@Singleton
class SandboxThirdPartyApplicationConnector @Inject()(val config: ThirdPartyApplicationConnectorConfig,
                                                      override val httpClient: HttpClient,
                                                      override val proxiedHttpClient: ProxiedHttpClient)(implicit override val ec: ExecutionContext)
  extends ThirdPartyApplicationConnector {

  val serviceBaseUrl = config.applicationSandboxBaseUrl
  val useProxy = config.applicationSandboxUseProxy
  val bearerToken = config.applicationSandboxBearerToken
  val apiKey = config.applicationSandboxApiKey
}

@Singleton
class ProductionThirdPartyApplicationConnector @Inject()(val config: ThirdPartyApplicationConnectorConfig,
                                                         override val httpClient: HttpClient,
                                                         override val proxiedHttpClient: ProxiedHttpClient)(implicit override val ec: ExecutionContext)
  extends ThirdPartyApplicationConnector {

  val serviceBaseUrl = config.applicationProductionBaseUrl
  val useProxy = config.applicationProductionUseProxy
  val bearerToken = config.applicationProductionBearerToken
  val apiKey = config.applicationProductionApiKey
}
