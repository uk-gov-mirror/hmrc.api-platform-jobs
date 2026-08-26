/*
 * Copyright 2025 HM Revenue & Customs
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

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.DAYS
import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import play.api.Application
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Environment, UserId}

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyOrchestratorConnector
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.utils.{AsyncHmrcSpec, UrlEncoding}

class ThirdPartyOrchestratorConnectorSpec
    extends AsyncHmrcSpec
    with ResponseUtils
    with GuiceOneAppPerSuite
    with WiremockSugar
    with UrlEncoding
    with ApplicationWithCollaboratorsFixtures {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  class Setup(proxyEnabled: Boolean = false) {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockConfig                 = mock[ThirdPartyOrchestratorConnector.Config]
    when(mockConfig.serviceBaseUrl).thenReturn(wireMockUrl)

    val connector = new ThirdPartyOrchestratorConnector(
      app.injector.instanceOf[HttpClientV2],
      mockConfig
    )
  }

  "fetchApplicationsByUserId" should {

    val appAADUsers = Set[Collaborator](adminOne, adminTwo, developerOne)
    val appADUsers  = Set[Collaborator](adminOne, developerOne)

    val app1 = standardApp.withCollaborators(appAADUsers)
    val app2 = standardApp2.withCollaborators(appADUsers)

    val applicationResponses = List(app1, app2)

    "return application Ids" in new Setup {
      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("userId", equalTo(s"${adminOne.userId}"))
          .withQueryParam("status", equalTo("EXCLUDING_DELETED"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(applicationResponses)
          )
      )

      val result = await(connector.fetchApplicationsByUserId(adminOne.userId))

      result.size shouldBe 2
      result.map(_.id) should contain.allOf(standardApp.id, standardApp2.id)
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("userId", equalTo(s"${adminOne.userId}"))
          .withQueryParam("status", equalTo("EXCLUDING_DELETED"))
          .willReturn(
            aResponse()
              .withStatus(499)
          )
      )

      intercept[UpstreamErrorResponse] {
        await(connector.fetchApplicationsByUserId(adminOne.userId))
      }.statusCode shouldBe 499
    }
  }

  "findApplicationsThatHaveNotBeenUsedSince" should {
    def response(apps: List[ApplicationWithCollaborators]) = apps

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    val noDeleteRestriction              = DeleteRestrictionType.NO_RESTRICTION

    "return application details as ApplicationUsageDetails objects" in new Setup {
      val checkLastUseInstant = now.minusMonths(12).asInstant
      val createdInstant      = now.minusMonths(24).asInstant

      val dateString: String = dateFormatter.format(checkLastUseInstant)

      val oldApplication1Admin = "foo@bar.com".toLaxEmail
      val oldApplication1      = standardApp.withCollaborators(
        Collaborators.Administrator(UserId.random, oldApplication1Admin),
        Collaborators.Developer(UserId.random, "a@b.com".toLaxEmail)
      )
        .modify(
          _.copy(
            createdOn = createdInstant,
            lastAccess = Some(now.minusMonths(13).asInstant)
          )
        )
      val oldApplication2Admin = "bar@baz.com".toLaxEmail

      val oldApplication2 = standardApp.withCollaborators(
        Collaborators.Administrator(UserId.random, oldApplication2Admin),
        Collaborators.Developer(UserId.random, "b@c.com".toLaxEmail)
      )
        .modify(
          _.copy(
            createdOn = createdInstant,
            lastAccess = Some(now.minusMonths(14).asInstant)
          )
        )

      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("environment", equalTo("PRODUCTION"))
          .withQueryParam("lastUsedBefore", equalTo(dateString))
          .withQueryParam("deleteRestriction", equalTo(noDeleteRestriction.toString))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(response(List(oldApplication1, oldApplication2)))
          )
      )

      val results = await(connector.findApplicationsThatHaveNotBeenUsedSince(Environment.PRODUCTION, checkLastUseInstant))

      results should contain
      ApplicationUsageDetails(oldApplication1.id, oldApplication1.name, Set(oldApplication1Admin), oldApplication1.details.createdOn, oldApplication1.details.lastAccess)
      results should contain
      ApplicationUsageDetails(oldApplication2.id, oldApplication2.name, Set(oldApplication2Admin), oldApplication2.details.createdOn, oldApplication2.details.lastAccess)
    }

    "return empty Sequence when no results are returned" in new Setup {
      val lastUseInstant     = now.minusMonths(12).asInstant
      val dateString: String = dateFormatter.format(lastUseInstant)

      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("environment", equalTo("PRODUCTION"))
          .withQueryParam("lastUsedBefore", equalTo(dateString))
          .withQueryParam("deleteRestriction", equalTo(noDeleteRestriction.toString))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(response(Nil))
          )
      )

      val results = await(connector.findApplicationsThatHaveNotBeenUsedSince(Environment.PRODUCTION, lastUseInstant))

      results.size should be(0)
    }
  }

  "findApplicationsThatHaveNeverBeenUsedCreatedBefore" should {
    def response(apps: List[ApplicationWithCollaborators]) = apps

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    val noDeleteRestriction              = DeleteRestrictionType.NO_RESTRICTION

    "return application details as ApplicationUsageDetails objects" in new Setup {
      val createdInstant                  = now.minusDays(31).asInstant
      val createdBeforeDate               = now.minusDays(30).asInstant
      val createdBeforeDateString: String = dateFormatter.format(createdBeforeDate)

      val neverUsedApplicationAdmin = "foo@bar.com".toLaxEmail
      val neverUsedApplication      = standardApp.withCollaborators(
        Collaborators.Administrator(UserId.random, neverUsedApplicationAdmin),
        Collaborators.Developer(UserId.random, "a@b.com".toLaxEmail)
      )
        .modify(
          _.copy(
            createdOn = createdInstant,
            lastAccess = None
          )
        )

      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("environment", equalTo("PRODUCTION"))
          .withQueryParam("neverUsed", equalTo(""))
          .withQueryParam("lastUsedBefore", equalTo(createdBeforeDateString))
          .withQueryParam("deleteRestriction", equalTo(noDeleteRestriction.toString))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(response(List(neverUsedApplication)))
          )
      )

      val results = await(connector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(Environment.PRODUCTION, createdBeforeDate))

      results should contain
      ApplicationUsageDetails(
        neverUsedApplication.id,
        neverUsedApplication.name,
        Set(neverUsedApplicationAdmin),
        neverUsedApplication.details.createdOn,
        neverUsedApplication.details.lastAccess
      )
    }

    "return empty Sequence when no results are returned" in new Setup {
      val createdBeforeDate               = now.minusDays(30).asInstant
      val createdBeforeDateString: String = dateFormatter.format(createdBeforeDate)

      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("environment", equalTo("PRODUCTION"))
          .withQueryParam("neverUsed", equalTo(""))
          .withQueryParam("lastUsedBefore", equalTo(createdBeforeDateString))
          .withQueryParam("deleteRestriction", equalTo(noDeleteRestriction.toString))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(response(Nil))
          )
      )

      val results = await(connector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(Environment.PRODUCTION, createdBeforeDate))

      results.size should be(0)
    }
  }

  "findApplicationsThatShouldNotBeDeleted" should {
    def response(apps: List[ApplicationWithCollaborators]) = apps

    "ensure is checking for Do Not Delete" in new Setup {
      stubFor(
        get(urlPathEqualTo("/query"))
          .withQueryParam("environment", equalTo("PRODUCTION"))
          .withQueryParam("deleteRestriction", equalTo(DeleteRestrictionType.DO_NOT_DELETE.toString))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(response(Nil))
          )
      )

      val results = await(connector.findApplicationsThatShouldNotBeDeleted(Environment.PRODUCTION))

      results.size should be(0)
    }
  }

  "toDomain" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyOrchestratorConnector.toDomain

    "correctly convert ApplicationLastUseDates to ApplicationUsageDetails" in {
      val createdOn  = now.minusYears(1).asInstant
      val lastAccess = createdOn.plus(5, DAYS)
      val apps       = List(standardApp.modify(_.copy(createdOn = createdOn, lastAccess = Some(lastAccess))))

      val convertedApplicationDetails = toDomain(apps)

      convertedApplicationDetails.size should be(1)
      val convertedApplication = convertedApplicationDetails.head

      convertedApplication.applicationId should be(standardApp.id)
      convertedApplication.applicationName should be(standardApp.name)
      convertedApplication.creationDate should be(createdOn)
      convertedApplication.lastAccessDate should be(Some(lastAccess))
      convertedApplication.administrators.size should be(2)
      convertedApplication.administrators should contain(adminOne.emailAddress)
      convertedApplication.administrators should not contain (developerOne.emailAddress)
    }
  }
}
